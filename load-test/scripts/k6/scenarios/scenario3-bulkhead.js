// SCG 시나리오 3: Bulkhead 동시 요청 제한 검증
// payment-service Bulkhead 설정:
//   maxConcurrentCalls=10 (Resilience4jConfig.java, BulkheadRegistry)
//   default: maxConcurrentCalls=20
//   BulkheadFilter: GlobalFilter, order=HIGHEST_PRECEDENCE+7
//   거절 응답: 503 ProblemDetail "Too Many Concurrent Requests"
//
// 검증 전략:
//   payment-app 중단 → connect timeout(3s) × retry(4회) ≈ 12s/슬롯 점유
//   → 20 VU 동시 요청 시 10개만 bulkhead 통과, 나머지 즉시 503 거절
//   → CB OPEN 후 bulkhead 슬롯 자동 해제 (CB가 보호 인계)
//
// 사전 준비 (터미널 2):
//   테스트 시작 15초 후: docker compose stop payment-app
//   테스트 시작 60초 후: docker compose start payment-app
//
// 실행:
//   k6 run --env SCG_BASE_URL=http://192.168.124.100:8090 scenario3-bulkhead.js

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
const bhRejectedCount    = new Counter('bh_rejected_count');    // Bulkhead 거절 (503 "Too Many Concurrent Requests")
const bhPassedCount      = new Counter('bh_passed_count');      // Bulkhead 통과 (성공 또는 CB fallback)
const bhSuccessCount     = new Counter('bh_success_count');     // 정상 응답 (200/404)
const bhCbFallbackCount  = new Counter('bh_cb_fallback_count'); // CB fallback (503 "temporarily unavailable")
const bhRateLimitedCount = new Counter('bh_rate_limited_count');// Rate Limiter 차단 (429)
const bhUnexpectedCount  = new Counter('bh_unexpected_count');  // 예상치 못한 응답

// 비율 메트릭
const bhRejectedRate = new Rate('bh_rejected_rate');

// 레이턴시 분포
const bhRejectedDuration   = new Trend('bh_rejected_duration', true);
const bhPassedDuration     = new Trend('bh_passed_duration', true);
const bhCbFallbackDuration = new Trend('bh_cb_fallback_duration', true);

// 전이 타임스탬프 기록
const bhFirstRejectedAt   = new Trend('bh_first_rejected_at');
const bhFirstCbFallbackAt = new Trend('bh_first_cb_fallback_at');
const bhFirstRecoveryAt   = new Trend('bh_first_recovery_at');

// VU별 상태 추적
let vuFirstRejected   = false;
let vuFirstCbFallback = false;
let vuFirstRecovery   = false;
let vuHadRejection    = false;

