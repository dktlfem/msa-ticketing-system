// SCG 시나리오 2: CircuitBreaker + Fallback 검증
// payment-service-cb 설정:
//   slidingWindowType=COUNT_BASED, slidingWindowSize=10
//   failureRateThreshold=50%, waitDurationInOpenState=30s (payment 전용)
//   permittedNumberOfCallsInHalfOpenState=3
//   statusCodes: 500, 502, 503, 504
//   fallbackUri: forward:/fallback/service-unavailable
//   Retry: 3회 (GET/HEAD only), backoff 50ms→500ms
//
// 사전 준비 (터미널 2):
//   테스트 시작 15초 후: docker compose stop payment-app
//   테스트 시작 75초 후: docker compose start payment-app
//
// 실행:
//   k6 run --env SCG_BASE_URL=http://192.168.124.100:8090 scenario2-circuit-breaker.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// ── 환경 변수 ────────────────────────────────────────────────
const SCG_BASE_URL = __ENV.SCG_BASE_URL || 'http://192.168.124.100:8090';
const JWT_SECRET   = __ENV.JWT_SECRET   || 'change-me-in-production-must-be-at-least-32-bytes!!';
const TARGET_PATH  = '/api/v1/payments/1';
const RESULT_DIR   = __ENV.RESULT_DIR   || 'results';
const RUN_TAG = (() => {
    const d = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${pad(d.getMonth()+1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
})();

// ── JWT 생성 ─────────────────────────────────────────────────
function generateJwt(userId) {
    const header = encoding.b64encode(
        JSON.stringify({ alg: 'HS256', typ: 'JWT' }),
        'rawurl'
    );
    const now = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({
            sub: String(userId),
            roles: ['USER'],
            iat: now,
            exp: now + 3600,
        }),
        'rawurl'
    );
    const sigInput = `${header}.${payload}`;
    const signature = crypto.hmac('sha256', JWT_SECRET, sigInput, 'base64rawurl');
    return `${header}.${payload}.${signature}`;
}

// ── 커스텀 메트릭 ────────────────────────────────────────────
// 응답 분류 카운터
const closedCount      = new Counter('cb_closed_count');      // CB CLOSED 정상 통과 (200/404)
const errorCount       = new Counter('cb_error_count');       // upstream 에러 (502 등 — SCG CB는 fallback으로 흡수하므로 0일 수 있음)
const fallbackCount    = new Counter('cb_fallback_count');    // CB OPEN fallback (503 ProblemDetail)
const transitionCount  = new Counter('cb_transition_count');  // CB 전이 중 fallback (느린 fallback, retry 포함)
const halfOpenCount    = new Counter('cb_half_open_count');   // HALF_OPEN 복구 성공 추정
const unexpectedCount  = new Counter('cb_unexpected_count'); // 예상치 못한 응답

// 비율 메트릭
const fallbackRate = new Rate('cb_fallback_rate');
const errorRate    = new Rate('cb_error_rate');

// 레이턴시 분포
const closedDuration     = new Trend('cb_closed_duration', true);
const errorDuration      = new Trend('cb_error_duration', true);
const fallbackDuration   = new Trend('cb_fallback_duration', true);
// SCG CB는 upstream 실패 시 같은 요청 안에서 fallback으로 redirect한다.
// 이때 Retry backoff(350ms+)가 포함되어 느린 fallback이 발생한다.
// "순수 fallback" (CB 이미 OPEN, upstream 호출 없음)만 분리 측정한다.
const pureFallbackDuration = new Trend('cb_pure_fallback_duration', true);

// 전이 타임스탬프 기록 (handleSummary에서 min 으로 최초 시점 추출)
const openDetectedAt     = new Trend('cb_open_detected_at');
const errorDetectedAt    = new Trend('cb_error_detected_at');
const recoveryDetectedAt = new Trend('cb_recovery_detected_at');

// VU별 상태 추적 (모듈 스코프 = VU별 독립)
let vuFirstFallback = false;
let vuFirstError    = false;
let vuFirstRecovery = false;

// ── 테스트 옵션 ──────────────────────────────────────────────
// VU/sleep 설정: payment-service burst-capacity(10/s) 미만으로 유지하여 Rate Limiter 간섭 방지
// baseline 2VU×500ms ≈ 4 req/s, fault 3VU×400ms ≈ 7.5 req/s, recovery 2VU×500ms ≈ 4 req/s
export const options = {
    scenarios: {
        baseline: {
            executor: 'constant-vus',
            vus: 2,
            duration: '15s',
            exec: 'baselinePhase',
            tags: { phase: 'baseline' },
        },
        fault: {
            executor: 'constant-vus',
            vus: 3,
            duration: '60s',
            startTime: '15s',
            exec: 'faultPhase',
            tags: { phase: 'fault' },
        },
        recovery: {
            executor: 'constant-vus',
            vus: 2,
            duration: '30s',
            startTime: '75s',
            exec: 'recoveryPhase',
            tags: { phase: 'recovery' },
        },
    },
    thresholds: {
        // baseline: CB CLOSED → fallback 없어야 함
        'cb_fallback_rate{phase:baseline}': ['rate<0.01'],
        // fault: payment-app 중단 후 CB OPEN → fallback 비율 30% 초과
        // (수동 stop 타이밍에 따라 fault 전반부는 CLOSED → 30%로 완화)
        'cb_fallback_rate{phase:fault}': ['rate>0.30'],
        // "순수 fallback" 응답 속도: CB 이미 OPEN, upstream 호출 없이 즉시 반환
        // 150ms: 네트워크 지터(Mac→스테이징 서버) + GC pause 허용 마진 포함
        // (전이 중 fallback은 Retry backoff 포함으로 느리므로 별도 메트릭으로 분리)
        'cb_pure_fallback_duration': ['p(95)<150'],
        // fallback이 1건 이상 발생해야 함 (CB OPEN 확인)
        'cb_fallback_count': ['count>0'],
        // 비정상 응답 최소화 (429 Rate Limiter 등)
        'cb_unexpected_count': ['count<5'],
    },
};

// ── setup: 사전 검증 + 안내 ──────────────────────────────────
export function setup() {
    const token = generateJwt(1);
    const res = http.get(`${SCG_BASE_URL}${TARGET_PATH}`, {
        headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
        timeout: '5s',
    });

    console.log(`[setup] preflight status=${res.status}`);

    if (res.status === 0) {
        console.error(`SCG 연결 실패 (${SCG_BASE_URL}). scg-app 실행 여부를 확인하세요.`);
    }
    if (res.status === 401) {
        console.error(`JWT 인증 실패. JWT_SECRET이 scg-app gateway.security.jwt-secret과 일치하는지 확인하세요.`);
    }

    // CB 초기 상태 확인
    const healthRes = http.get(`${SCG_BASE_URL}/actuator/health`, { timeout: '5s' });
    if (healthRes.status === 200) {
        console.log(`[setup] actuator/health status=${healthRes.status}`);
        if (healthRes.body && healthRes.body.includes('circuitBreakers')) {
            console.log(`[setup] CircuitBreaker 상태 노출 확인 OK`);
        }
    }

    console.log('');
    console.log('┌──────────────────────────────────────────────────────────────────┐');
    console.log('│  터미널 2에서 아래 명령어를 타이밍에 맞춰 실행하세요:              │');
    console.log('│                                                                  │');
    console.log('│  [15초 후] docker compose stop payment-app                       │');
    console.log('│  [75초 후] docker compose start payment-app                      │');
    console.log('│                                                                  │');
    console.log('│  또는 한번에:                                                     │');
    console.log('│  sleep 15 && docker compose stop payment-app && \\                │');
    console.log('│  sleep 60 && docker compose start payment-app                    │');
    console.log('└──────────────────────────────────────────────────────────────────┘');
    console.log('');

    return { token };
}

// ── 응답 분류 ────────────────────────────────────────────────
function isFallbackResponse(res) {
    return res.status === 503 &&
        res.body &&
        res.body.includes('temporarily unavailable');
}

function classifyResponse(res, phaseName) {
    if (res.status === 200 || res.status === 404) {
        // CB CLOSED 또는 HALF_OPEN 성공: 정상 통과
        closedCount.add(1, { phase: phaseName });
        fallbackRate.add(0, { phase: phaseName });
        errorRate.add(0, { phase: phaseName });
        closedDuration.add(res.timings.duration, { phase: phaseName });

        check(res, {
            '[CLOSED] 정상 응답': () => true,
        });

        // fault/recovery 단계에서 200/404가 오면 CB가 CLOSED/HALF_OPEN 상태
        if ((phaseName === 'fault' || phaseName === 'recovery') && !vuFirstRecovery && vuFirstFallback) {
            vuFirstRecovery = true;
            recoveryDetectedAt.add(Date.now());
            halfOpenCount.add(1, { phase: phaseName });
            console.log(`🟢 [CB RECOVERY] HALF_OPEN/CLOSED 복구 감지 — phase=${phaseName}, status=${res.status}`);
        }

    } else if (isFallbackResponse(res)) {
        // CB fallback 응답 — 두 종류가 존재:
        // 1. "전이 fallback": CB CLOSED → upstream 실패 + Retry 3회 → CB OPEN → 같은 요청에서 fallback
        //    → duration > 200ms (Retry backoff 50+100+200=350ms 포함)
        // 2. "순수 fallback": CB 이미 OPEN → upstream 호출 없이 즉시 fallback
        //    → duration < 200ms
        const isTransition = res.timings.duration > 200;

        fallbackCount.add(1, { phase: phaseName });
        fallbackRate.add(1, { phase: phaseName });
        errorRate.add(0, { phase: phaseName });
        fallbackDuration.add(res.timings.duration, { phase: phaseName });

        if (isTransition) {
            transitionCount.add(1, { phase: phaseName });
        } else {
            pureFallbackDuration.add(res.timings.duration, { phase: phaseName });
        }

        check(res, {
            '[FALLBACK] 503 Service Unavailable': (r) => r.status === 503,
            '[FALLBACK] ProblemDetail 형식':       (r) => r.body.includes('"title"'),
        });

        if (!vuFirstFallback) {
            vuFirstFallback = true;
            openDetectedAt.add(Date.now());
            const fbType = isTransition ? '전이 fallback (Retry 포함)' : '순수 fallback (CB 이미 OPEN)';
            console.log(`🔴 [CB OPEN] 첫 fallback 감지 — phase=${phaseName}, duration=${res.timings.duration.toFixed(1)}ms, type=${fbType}`);
        }

    } else if (res.status === 502 || res.status === 503 || res.status === 504 || res.status === 500) {
        // upstream 에러 (CB가 실패로 카운트, CB CLOSED 상태에서 발생)
        errorCount.add(1, { phase: phaseName });
        fallbackRate.add(0, { phase: phaseName });
        errorRate.add(1, { phase: phaseName });
        errorDuration.add(res.timings.duration, { phase: phaseName });

        if (!vuFirstError) {
            vuFirstError = true;
            errorDetectedAt.add(Date.now());
            console.log(`🟡 [UPSTREAM ERROR] 첫 upstream 에러 — phase=${phaseName}, status=${res.status}, duration=${res.timings.duration.toFixed(1)}ms`);
        }

    } else if (res.status === 429) {
        // Rate Limiter 차단 — CB 테스트에서는 비정상
        unexpectedCount.add(1, { phase: phaseName });
        fallbackRate.add(0, { phase: phaseName });
        errorRate.add(0, { phase: phaseName });
        console.warn(`[UNEXPECTED 429] phase=${phaseName} — Rate Limiter 간섭. VU/sleep 조정 필요`);

    } else {
        unexpectedCount.add(1, { phase: phaseName });
        fallbackRate.add(0, { phase: phaseName });
        errorRate.add(0, { phase: phaseName });
        console.error(`[UNEXPECTED] status=${res.status} phase=${phaseName} body=${(res.body || '').substring(0, 200)}`);
    }
}

// ── 요청 전송 ────────────────────────────────────────────────
function sendRequest(setupData, phaseName) {
    const token = setupData.token || generateJwt(1);
    const res = http.get(`${SCG_BASE_URL}${TARGET_PATH}`, {
        headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json',
            'X-Test-Scenario': 'circuit-breaker',
            'X-Test-Phase': phaseName,
        },
        timeout: '15s', // Retry backoff 3회 포함 여유 (50+100+200=350ms + upstream timeout)
        tags: { phase: phaseName },
    });
    classifyResponse(res, phaseName);
    return res;
}