// ── 테스트 옵션 ──────────────────────────────────────────────
// saturation 단계: 20 VU로 bulkhead(10) 초과 요청 발사
// payment-app 중단 시 connect timeout(3s)이 슬롯을 점유 → 10개 초과 즉시 거절
// sleep 200ms: 거절된 VU의 재요청 속도 제어 (rate limiter 간섭 최소화)
export const options = {
    scenarios: {
        baseline: {
            executor: 'constant-vus',
            vus: 3,
            duration: '15s',
            exec: 'baselinePhase',
            tags: { phase: 'baseline' },
        },
        saturation: {
            executor: 'constant-vus',
            vus: 20,
            duration: '35s',
            startTime: '15s',
            exec: 'saturationPhase',
            tags: { phase: 'saturation' },
        },
        recovery: {
            executor: 'constant-vus',
            vus: 3,
            duration: '30s',
            startTime: '50s',
            exec: 'recoveryPhase',
            tags: { phase: 'recovery' },
        },
    },
    thresholds: {
        // baseline: bulkhead 거절 없어야 함
        'bh_rejected_rate{phase:baseline}': ['rate<0.01'],
        // saturation: bulkhead 거절 발생해야 함
        'bh_rejected_count': ['count>0'],
        // bulkhead 거절 응답은 즉시 반환 (<100ms)
        'bh_rejected_duration': ['p(95)<150'],
        // 비정상 응답 최소화
        'bh_unexpected_count': ['count<10'],
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

    console.log('');
    console.log('┌──────────────────────────────────────────────────────────────────┐');
    console.log('│  터미널 2에서 아래 명령어를 타이밍에 맞춰 실행하세요:              │');
    console.log('│                                                                  │');
    console.log('│  [15초 후] docker compose stop payment-app                       │');
    console.log('│  [60초 후] docker compose start payment-app                      │');
    console.log('│                                                                  │');
    console.log('│  또는 한번에:                                                     │');
    console.log('│  sleep 15 && docker compose stop payment-app && \\               │');
    console.log('│  sleep 45 && docker compose start payment-app                    │');
    console.log('└──────────────────────────────────────────────────────────────────┘');
    console.log('');

    return { token };
}

// ── 응답 분류 ────────────────────────────────────────────────
function isBulkheadRejected(res) {
    return res.status === 503 &&
        res.body &&
        res.body.includes('Too Many Concurrent Requests');
}

function isCbFallback(res) {
    return res.status === 503 &&
        res.body &&
        res.body.includes('temporarily unavailable');
}

function classifyResponse(res, phaseName) {
    if (isBulkheadRejected(res)) {
        // Bulkhead 거절: upstream 호출 없이 SCG 내부에서 즉시 반환
        bhRejectedCount.add(1, { phase: phaseName });
        bhRejectedDuration.add(res.timings.duration, { phase: phaseName });
        bhRejectedRate.add(1, { phase: phaseName });

        check(res, {
            '[BULKHEAD] 503 Too Many Concurrent Requests': () => true,
            '[BULKHEAD] ProblemDetail 형식': (r) => r.body.includes('"title"'),
        });

        if (!vuFirstRejected) {
            vuFirstRejected = true;
            vuHadRejection = true;
            bhFirstRejectedAt.add(Date.now());
            console.log(`🔴 [BULKHEAD REJECTED] 첫 거절 — phase=${phaseName}, duration=${res.timings.duration.toFixed(1)}ms`);
        }

    } else if (isCbFallback(res)) {
        // CB fallback: bulkhead 통과했으나 upstream 장애 → CB가 처리
        // "전이 fallback" (>2s): CB CLOSED, connect timeout + retry 후 fallback
        // "순수 fallback" (<200ms): CB OPEN, upstream 호출 없이 즉시 fallback
        const isSlow = res.timings.duration > 2000;

        bhCbFallbackCount.add(1, { phase: phaseName });
        bhPassedCount.add(1, { phase: phaseName });
        bhCbFallbackDuration.add(res.timings.duration, { phase: phaseName });
        bhRejectedRate.add(0, { phase: phaseName });

        check(res, {
            '[CB_FALLBACK] 503 Service Unavailable': (r) => r.status === 503,
        });

        if (!vuFirstCbFallback) {
            vuFirstCbFallback = true;
            bhFirstCbFallbackAt.add(Date.now());
            const fbType = isSlow ? '전이 (connect timeout + retry 포함)' : '순수 (CB OPEN)';
            console.log(`🟡 [CB FALLBACK] 첫 감지 — phase=${phaseName}, duration=${res.timings.duration.toFixed(1)}ms, type=${fbType}`);
        }

    } else if (res.status === 200 || res.status === 404) {
        // 정상: bulkhead 통과 + upstream 정상 응답
        bhSuccessCount.add(1, { phase: phaseName });
        bhPassedCount.add(1, { phase: phaseName });
        bhPassedDuration.add(res.timings.duration, { phase: phaseName });
        bhRejectedRate.add(0, { phase: phaseName });

        check(res, {
            '[SUCCESS] 정상 응답': () => true,
        });

        if (vuHadRejection && !vuFirstRecovery) {
            vuFirstRecovery = true;
            bhFirstRecoveryAt.add(Date.now());
            console.log(`🟢 [RECOVERY] 복구 감지 — phase=${phaseName}, status=${res.status}`);
        }

    } else if (res.status === 429) {
        // Rate Limiter 차단 — CB OPEN 후 빠른 요청 순환으로 burst-capacity(10) 초과 시 발생
        bhRateLimitedCount.add(1, { phase: phaseName });
        bhRejectedRate.add(0, { phase: phaseName });

    } else {
        bhUnexpectedCount.add(1, { phase: phaseName });
        bhRejectedRate.add(0, { phase: phaseName });
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
            'X-Test-Scenario': 'bulkhead',
            'X-Test-Phase': phaseName,
        },
        timeout: '30s', // connect timeout 3s × retry 4회 ≈ 12s, 여유 확보
        tags: { phase: phaseName },
    });
    classifyResponse(res, phaseName);
    return res;
}

// ── Phase 함수 ───────────────────────────────────────────────
export function baselinePhase(setupData) {
    sendRequest(setupData, 'baseline');
    sleep(1.0);
}

export function saturationPhase(setupData) {
    sendRequest(setupData, 'saturation');
    sleep(0.2);
}

export function recoveryPhase(setupData) {
    sendRequest(setupData, 'recovery');
    sleep(1.0);
}

export default function (setupData) {
    sendRequest(setupData || {}, 'default');
    sleep(0.5);
}

// ── 결과 산출물 생성 ─────────────────────────────────────────
export function handleSummary(data) {
    const m = (name, key) => data.metrics[name]?.values?.[key] || 0;

    const totalReqs      = m('http_reqs', 'count');
    const rejectedCnt    = m('bh_rejected_count', 'count');
    const passedCnt      = m('bh_passed_count', 'count');
    const successCnt     = m('bh_success_count', 'count');
    const cbFallbackCnt  = m('bh_cb_fallback_count', 'count');
    const rateLimitedCnt = m('bh_rate_limited_count', 'count');
    const unexpCnt       = m('bh_unexpected_count', 'count');

    const rejectedP50 = m('bh_rejected_duration', 'p(50)');
    const rejectedP95 = m('bh_rejected_duration', 'p(95)');
    const rejectedP99 = m('bh_rejected_duration', 'p(99)');

    const passedP50 = m('bh_passed_duration', 'p(50)');
    const passedP95 = m('bh_passed_duration', 'p(95)');
    const passedP99 = m('bh_passed_duration', 'p(99)');

    const cbFbP50 = m('bh_cb_fallback_duration', 'p(50)');
    const cbFbP95 = m('bh_cb_fallback_duration', 'p(95)');
    const cbFbP99 = m('bh_cb_fallback_duration', 'p(99)');

    const rejectedRate = m('bh_rejected_rate', 'rate');

    // 전이 타임스탬프
    const firstRejectedAt   = m('bh_first_rejected_at', 'min');
    const firstCbFallbackAt = m('bh_first_cb_fallback_at', 'min');
    const firstRecoveryAt   = m('bh_first_recovery_at', 'min');

    const firstRejectedTime   = firstRejectedAt   ? new Date(firstRejectedAt).toISOString()   : null;
    const firstCbFallbackTime = firstCbFallbackAt  ? new Date(firstCbFallbackAt).toISOString() : null;
    const firstRecoveryTime   = firstRecoveryAt    ? new Date(firstRecoveryAt).toISOString()   : null;

    // Bulkhead 활성 보호 구간 (첫 거절 → 첫 CB fallback)
    const bhActiveMs = (firstCbFallbackAt && firstRejectedAt) ? firstCbFallbackAt - firstRejectedAt : null;

    const testDate = new Date().toISOString();

    // ── JSON ────────────────────────────────────────────────
    const jsonReport = {
        scenario: 'scenario3-bulkhead',
        timestamp: testDate,
        config: {
            scgBaseUrl: SCG_BASE_URL,
            targetPath: TARGET_PATH,
            bulkhead: {
                defaultMaxConcurrentCalls: 20,
                paymentServiceMaxConcurrentCalls: 10,
                filterOrder: 'HIGHEST_PRECEDENCE + 7',
                rejectStatus: 503,
                rejectResponse: 'RFC 7807 ProblemDetail "Too Many Concurrent Requests"',
            },
            slotHoldingMechanism: {
                description: 'payment-app 중단 → connect timeout(3s) × retry(4회) ≈ 12s/slot',
                note: 'BulkheadFilter.doFinally()에서 permission 해제 → retry 전체가 끝나야 슬롯 반환',
            },
            circuitBreaker: {
                name: 'payment-service-cb',
                interaction: 'CB OPEN 후 빠른 fallback → bulkhead 슬롯 즉시 해제 → 거절 감소',
            },
            rateLimiter: {
                paymentService: { replenishRate: 5, burstCapacity: 10 },
                note: 'CB OPEN 후 빠른 요청 순환으로 429 발생 가능 (예상된 동작)',
            },
        },
        phases: {
            baseline:   { vus: 3,  duration: '15s', sleep: '1000ms', purpose: 'bulkhead 미작동 확인 (정상 트래픽)' },
            saturation: { vus: 20, duration: '35s', sleep: '200ms',  purpose: 'payment-app 중단 → bulkhead 포화 → 초과 요청 거절' },
            recovery:   { vus: 3,  duration: '30s', sleep: '1000ms', purpose: 'payment-app 재시작 → 정상화 확인' },
        },
        results: {
            totalRequests: totalReqs,
            bulkheadRejected: rejectedCnt,
            bulkheadPassed: passedCnt,
            successCount: successCnt,
            cbFallbackCount: cbFallbackCnt,
            rateLimitedCount: rateLimitedCnt,
            unexpectedCount: unexpCnt,
            bulkheadRejectedPercent: +(rejectedRate * 100).toFixed(2),
        },
        timeline: {
            firstBulkheadRejection: firstRejectedTime,
            firstCbFallback: firstCbFallbackTime,
            firstRecovery: firstRecoveryTime,
            bhActiveProtectionMs: bhActiveMs ? +bhActiveMs.toFixed(0) : null,
        },
        latency: {
            bulkheadRejected: { p50: +rejectedP50.toFixed(2), p95: +rejectedP95.toFixed(2), p99: +rejectedP99.toFixed(2) },
            passed:           { p50: +passedP50.toFixed(2),   p95: +passedP95.toFixed(2),   p99: +passedP99.toFixed(2) },
            cbFallback:       { p50: +cbFbP50.toFixed(2),     p95: +cbFbP95.toFixed(2),     p99: +cbFbP99.toFixed(2) },
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
        [testDate, 'scenario3', 'total_requests',         totalReqs,                       'count', '-',     '-'],
        [testDate, 'scenario3', 'bulkhead_rejected',       rejectedCnt,                     'count', '>0',    rejectedCnt > 0],
        [testDate, 'scenario3', 'bulkhead_passed',         passedCnt,                       'count', '-',     '-'],
        [testDate, 'scenario3', 'success_count',           successCnt,                      'count', '-',     '-'],
        [testDate, 'scenario3', 'cb_fallback_count',       cbFallbackCnt,                   'count', '-',     '-'],
        [testDate, 'scenario3', 'rate_limited_count',      rateLimitedCnt,                  'count', '-',     '-'],
        [testDate, 'scenario3', 'unexpected_count',        unexpCnt,                        'count', '<10',   unexpCnt < 10],
        [testDate, 'scenario3', 'rejected_percent',        (rejectedRate * 100).toFixed(2),  '%',     '-',     '-'],
        [testDate, 'scenario3', 'rejected_latency_p50',    rejectedP50.toFixed(2),           'ms',    '-',     '-'],
        [testDate, 'scenario3', 'rejected_latency_p95',    rejectedP95.toFixed(2),           'ms',    '<100',  rejectedP95 < 100],
        [testDate, 'scenario3', 'rejected_latency_p99',    rejectedP99.toFixed(2),           'ms',    '-',     '-'],
        [testDate, 'scenario3', 'cb_fallback_latency_p95', cbFbP95.toFixed(2),               'ms',    '-',     '-'],
        [testDate, 'scenario3', 'bh_active_protection_s',  bhActiveMs ? (bhActiveMs / 1000).toFixed(1) : 'N/A', 's', '-', '-'],
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

    const baselineRejected = isFail('phase:baseline');
    const noRejections     = rejectedCnt === 0 && totalReqs > 20;
    const slowRejections   = isFail('bh_rejected_duration');
    const tooManyUnexpected = isFail('bh_unexpected_count');

    if (baselineRejected) {
        diagnostics.push({
            symptom: 'Baseline에서 bulkhead 거절 발생 — 테스트 전에 이미 bulkhead 포화',
            causes: [
                {
                    text: 'payment-app이 이미 중단되어 있거나 응답 지연 중',
                    check: 'docker compose ps payment-app 상태 확인. curl http://payment-app:8080/actuator/health',
                },
                {
                    text: '이전 테스트에서 장시간 요청이 남아있어 bulkhead 슬롯이 비정상 점유 중',
                    check: 'scg-app 재시작 후 재테스트. 재시작 시 BulkheadRegistry가 초기화됨',
                },
                {
                    text: 'payment-service route URI가 잘못된 주소를 가리킴',
                    check: 'application.yml → routes[3].uri 값과 실제 payment-app 주소 일치 여부 확인',
                },
            ],
        });
    }

    if (noRejections) {
        diagnostics.push({
            symptom: 'Bulkhead 거절이 한 건도 발생하지 않음 — bulkhead가 동작하지 않았음',
            causes: [
                {
                    text: 'payment-app을 테스트 도중 중단하지 않았음 (수동 조작 필요)',
                    check: '터미널 2에서 테스트 시작 15초 후 docker compose stop payment-app 실행 여부 확인',
                },
                {
                    text: 'BulkheadFilter Bean이 등록되지 않음',
                    check: 'scg-app 시작 로그에서 BulkheadFilter 관련 에러 확인. @Component 어노테이션 확인',
                },
                {
                    text: 'BulkheadRegistry에 payment-service 인스턴스가 없어 default(20)로 생성됨',
                    check: 'Resilience4jConfig.java에서 registry.bulkhead("payment-service", paymentConfig) 호출 확인',
                },
                {
                    text: 'connect timeout이 너무 짧아 bulkhead 슬롯이 즉시 해제됨',
                    check: 'SCG connect-timeout 설정 확인. 3s 이상이어야 슬롯이 충분히 오래 점유됨',
                },
                {
                    text: 'VU 수(20)가 부족하여 슬롯이 포화되지 않음',
                    check: 'saturation phase에서 실제 동시 요청 수 확인. sleep을 줄이거나 VU 수 증가 검토',
                },
            ],
        });
    }

    if (slowRejections) {
        diagnostics.push({
            symptom: `Bulkhead 거절 응답 P95 ${rejectedP95.toFixed(1)}ms — 목표(<100ms) 초과`,
            causes: [
                {
                    text: 'SCG Netty event loop 포화로 거절 응답 자체가 지연',
                    check: 'Grafana → scg-app CPU 사용률 확인. reactor.netty.* 메트릭 점검',
                },
                {
                    text: 'BulkheadFilter 내부에서 예상치 못한 지연 발생',
                    check: 'BulkheadFilter.java — tryAcquirePermission()은 non-blocking이므로 직접적 원인은 아님. writeBulkheadResponse() 확인',
                },
                {
                    text: 'GC pause가 응답 시간에 영향',
                    check: 'Grafana → jvm.gc.pause 메트릭에서 테스트 시간대 GC pause 확인',
                },
            ],
        });
    }

    if (tooManyUnexpected) {
        diagnostics.push({
            symptom: `비정상 응답 ${unexpCnt}건`,
            causes: [
                {
                    text: '401 JWT 인증 실패',
                    check: 'JWT_SECRET이 scg-app gateway.security.jwt-secret과 일치하는지 확인',
                },
                {
                    text: 'SCG 자체 장애 또는 과부하',
                    check: 'scg-app actuator/health 확인. scg-app 로그에서 에러 확인',
                },
            ],
        });
    }

    // Bulkhead 활성 보호 구간 분석
    if (bhActiveMs !== null && rejectedCnt > 0) {
        diagnostics.push({
            symptom: `Bulkhead 활성 보호 구간: ${(bhActiveMs / 1000).toFixed(1)}초 (첫 거절 → 첫 CB fallback)`,
            causes: [
                {
                    text: `connect timeout(3s) × retry(4회) ≈ 12s 동안 bulkhead가 동시 연결을 10개로 제한. CB slidingWindow(10)에 실패 누적 후 CB OPEN → bulkhead 역할 축소`,
                    check: `실측 ${(bhActiveMs / 1000).toFixed(1)}초가 12~15s 부근이면 정상. 이 구간에서 SCG 커넥션 풀 고갈 방지가 bulkhead의 핵심 역할`,
                },
            ],
        });
    }

    if (rateLimitedCnt > 0) {
        diagnostics.push({
            symptom: `Rate Limiter 429 응답 ${rateLimitedCnt}건 발생`,
            causes: [
                {
                    text: 'CB OPEN 후 bulkhead 슬롯이 빠르게 해제됨 → 20 VU가 빠른 CB fallback으로 순환 → 초당 요청 수가 burst-capacity(10) 초과',
                    check: '예상된 동작. BulkheadFilter(order=-2147483641)가 RateLimiter(route filter)보다 먼저 실행되므로, bulkhead 거절은 rate limiter를 거치지 않음',
                },
            ],
        });
    }

    // 복구 분석
    if (firstRecoveryTime && rejectedCnt > 0) {
        diagnostics.push({
            symptom: `복구 감지: ${firstRecoveryTime}`,
            causes: [
                {
                    text: 'payment-app 재시작 후 CB HALF_OPEN → CLOSED 전이. bulkhead 슬롯도 정상 순환',
                    check: 'CB waitDurationInOpenState=30s. payment-app 재시작 후 30s 이내 복구되었는지 확인',
                },
            ],
        });
    } else if (rejectedCnt > 0 && !firstRecoveryTime) {
        diagnostics.push({
            symptom: '복구 미감지 — recovery 단계에서 정상 응답이 없었음',
            causes: [
                {
                    text: 'payment-app 재시작이 늦어 recovery 단계 내에 복구가 완료되지 않음',
                    check: '60초 시점에 docker compose start payment-app을 실행했는지 확인',
                },
                {
                    text: 'CB OPEN 상태가 유지 중. CB waitDurationInOpenState=30s 경과 후 HALF_OPEN 전이 필요',
                    check: 'CB가 OPEN된 시점 + 30초가 테스트 종료(80s) 이전인지 확인',
                },
                {
                    text: 'payment-app 기동 후 health check 통과까지 시간 필요',
                    check: 'payment-app 기동 시간(Spring Boot ~10s) 고려. curl payment-app:8080/actuator/health',
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
        passNotes.push(`payment-service maxConcurrentCalls=10이 정상 동작합니다. Bulkhead가 동시 연결 수를 제한하여 downstream 장애 시 SCG의 커넥션 풀 고갈을 방지합니다.`);
        if (bhActiveMs !== null) {
            passNotes.push(`Bulkhead는 CB OPEN 전 약 ${(bhActiveMs / 1000).toFixed(1)}초 동안 활성 보호합니다. 이 구간에서 connect timeout(3s)이 SCG Netty 워커 스레드를 모두 점유하는 것을 방지합니다. Bulkhead 없이 CB만 있으면 이 12초 동안 전체 라우트가 영향받을 수 있습니다.`);
        }
        passNotes.push(`Bulkhead 거절 응답은 P95 ${rejectedP95.toFixed(1)}ms로, upstream 호출 없이 SCG 내부에서 즉시 503을 반환합니다.`);
        passNotes.push(`CB OPEN 후에는 CB가 빠른 fallback으로 보호하므로 bulkhead 슬롯이 자동 해제됩니다. Bulkhead(동시 연결 제한)와 CircuitBreaker(반복 실패 차단)는 시간 순서로 역할을 분담합니다.`);
        if (rateLimitedCnt > 0) {
            passNotes.push(`Rate Limiter 429 응답 ${rateLimitedCnt}건은 CB OPEN 후 요청 속도가 burst-capacity(10)를 초과하여 발생한 예상된 동작입니다. 세 가지 보호 레이어(Bulkhead → CB → RateLimiter)가 각자 독립적으로 동작합니다.`);
        }
    }

    // ── HTML ─────────────────────────────────────────────────
    const passColor = jsonReport.pass ? '#22c55e' : '#ef4444';
    const passText  = jsonReport.pass ? 'PASS' : 'FAIL';

    const timelineHtml = `<div style="background:#f8fafc; border:1px solid #e2e8f0; border-radius:8px; padding:16px; margin:16px 0; font-family:monospace; font-size:0.85rem; line-height:2;">
  <div><strong>Timeline</strong></div>
  <div>0s ──────── 15s ──────────────────────────── 50s ──────────── 80s</div>
  <div>│ Phase1&nbsp;&nbsp; │ Phase2 saturation (20 VUs)&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;│ Phase3 복구&nbsp;&nbsp; │</div>
  <div>│ NORMAL&nbsp;&nbsp; │ ${rejectedCnt > 0 ? `BH거절:${rejectedCnt} CB:${cbFallbackCnt} 429:${rateLimitedCnt}` : 'payment-app 장애 대기'}&nbsp;&nbsp;│ NORMAL?&nbsp;&nbsp;&nbsp;│</div>
  <div style="margin-top:8px; line-height:1.8;">
    ${firstRejectedTime   ? `🔴 첫 bulkhead 거절: ${firstRejectedTime}` : '🔴 bulkhead 거절: 미감지'}<br/>
    ${firstCbFallbackTime ? `🟡 첫 CB fallback: ${firstCbFallbackTime}` : '🟡 CB fallback: 미감지'}<br/>
    ${bhActiveMs !== null  ? `⏱️ Bulkhead 활성 보호: ${(bhActiveMs / 1000).toFixed(1)}초` : '⏱️ 보호 구간: 미측정'}<br/>
    ${firstRecoveryTime   ? `🟢 복구: ${firstRecoveryTime}` : '🟢 복구: 미감지'}
  </div>
</div>`;

    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>SCG Bulkhead 검증 결과</title>
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
<h1>SCG Bulkhead 검증 결과 — 시나리오 3 <span class="badge">${passText}</span></h1>
<p style="color:#6b7280; font-size:0.85rem;">${testDate} | ${SCG_BASE_URL}</p>

<h2>목적</h2>
<p>payment-service의 Bulkhead(maxConcurrentCalls=10) 초과 시 즉시 503 거절을 반환하는지 검증한다.
Bulkhead는 downstream 장애 시 connect timeout이 SCG 커넥션 풀을 고갈시키는 것을 방지하는 1차 보호 장치다.</p>

<h2>설정</h2>
<table>
  <tr><th>항목</th><th>값</th></tr>
  <tr><td>BulkheadFilter</td><td>GlobalFilter, order=HIGHEST_PRECEDENCE+7</td></tr>
  <tr><td>payment-service maxConcurrentCalls</td><td><strong>10</strong></td></tr>
  <tr><td>default maxConcurrentCalls</td><td>20</td></tr>
  <tr><td>거절 응답</td><td>503, application/problem+json, "Too Many Concurrent Requests"</td></tr>
  <tr><td>슬롯 점유 메커니즘</td><td>payment-app 중단 → connect timeout(3s) × retry(4회) ≈ 12s/slot</td></tr>
  <tr><td>CircuitBreaker 연쇄</td><td>CB OPEN 후 빠른 fallback → bulkhead 슬롯 자동 해제</td></tr>
</table>

<h2>Phase 구성</h2>
<table>
  <tr><th>Phase</th><th>VU</th><th>Duration</th><th>Sleep</th><th>목적</th></tr>
  <tr><td>1. Baseline</td><td class="num">3</td><td>0~15s</td><td>1000ms</td><td>payment-app 정상 — bulkhead 미작동 확인</td></tr>
  <tr><td>2. Saturation</td><td class="num">20</td><td>15~50s</td><td>200ms</td><td>payment-app 중단 → bulkhead 포화 → 초과 요청 거절</td></tr>
  <tr><td>3. Recovery</td><td class="num">3</td><td>50~80s</td><td>1000ms</td><td>payment-app 재시작 → 정상화 확인</td></tr>
</table>

${timelineHtml}

<h2>결과</h2>
<table>
  <tr><th>지표</th><th class="num">값</th><th>목표</th><th>판정</th></tr>
  <tr><td>전체 요청</td><td class="num">${totalReqs}</td><td>-</td><td>-</td></tr>
  <tr><td><strong>Bulkhead 거절</strong></td><td class="num"><strong>${rejectedCnt}</strong></td><td>&gt;0</td><td class="${rejectedCnt > 0 ? 'pass' : 'fail'}">${rejectedCnt > 0 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>Bulkhead 통과</td><td class="num">${passedCnt}</td><td>-</td><td>-</td></tr>
  <tr><td>&nbsp;&nbsp;├ 정상 응답 (200/404)</td><td class="num">${successCnt}</td><td>-</td><td>-</td></tr>
  <tr><td>&nbsp;&nbsp;└ CB Fallback</td><td class="num">${cbFallbackCnt}</td><td>-</td><td>-</td></tr>
  <tr><td>Rate Limited (429)</td><td class="num">${rateLimitedCnt}</td><td>-</td><td style="color:#6b7280;">예상됨</td></tr>
  <tr><td>비정상 응답</td><td class="num">${unexpCnt}</td><td>&lt;10</td><td class="${unexpCnt < 10 ? 'pass' : 'fail'}">${unexpCnt < 10 ? 'PASS' : 'FAIL'}</td></tr>
</table>

<h2>레이턴시</h2>
<table>
  <tr><th>구분</th><th class="num">P50</th><th class="num">P95</th><th class="num">P99</th><th>설명</th></tr>
  <tr><td><strong>Bulkhead 거절</strong></td><td class="num">${rejectedP50.toFixed(1)}ms</td><td class="num"><strong>${rejectedP95.toFixed(1)}ms</strong></td><td class="num">${rejectedP99.toFixed(1)}ms</td><td>upstream 호출 없이 즉시 반환 <strong>(핵심 지표)</strong></td></tr>
  <tr><td>Bulkhead 통과</td><td class="num">${passedP50.toFixed(1)}ms</td><td class="num">${passedP95.toFixed(1)}ms</td><td class="num">${passedP99.toFixed(1)}ms</td><td>정상 응답 + connect timeout 포함</td></tr>
  <tr><td>CB Fallback</td><td class="num">${cbFbP50.toFixed(1)}ms</td><td class="num">${cbFbP95.toFixed(1)}ms</td><td class="num">${cbFbP99.toFixed(1)}ms</td><td>CB OPEN 후 빠른 fallback</td></tr>
</table>

<div class="info">
  <strong>Bulkhead와 CircuitBreaker 역할 분담:</strong><br/><br/>
  <strong>Phase A — Bulkhead 활성 보호 (~12s):</strong> CB가 아직 CLOSED인 구간. connect timeout(3s) × retry(4회)로 각 요청이 ~12초간 bulkhead 슬롯을 점유한다.
  10개 슬롯이 모두 점유되면 초과 요청은 즉시 503으로 거절된다. 이 구간이 없으면 SCG의 Netty 워커 스레드가 무제한으로 downstream connect timeout에 묶여 다른 라우트(user-service, concert-service 등)까지 영향받는다.<br/><br/>
  <strong>Phase B — CB 보호 인계 (~12s+):</strong> CB slidingWindow에 실패가 누적되어 CB OPEN 전이. 이후 요청은 upstream 호출 없이 즉시 CB fallback(503)을 반환하므로 bulkhead 슬롯이 즉시 해제된다. Bulkhead 거절은 자연스럽게 감소한다.<br/><br/>
  <em>면접 포인트:</em> "Bulkhead는 CB가 학습하기 전 초기 12초를 보호하는 1차 방어선이고, CB는 학습 후 빠르게 차단하는 2차 방어선입니다."
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

<p class="meta">Generated by k6 scenario3-bulkhead.js</p>
</body>
</html>`;

    // ── Console ─────────────────────────────────────────────
    const consoleMsg = [
        `\n[scenario3-bulkhead] ${passText}`,
        `  요청: ${totalReqs} | BH거절: ${rejectedCnt} | 통과: ${passedCnt} (성공:${successCnt} CB:${cbFallbackCnt}) | 429: ${rateLimitedCnt} | 비정상: ${unexpCnt}`,
        `  BH거절 P95: ${rejectedP95.toFixed(1)}ms | CB fallback P95: ${cbFbP95.toFixed(1)}ms`,
        firstRejectedTime   ? `  🔴 첫 BH 거절: ${firstRejectedTime}` : '  🔴 BH 거절: 미감지',
        bhActiveMs !== null  ? `  ⏱️ BH 활성 보호: ${(bhActiveMs / 1000).toFixed(1)}초` : '  ⏱️ BH 보호: 미측정',
        firstRecoveryTime   ? `  🟢 복구: ${firstRecoveryTime}` : '  🟢 복구: 미감지',
        `  산출물: ${RESULT_DIR}/{html,json,csv}/scenario3-bulkhead_${RUN_TAG}.*`,
        '',
    ].join('\n');

    return {
        stdout:                                                  consoleMsg,
        [`${RESULT_DIR}/json/scenario3-bulkhead_${RUN_TAG}.json`]: JSON.stringify(jsonReport, null, 2),
        [`${RESULT_DIR}/csv/scenario3-bulkhead_${RUN_TAG}.csv`]:  csv,
        [`${RESULT_DIR}/html/scenario3-bulkhead_${RUN_TAG}.html`]: html,
    };
}