// ── Phase 함수 ───────────────────────────────────────────────
export function baselinePhase(setupData) {
    sendRequest(setupData, 'baseline');
    sleep(0.5);
}

export function faultPhase(setupData) {
    sendRequest(setupData, 'fault');
    sleep(0.4);
}

export function recoveryPhase(setupData) {
    sendRequest(setupData, 'recovery');
    sleep(0.5);
}

export default function (setupData) {
    sendRequest(setupData || {}, 'default');
    sleep(0.5);
}

// ── 결과 산출물 생성 ─────────────────────────────────────────
export function handleSummary(data) {
    const m = (name, key) => data.metrics[name]?.values?.[key] || 0;

    const totalReqs     = m('http_reqs', 'count');
    const closedCnt     = m('cb_closed_count', 'count');
    const errorCnt      = m('cb_error_count', 'count');
    const fallbackCnt   = m('cb_fallback_count', 'count');
    const transitionCnt = m('cb_transition_count', 'count');
    const halfOpenCnt   = m('cb_half_open_count', 'count');
    const unexpCnt      = m('cb_unexpected_count', 'count');
    const fbRate        = m('cb_fallback_rate', 'rate');
    const errRate       = m('cb_error_rate', 'rate');

    const closedP50  = m('cb_closed_duration', 'p(50)');
    const closedP95  = m('cb_closed_duration', 'p(95)');
    const closedP99  = m('cb_closed_duration', 'p(99)');
    const errorP50   = m('cb_error_duration', 'p(50)');
    const errorP95   = m('cb_error_duration', 'p(95)');
    const errorP99   = m('cb_error_duration', 'p(99)');
    const fbP50      = m('cb_fallback_duration', 'p(50)');
    const fbP95      = m('cb_fallback_duration', 'p(95)');
    const fbP99      = m('cb_fallback_duration', 'p(99)');
    // 순수 fallback: CB 이미 OPEN, upstream 호출 없이 즉시 반환 (<200ms)
    const pureFbP50  = m('cb_pure_fallback_duration', 'p(50)');
    const pureFbP95  = m('cb_pure_fallback_duration', 'p(95)');
    const pureFbP99  = m('cb_pure_fallback_duration', 'p(99)');
    const pureFbCnt  = fallbackCnt - transitionCnt;

    // 전이 타임스탬프 (min = 최초 감지 시점)
    const openAt     = m('cb_open_detected_at', 'min');
    const errorAt    = m('cb_error_detected_at', 'min');
    const recoveryAt = m('cb_recovery_detected_at', 'min');

    const openTime     = openAt     ? new Date(openAt).toISOString()     : null;
    const errorTime    = errorAt    ? new Date(errorAt).toISOString()    : null;
    const recoveryTime = recoveryAt ? new Date(recoveryAt).toISOString() : null;

    // OPEN 전이 소요 시간 (첫 에러 → 첫 fallback)
    const transitionMs = (openAt && errorAt) ? openAt - errorAt : null;

    const testDate = new Date().toISOString();

    // ── JSON ────────────────────────────────────────────────
    const jsonReport = {
        scenario: 'scenario2-circuit-breaker',
        timestamp: testDate,
        config: {
            scgBaseUrl: SCG_BASE_URL,
            targetPath: TARGET_PATH,
            circuitBreaker: {
                name: 'payment-service-cb',
                slidingWindowType: 'COUNT_BASED',
                slidingWindowSize: 10,
                failureRateThreshold: '50%',
                waitDurationInOpenState: '30s',
                permittedNumberOfCallsInHalfOpenState: 3,
                statusCodes: [500, 502, 503, 504],
            },
            fallback: {
                uri: 'forward:/fallback/service-unavailable',
                responseStatus: 503,
                responseFormat: 'RFC 7807 ProblemDetail',
            },
            retry: {
                retries: 3,
                methods: 'GET,HEAD',
                series: 'SERVER_ERROR',
                backoff: '50ms→100ms→200ms (factor=2)',
            },
            rateLimiter: {
                burstCapacity: 10,
                note: 'VU/sleep 설정으로 rate-limit 미만 유지',
            },
        },
        phases: {
            baseline:  { vus: 2, duration: '15s', sleep: '500ms', purpose: 'CB CLOSED 기준선 확보' },
            fault:     { vus: 3, duration: '60s', sleep: '400ms', purpose: 'upstream 장애 → CB OPEN 전이 + fallback 검증' },
            recovery:  { vus: 2, duration: '30s', sleep: '500ms', purpose: 'payment-app 재시작 → HALF_OPEN → CLOSED 복구' },
        },
        results: {
            totalRequests: totalReqs,
            closedPassCount: closedCnt,
            upstreamErrorCount: errorCnt,
            fallbackTotal: fallbackCnt,
            transitionFallback: transitionCnt,
            pureFallback: pureFbCnt,
            halfOpenRecoveryCount: halfOpenCnt,
            unexpectedCount: unexpCnt,
            fallbackPercent: +(fbRate * 100).toFixed(2),
            errorPercent: +(errRate * 100).toFixed(2),
        },
        // SCG CB 동작 발견:
        // SCG CircuitBreaker는 upstream 실패 시 502를 클라이언트에 노출하지 않고,
        // 같은 요청 안에서 fallback으로 redirect한다.
        // "전이 fallback" (>200ms): Retry backoff 포함, CB CLOSED→OPEN 전이 중
        // "순수 fallback" (<200ms): CB 이미 OPEN, upstream 호출 없이 즉시 반환
        cbBehavior: {
            upstreamErrorExposed: false,
            note: 'SCG CB catches upstream error internally and redirects to fallback in same request',
        },
        timeline: {
            firstUpstreamError: errorTime,
            firstCbOpenFallback: openTime,
            firstRecovery: recoveryTime,
            openTransitionMs: transitionMs ? +transitionMs.toFixed(0) : null,
        },
        latency: {
            closed:         { p50: +closedP50.toFixed(2), p95: +closedP95.toFixed(2), p99: +closedP99.toFixed(2) },
            error:          { p50: +errorP50.toFixed(2),  p95: +errorP95.toFixed(2),  p99: +errorP99.toFixed(2) },
            fallbackAll:    { p50: +fbP50.toFixed(2),     p95: +fbP95.toFixed(2),     p99: +fbP99.toFixed(2) },
            pureFallback:   { p50: +pureFbP50.toFixed(2), p95: +pureFbP95.toFixed(2), p99: +pureFbP99.toFixed(2) },
        },
        thresholds: Object.fromEntries(
            Object.entries(data.metrics)
                .filter(([, v]) => v.thresholds)
                .map(([k, v]) => [k, v.thresholds])
        ),
        pass: !Object.values(data.metrics)
            .some(v => v.thresholds && Object.values(v.thresholds).some(t => !t.ok)),
        diagnostics: [],
    };

    // ── CSV ─────────────────────────────────────────────────
    const csvHeader = 'test_date,scenario,metric,value,unit,target,pass';
    const csvRows = [
        [testDate, 'scenario2', 'total_requests',          totalReqs,                       'count', '-',     '-'],
        [testDate, 'scenario2', 'closed_pass_count',        closedCnt,                       'count', '-',     '-'],
        [testDate, 'scenario2', 'upstream_error_count',     errorCnt,                        'count', '-',     '-'],
        [testDate, 'scenario2', 'fallback_total',           fallbackCnt,                     'count', '>0',    fallbackCnt > 0],
        [testDate, 'scenario2', 'transition_fallback_count',transitionCnt,                   'count', '-',     '-'],
        [testDate, 'scenario2', 'pure_fallback_count',      pureFbCnt,                       'count', '-',     '-'],
        [testDate, 'scenario2', 'half_open_count',          halfOpenCnt,                     'count', '-',     '-'],
        [testDate, 'scenario2', 'unexpected_count',         unexpCnt,                        'count', '<5',    unexpCnt < 5],
        [testDate, 'scenario2', 'fallback_percent',         (fbRate * 100).toFixed(2),       '%',     '>30',   fbRate > 0.30],
        [testDate, 'scenario2', 'closed_latency_p95',       closedP95.toFixed(2),            'ms',    '-',     '-'],
        [testDate, 'scenario2', 'fallback_all_latency_p95', fbP95.toFixed(2),                'ms',    '-',     '-'],
        [testDate, 'scenario2', 'pure_fb_latency_p50',      pureFbP50.toFixed(2),            'ms',    '-',     '-'],
        [testDate, 'scenario2', 'pure_fb_latency_p95',      pureFbP95.toFixed(2),            'ms',    '<100',  pureFbP95 < 100],
        [testDate, 'scenario2', 'pure_fb_latency_p99',      pureFbP99.toFixed(2),            'ms',    '-',     '-'],
    ].map(row => row.join(',')).join('\n');

    const csv = `${csvHeader}\n${csvRows}\n`;

    // ── Diagnostics ─────────────────────────────────────────
    const diagnostics = [];

    const thresholdResults = {};
    Object.entries(data.metrics)
        .filter(([, v]) => v.thresholds)
        .forEach(([k, v]) => {
            Object.entries(v.thresholds).forEach(([expr, t]) => {
                thresholdResults[k + '||' + expr] = t.ok;
            });
        });
    const isFail = (key) => {
        const match = Object.entries(thresholdResults).find(([k]) => k.includes(key));
        return match ? !match[1] : false;
    };

    const baselineFbFail  = isFail('phase:baseline');
    const faultFbFail     = isFail('phase:fault');
    const fbLatencyFail   = isFail('cb_fallback_duration');
    const unexpFail       = isFail('cb_unexpected_count');
    const noFallbackAtAll = fallbackCnt === 0 && totalReqs > 20;
    const noErrorAtAll    = errorCnt === 0 && fallbackCnt === 0;

    if (baselineFbFail) {
        diagnostics.push({
            symptom: 'Phase 1 (Baseline)에서 fallback 발생 — CB가 이미 OPEN 상태',
            causes: [
                {
                    text: '이전 테스트 실행 후 CB가 OPEN 상태로 남아있음',
                    check: 'GET /actuator/health 에서 circuitBreakers 상태 확인. scg-app 재시작 후 재테스트',
                },
                {
                    text: 'payment-app이 기동되지 않았거나 이미 다운 상태',
                    check: 'docker compose ps payment-app 상태 확인. curl http://payment-app:8080/actuator/health',
                },
                {
                    text: 'payment-service route URI가 실제 주소와 불일치',
                    check: 'application.yml → routes[3].uri 값과 docker compose 네트워크 설정 일치 여부 확인',
                },
            ],
        });
    }

    if (faultFbFail || noFallbackAtAll) {
        diagnostics.push({
            symptom: `Phase 2 (Fault)에서 fallback 비율 ${(fbRate * 100).toFixed(1)}% — 목표(>50%) 미달`,
            causes: [
                {
                    text: 'payment-app을 테스트 도중 중단하지 않았음 (수동 조작 필요)',
                    check: '터미널 2에서 테스트 시작 15초 후 docker compose stop payment-app 실행 여부 확인',
                },
                {
                    text: 'CircuitBreaker 필터가 route에 등록되지 않음',
                    check: 'application.yml → routes[3].filters에 name: CircuitBreaker 존재 확인',
                },
                {
                    text: 'Resilience4jConfig circuitBreakerCustomizer Bean 등록 실패',
                    check: 'scg-app 시작 로그에서 Resilience4jConfig, ReactiveResilience4JCircuitBreakerFactory 관련 에러 확인',
                },
                {
                    text: 'CB statusCodes에 실제 에러 코드가 미포함',
                    check: 'payment-app 다운 시 SCG 반환 status 확인. 502가 statusCodes [500,502,503,504]에 포함되는지 검증',
                },
                {
                    text: 'slidingWindowSize(10) 채우기 전에 payment-app이 복구됨',
                    check: 'payment-app 중단 후 충분한 시간(10초 이상) 대기. error_count 확인',
                },
            ],
        });
    }

    if (noErrorAtAll && noFallbackAtAll) {
        diagnostics.push({
            symptom: 'upstream 에러(502)도 fallback(503)도 없음 — downstream 장애가 발생하지 않았음',
            causes: [
                {
                    text: 'payment-app이 테스트 전체 동안 정상 동작했음 (중단 미실행)',
                    check: 'docker compose stop payment-app을 실행했는지 확인',
                },
                {
                    text: '요청이 payment-service route를 타지 않음',
                    check: `요청 경로 GET ${TARGET_PATH}가 Path=/api/v1/payments/** 에 매칭되는지 확인`,
                },
            ],
        });
    }

    if (fbLatencyFail) {
        diagnostics.push({
            symptom: `Fallback 응답 P95 ${fbP95.toFixed(1)}ms — 목표(<100ms) 초과`,
            causes: [
                {
                    text: 'FallbackController 내부 지연 (블로킹 호출 등)',
                    check: 'FallbackController.java 에 블로킹 I/O가 없는지 확인 (MDC.get, resolveRequestId)',
                },
                {
                    text: 'SCG Netty event loop 포화로 fallback 응답 자체가 지연',
                    check: 'Grafana → reactor.netty.* 메트릭 확인. scg-app CPU 사용률 점검',
                },
                {
                    text: 'GC pause가 응답 시간에 영향',
                    check: 'Grafana → jvm.gc.pause 메트릭에서 테스트 시간대 GC pause 확인',
                },
            ],
        });
    }

    if (unexpFail) {
        diagnostics.push({
            symptom: `비정상 응답 ${unexpCnt}건`,
            causes: [
                {
                    text: '429 Rate Limiter 차단 — 테스트 요청 속도가 burst-capacity(10/s) 초과',
                    check: 'VU 수 줄이거나 sleep 늘려서 rate-limit 미만으로 조정. 현재: fault 3VU×400ms≈7.5 req/s',
                },
                {
                    text: '401 JWT 인증 실패',
                    check: 'JWT_SECRET이 scg-app gateway.security.jwt-secret과 일치하는지 확인',
                },
            ],
        });
    }

    // CB 전이 타이밍 분석
    if (transitionMs !== null) {
        diagnostics.push({
            symptom: `CB OPEN 전이 소요: ${(transitionMs / 1000).toFixed(1)}초 (첫 에러 → 첫 fallback)`,
            causes: [
                {
                    text: `COUNT_BASED slidingWindowSize=10, failureRateThreshold=50%. Retry(3회) 포함으로 실제 upstream 호출은 실패 1건당 최대 4회 발생`,
                    check: `전이 시간 ${(transitionMs / 1000).toFixed(1)}초 동안 error_count=${errorCnt}건. 10건 이상이면 Retry가 sliding window를 빠르게 채움`,
                },
            ],
        });
    }

    // 복구 분석
    if (halfOpenCnt > 0 && recoveryTime) {
        diagnostics.push({
            symptom: `HALF_OPEN → CLOSED 복구 감지: ${recoveryTime}`,
            causes: [
                {
                    text: `waitDurationInOpenState=30s 경과 후 HALF_OPEN 전이. permittedNumberOfCallsInHalfOpenState=3 중 성공 시 CLOSED 전이`,
                    check: `복구 시점에 payment-app이 정상 동작 중이었는지 확인`,
                },
            ],
        });
    } else if (halfOpenCnt === 0 && fallbackCnt > 0) {
        diagnostics.push({
            symptom: 'CB 복구 미감지 — HALF_OPEN → CLOSED 전이 미확인',
            causes: [
                {
                    text: 'payment-app 재시작이 늦어 recovery 단계(75~105s) 내에 HALF_OPEN 전이가 발생하지 않음',
                    check: '75초 시점에 docker compose start payment-app을 실행했는지 확인. payment-app 기동 시간(~10s) 고려 필요',
                },
                {
                    text: 'CB OPEN → HALF_OPEN 전이가 recovery 단계 이후에 발생 (waitDuration=30s)',
                    check: 'CB가 마지막으로 OPEN된 시점 + 30초가 105초(테스트 종료) 이전인지 확인',
                },
                {
                    text: 'payment-app 기동 후 health check가 통과하지 않아 HALF_OPEN 요청이 실패 → 다시 OPEN',
                    check: 'payment-app actuator/health 응답 확인. 기동 완료까지 약간의 시간 필요',
                },
            ],
        });
    }

    jsonReport.diagnostics = diagnostics.map(d => ({
        symptom: d.symptom,
        causes: d.causes.map(c => ({ cause: c.text, check: c.check })),
    }));

    // ── PASS 참고 메모 ──────────────────────────────────────
    const passNotes = [];
    if (jsonReport.pass) {
        if (recoveryTime) {
            passNotes.push(`HALF_OPEN → CLOSED 복구가 감지되었습니다. CB가 정상적으로 자동 복구됩니다.`);
        }
        passNotes.push(`payment-service-cb의 waitDurationInOpenState=30s는 다른 서비스(10s)보다 길게 설정했습니다. PG(TossPayments) 장애 복구에 충분한 시간을 주기 위한 의도적 설계입니다.`);
        passNotes.push(`CB OPEN 상태에서 fallback 응답은 upstream 호출 없이 SCG 내부에서 즉시 반환되므로, 장애 전파를 차단하고 클라이언트에 빠른 503 응답을 제공합니다. 이는 payment-app 장애 시 booking-app Saga 보상 트랜잭션으로 연쇄 장애가 전파되는 것을 방지합니다.`);
        if (transitionMs !== null) {
            passNotes.push(`CB OPEN 전이까지 ${(transitionMs / 1000).toFixed(1)}초 소요. slidingWindowSize=10 중 50% 실패로 OPEN 전이됩니다. Retry(3회) backoff 포함 시 전이까지 ~3초 예상이며, 실측치가 이 범위에 있으면 정상입니다.`);
        }
    }

    // ── HTML ─────────────────────────────────────────────────
    const passColor = jsonReport.pass ? '#22c55e' : '#ef4444';
    const passText  = jsonReport.pass ? 'PASS' : 'FAIL';

    const timelineHtml = `<div style="background:#f8fafc; border:1px solid #e2e8f0; border-radius:8px; padding:16px; margin:16px 0; font-family:monospace; font-size:0.85rem; line-height:2;">
  <div><strong>Timeline</strong></div>
  <div>0s ──────── 15s ─────────────────────────────── 75s ──────────── 105s</div>
  <div>│ Phase1&nbsp;&nbsp; │ Phase2 upstream 장애 → CB OPEN&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;│ Phase3 복구&nbsp;&nbsp; │</div>
  <div>│ CLOSED&nbsp;&nbsp; │ ${fallbackCnt > 0 ? `전이(${transitionCnt}) + 순수FB(${pureFbCnt}) = ${fallbackCnt}` : 'payment-app 장애 대기'}&nbsp;│ HALF_OPEN?&nbsp;&nbsp;│</div>
  <div style="margin-top:8px; line-height:1.8;">
    ${errorTime    ? `🟡 첫 upstream 에러: ${errorTime}` : '🟡 upstream 에러: 미감지'}<br/>
    ${openTime     ? `🔴 CB OPEN (첫 fallback): ${openTime}` : '🔴 CB OPEN: 미감지'}<br/>
    ${transitionMs !== null ? `⏱️ OPEN 전이 소요: ${(transitionMs / 1000).toFixed(1)}초` : '⏱️ 전이 시간: 미측정'}<br/>
    ${recoveryTime ? `🟢 CB CLOSED 복구: ${recoveryTime}` : '🟢 복구: 미감지'}
  </div>
</div>`;

    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>SCG CircuitBreaker 검증 결과</title>
<style>
  body { font-family: -apple-system, 'Pretendard', sans-serif; max-width: 900px; margin: 40px auto; padding: 0 20px; color: #1a1a1a; }
  h1 { font-size: 1.4rem; border-bottom: 2px solid #e5e7eb; padding-bottom: 8px; }
  h2 { font-size: 1.1rem; margin-top: 28px; }
  .badge { display: inline-block; padding: 4px 12px; border-radius: 4px; color: #fff; font-weight: 700; font-size: 0.85rem; background: ${passColor}; }
  table { width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 0.9rem; }
  th, td { border: 1px solid #e5e7eb; padding: 8px 12px; text-align: left; }
  th { background: #f9fafb; font-weight: 600; }
  .num { text-align: right; font-variant-numeric: tabular-nums; }
  .pass { color: #16a34a; } .fail { color: #dc2626; }
  .meta { color: #6b7280; font-size: 0.8rem; margin-top: 32px; }
  .diag { background: #fef2f2; border: 1px solid #fecaca; border-radius: 8px; padding: 16px 20px; margin: 12px 0; }
  .diag h3 { color: #dc2626; font-size: 0.95rem; margin: 0 0 8px 0; }
  .diag ol { margin: 8px 0 0 0; padding-left: 20px; }
  .diag li { margin-bottom: 10px; line-height: 1.5; }
  .diag .cause { font-weight: 600; }
  .diag .how { color: #6b7280; font-size: 0.85rem; display: block; margin-top: 2px; }
  .note { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 12px 16px; margin: 8px 0; font-size: 0.9rem; line-height: 1.6; color: #15803d; }
  .info { background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; padding: 12px 16px; margin: 8px 0; font-size: 0.9rem; line-height: 1.6; color: #1e40af; }
</style>
</head>
<body>
<h1>SCG CircuitBreaker 검증 결과 — 시나리오 2 <span class="badge">${passText}</span></h1>
<p style="color:#6b7280; font-size:0.85rem;">${testDate} | ${SCG_BASE_URL}</p>

<h2>목적</h2>
<p>downstream(payment-app) 장애 시 CircuitBreaker가 CLOSED → OPEN 전이하여 fallback 503을 즉시 반환하고, 서비스 복구 후 HALF_OPEN → CLOSED로 자동 복구되는지 검증한다.</p>

<h2>설정</h2>
<table>
  <tr><th>항목</th><th>값</th></tr>
  <tr><td>CircuitBreaker 이름</td><td>payment-service-cb</td></tr>
  <tr><td>slidingWindowType</td><td>COUNT_BASED</td></tr>
  <tr><td>slidingWindowSize</td><td>10</td></tr>
  <tr><td>failureRateThreshold</td><td>50%</td></tr>
  <tr><td>waitDurationInOpenState</td><td><strong>30s</strong> (payment 전용, 기본 10s)</td></tr>
  <tr><td>permittedNumberOfCallsInHalfOpenState</td><td>3</td></tr>
  <tr><td>fallbackUri</td><td>forward:/fallback/service-unavailable</td></tr>
  <tr><td>statusCodes (CB 실패 인식)</td><td>500, 502, 503, 504</td></tr>
  <tr><td>Retry</td><td>3회 (GET/HEAD, SERVER_ERROR, backoff 50ms→500ms)</td></tr>
</table>

<h2>Phase 구성</h2>
<table>
  <tr><th>Phase</th><th>VU</th><th>Duration</th><th>Sleep</th><th>목적</th></tr>
  <tr><td>1. Baseline</td><td class="num">2</td><td>0~15s</td><td>500ms</td><td>CB CLOSED, payment-app 정상 — 기준선 확보</td></tr>
  <tr><td>2. Fault</td><td class="num">3</td><td>15~75s</td><td>400ms</td><td>payment-app 중단 → CB OPEN 전이 + fallback 503 검증</td></tr>
  <tr><td>3. Recovery</td><td class="num">2</td><td>75~105s</td><td>500ms</td><td>payment-app 재시작 → HALF_OPEN → CLOSED 자동 복구</td></tr>
</table>

${timelineHtml}

<h2>결과</h2>
<table>
  <tr><th>지표</th><th class="num">값</th><th>목표</th><th>판정</th></tr>
  <tr><td>전체 요청</td><td class="num">${totalReqs}</td><td>-</td><td>-</td></tr>
  <tr><td>CB CLOSED 정상 통과</td><td class="num">${closedCnt}</td><td>-</td><td>-</td></tr>
  <tr><td>CB Fallback 합계</td><td class="num">${fallbackCnt}</td><td>&gt;0</td><td class="${fallbackCnt > 0 ? 'pass' : 'fail'}">${fallbackCnt > 0 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>&nbsp;&nbsp;├ 전이 fallback (Retry 포함)</td><td class="num">${transitionCnt}</td><td>-</td><td>-</td></tr>
  <tr><td>&nbsp;&nbsp;└ 순수 fallback (CB 이미 OPEN)</td><td class="num">${pureFbCnt}</td><td>-</td><td>-</td></tr>
  <tr><td>HALF_OPEN → CLOSED 복구</td><td class="num">${halfOpenCnt}</td><td>-</td><td>${halfOpenCnt > 0 ? '<span class="pass">감지</span>' : '<span style="color:#6b7280;">미감지</span>'}</td></tr>
  <tr><td>비정상 응답</td><td class="num">${unexpCnt}</td><td>&lt;5</td><td class="${unexpCnt < 5 ? 'pass' : 'fail'}">${unexpCnt < 5 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>Fallback 비율 (fault 단계)</td><td class="num">${(fbRate * 100).toFixed(1)}%</td><td>&gt;30%</td><td class="${fbRate > 0.3 ? 'pass' : 'fail'}">${fbRate > 0.3 ? 'PASS' : 'FAIL'}</td></tr>
</table>

<h2>레이턴시</h2>
<table>
  <tr><th>구분</th><th class="num">P50</th><th class="num">P95</th><th class="num">P99</th><th>설명</th></tr>
  <tr><td>CB CLOSED 통과</td><td class="num">${closedP50.toFixed(1)}ms</td><td class="num">${closedP95.toFixed(1)}ms</td><td class="num">${closedP99.toFixed(1)}ms</td><td>정상 응답 (upstream 호출 포함)</td></tr>
  <tr><td>Fallback 전체</td><td class="num">${fbP50.toFixed(1)}ms</td><td class="num">${fbP95.toFixed(1)}ms</td><td class="num">${fbP99.toFixed(1)}ms</td><td>전이 + 순수 fallback 포함</td></tr>
  <tr><td><strong>순수 Fallback</strong></td><td class="num">${pureFbP50.toFixed(1)}ms</td><td class="num"><strong>${pureFbP95.toFixed(1)}ms</strong></td><td class="num">${pureFbP99.toFixed(1)}ms</td><td>CB 이미 OPEN, upstream 호출 없음 <strong>(핵심 지표)</strong></td></tr>
</table>

<div class="info">
  <strong>SCG CB 동작 발견:</strong> Spring Cloud Gateway의 CircuitBreaker 필터는 upstream 실패 시 502를 클라이언트에 노출하지 않습니다.
  같은 요청 안에서 CB가 에러를 감지하고 fallback으로 redirect합니다.<br/>
  따라서 fallback 응답은 두 종류로 나뉩니다:<br/>
  • <strong>전이 fallback</strong> (&gt;200ms): CB CLOSED → upstream 실패 + Retry 3회 → CB OPEN → fallback. Retry backoff(350ms) 포함<br/>
  • <strong>순수 fallback</strong> (&lt;200ms): CB 이미 OPEN → upstream 호출 없이 즉시 503 반환. <strong>이 지표가 CB 성능의 핵심</strong>
</div>

<h2>Threshold 판정</h2>
<table>
  <tr><th>Threshold</th><th>결과</th></tr>
  ${Object.entries(data.metrics)
      .filter(([, v]) => v.thresholds)
      .map(([k, v]) => Object.entries(v.thresholds)
          .map(([expr, t]) => `<tr><td>${k}: ${expr}</td><td class="${t.ok ? 'pass' : 'fail'}">${t.ok ? 'PASS' : 'FAIL'}</td></tr>`)
          .join('')
      ).join('')}
</table>

<h2>분석 및 원인</h2>
${diagnostics.length > 0
    ? diagnostics.map(d => `<div class="diag">
  <h3>${d.symptom}</h3>
  <ol>
    ${d.causes.map(c => `<li><span class="cause">${c.text}</span><span class="how">확인: ${c.check}</span></li>`).join('\n    ')}
  </ol>
</div>`).join('\n')
    : passNotes.map(n => `<div class="note">${n}</div>`).join('\n')}

<p class="meta">Generated by k6 scenario2-circuit-breaker.js</p>
</body>
</html>`;

    // ── Console ─────────────────────────────────────────────
    const consoleMsg = [
        `\n[scenario2-circuit-breaker] ${passText}`,
        `  요청: ${totalReqs} | CLOSED: ${closedCnt} | FALLBACK: ${fallbackCnt} (전이:${transitionCnt} 순수:${pureFbCnt}) | HALF_OPEN: ${halfOpenCnt} | 비정상: ${unexpCnt}`,
        `  Fallback 비율: ${(fbRate * 100).toFixed(1)}% | 순수FB P95: ${pureFbP95.toFixed(1)}ms | CLOSED P95: ${closedP95.toFixed(1)}ms`,
        openTime     ? `  🔴 CB OPEN: ${openTime}` : '  🔴 CB OPEN: 미감지',
        recoveryTime ? `  🟢 CB 복구: ${recoveryTime}` : '  🟢 CB 복구: 미감지',
        `  산출물: ${RESULT_DIR}/{html,json,csv}/scenario2-circuit-breaker_${RUN_TAG}.*`,
        '',
    ].join('\n');

    return {
        stdout:                                                    consoleMsg,
        [`${RESULT_DIR}/json/scenario2-circuit-breaker_${RUN_TAG}.json`]: JSON.stringify(jsonReport, null, 2),
        [`${RESULT_DIR}/csv/scenario2-circuit-breaker_${RUN_TAG}.csv`]:  csv,
        [`${RESULT_DIR}/html/scenario2-circuit-breaker_${RUN_TAG}.html`]: html,
    };
}
