// SCG 시나리오 1: Rate Limiter 검증
// payment-service burst-capacity=10/s (per IP, 1초 고정 윈도)
// 실행: k6 run --env SCG_BASE_URL=http://192.168.124.100:8090 scenario1-rate-limiter.js

import http from 'k6/http';
import { check, sleep, fail } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// ── 환경 변수 ────────────────────────────────────────────────
const SCG_BASE_URL = __ENV.SCG_BASE_URL || 'http://192.168.124.100:8090';
const JWT_SECRET = __ENV.JWT_SECRET || 'change-me-in-production-must-be-at-least-32-bytes!!';
const TARGET_PATH = '/api/v1/payments/1';
const RESULT_DIR = __ENV.RESULT_DIR || 'results';
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
const rateLimitedCount = new Counter('rate_limited_count');
const allowedCount     = new Counter('allowed_count');
const unexpectedCount  = new Counter('unexpected_count');
const rateLimitedRate  = new Rate('rate_limited_rate');
const allowedDuration  = new Trend('allowed_duration', true);
const blockedDuration  = new Trend('blocked_duration', true);

// ── 테스트 옵션 ──────────────────────────────────────────────
export const options = {
    scenarios: {
        baseline: {
            executor: 'constant-vus',
            vus: 1,
            duration: '15s',
            exec: 'baselinePhase',
            tags: { phase: 'baseline' },
        },
        burst: {
            executor: 'constant-vus',
            vus: 15,
            duration: '30s',
            startTime: '15s',
            exec: 'burstPhase',
            tags: { phase: 'burst' },
        },
        recovery: {
            executor: 'constant-vus',
            vus: 1,
            duration: '10s',
            startTime: '45s',
            exec: 'recoveryPhase',
            tags: { phase: 'recovery' },
        },
    },
    thresholds: {
        'rate_limited_rate{phase:burst}': ['rate>0.50'],
        'rate_limited_rate{phase:baseline}': ['rate<0.01'],
        'rate_limited_rate{phase:recovery}': ['rate<0.05'],
        'blocked_duration': ['p(95)<50'],
        'unexpected_count': ['count<5'],
    },
};

// ── setup: JWT 사전 검증 ─────────────────────────────────────
export function setup() {
    const token = generateJwt(1);
    const res = http.get(`${SCG_BASE_URL}${TARGET_PATH}`, {
        headers: { 'Authorization': `Bearer ${token}`, 'Content-Type': 'application/json' },
        timeout: '5s',
    });

    console.log(`[setup] preflight status=${res.status}`);

    if (res.status === 0) {
        fail(`SCG 연결 실패 (${SCG_BASE_URL}). scg-app 실행 여부를 확인하세요.`);
    }
    if (res.status === 401) {
        fail(`JWT 인증 실패. JWT_SECRET이 scg-app gateway.security.jwt-secret과 일치하는지 확인하세요.`);
    }
    if (![200, 404, 503, 429].includes(res.status)) {
        fail(`예상치 못한 preflight 응답: status=${res.status}`);
    }

    return { token };
}

// ── 요청 / 응답 분류 ─────────────────────────────────────────
function sendRequest(setupData, phaseName) {
    const token = setupData.token || generateJwt(1);
    const res = http.get(`${SCG_BASE_URL}${TARGET_PATH}`, {
        headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json',
            'X-Test-Scenario': 'rate-limiter',
            'X-Test-Phase': phaseName,
        },
        timeout: '5s',
        tags: { phase: phaseName },
    });
    classifyResponse(res, phaseName);
    return res;
}

function classifyResponse(res, phaseName) {
    if (res.status === 429) {
        rateLimitedCount.add(1, { phase: phaseName });
        rateLimitedRate.add(1, { phase: phaseName });
        blockedDuration.add(res.timings.duration, { phase: phaseName });
        check(res, {
            '[429] X-RateLimit-Remaining = 0': (r) => r.headers['X-RateLimit-Remaining'] === '0',
            '[429] 응답 50ms 이내':            (r) => r.timings.duration < 50,
        });
    } else if (res.status === 200 || res.status === 404 || res.status === 503) {
        allowedCount.add(1, { phase: phaseName });
        rateLimitedRate.add(0, { phase: phaseName });
        allowedDuration.add(res.timings.duration, { phase: phaseName });
    } else {
        unexpectedCount.add(1, { phase: phaseName });
        rateLimitedRate.add(0, { phase: phaseName });
        console.error(`[UNEXPECTED] status=${res.status} phase=${phaseName}`);
    }
}

// ── Phase 함수 ───────────────────────────────────────────────
export default function (setupData) {
    sendRequest(setupData || {}, 'default');
    sleep(0.1);
}

export function baselinePhase(setupData) {
    sendRequest(setupData, 'baseline');
    sleep(0.5);
}

export function burstPhase(setupData) {
    sendRequest(setupData, 'burst');
    sleep(0.05);
}

export function recoveryPhase(setupData) {
    sendRequest(setupData, 'recovery');
    sleep(0.5);
}

// ── 결과 산출물 생성 ─────────────────────────────────────────
export function handleSummary(data) {
    const m = (name, key) => data.metrics[name]?.values?.[key] || 0;

    const totalReqs  = m('http_reqs', 'count');
    const rlCount    = m('rate_limited_count', 'count');
    const allowedCnt = m('allowed_count', 'count');
    const unexpCnt   = m('unexpected_count', 'count');
    const rlRate     = m('rate_limited_rate', 'rate');
    const blockedP50 = m('blocked_duration', 'p(50)');
    const blockedP95 = m('blocked_duration', 'p(95)');
    const blockedP99 = m('blocked_duration', 'p(99)');
    const allowedP50 = m('allowed_duration', 'p(50)');
    const allowedP95 = m('allowed_duration', 'p(95)');
    const allowedP99 = m('allowed_duration', 'p(99)');
    const avgRps     = m('http_reqs', 'rate');

    const totalDurationSec = 55;
    const avgAllowedTps = (allowedCnt / totalDurationSec).toFixed(2);
    const testDate = new Date().toISOString();

    // ── 1. JSON: 프로그래밍 처리 / Notion API 업로드용 ────────
    const jsonReport = {
        scenario: 'scenario1-rate-limiter',
        timestamp: testDate,
        config: {
            scgBaseUrl: SCG_BASE_URL,
            targetPath: TARGET_PATH,
            burstCapacity: 10,
            replenishRate: 5,
            rateLimiterType: 'LocalRateLimiter (in-memory, 1s fixed window)',
            keyResolver: 'remoteAddrKeyResolver (client IP)',
        },
        phases: {
            baseline: { vus: 1, duration: '15s', sleepMs: 500 },
            burst:    { vus: 15, duration: '30s', sleepMs: 50 },
            recovery: { vus: 1, duration: '10s', sleepMs: 500 },
        },
        results: {
            totalRequests: totalReqs,
            allowedCount: allowedCnt,
            rateLimitedCount: rlCount,
            unexpectedCount: unexpCnt,
            rateLimitedPercent: +(rlRate * 100).toFixed(2),
            avgAllowedTps: +avgAllowedTps,
            avgRps: +avgRps.toFixed(2),
        },
        latency: {
            blocked:  { p50: +blockedP50.toFixed(2), p95: +blockedP95.toFixed(2), p99: +blockedP99.toFixed(2) },
            allowed:  { p50: +allowedP50.toFixed(2), p95: +allowedP95.toFixed(2), p99: +allowedP99.toFixed(2) },
        },
        thresholds: Object.fromEntries(
            Object.entries(data.metrics)
                .filter(([, v]) => v.thresholds)
                .map(([k, v]) => [k, v.thresholds])
        ),
        pass: !Object.values(data.metrics)
            .some(v => v.thresholds && Object.values(v.thresholds).some(t => !t.ok)),
        diagnostics: [],  // 아래에서 채움
    };

    // ── 2. CSV: Excel에서 바로 열기 + 피벗 분석용 ────────────
    const csvHeader = [
        'test_date', 'scenario', 'metric', 'value', 'unit', 'target', 'pass',
    ].join(',');

    const csvRows = [
        [testDate, 'scenario1', 'total_requests',        totalReqs,                           'count',   '-',      '-'],
        [testDate, 'scenario1', 'allowed_count',          allowedCnt,                          'count',   '-',      '-'],
        [testDate, 'scenario1', 'rate_limited_count',     rlCount,                             'count',   '-',      '-'],
        [testDate, 'scenario1', 'unexpected_count',       unexpCnt,                            'count',   '<5',     unexpCnt < 5],
        [testDate, 'scenario1', 'rate_limited_percent',   (rlRate * 100).toFixed(2),           '%',       '>50',    rlRate > 0.50],
        [testDate, 'scenario1', 'avg_allowed_tps',        avgAllowedTps,                       'req/s',   '~10',    '-'],
        [testDate, 'scenario1', 'avg_total_rps',          avgRps.toFixed(2),                   'req/s',   '-',      '-'],
        [testDate, 'scenario1', 'blocked_latency_p50',    blockedP50.toFixed(2),               'ms',      '-',      '-'],
        [testDate, 'scenario1', 'blocked_latency_p95',    blockedP95.toFixed(2),               'ms',      '<50',    blockedP95 < 50],
        [testDate, 'scenario1', 'blocked_latency_p99',    blockedP99.toFixed(2),               'ms',      '-',      '-'],
        [testDate, 'scenario1', 'allowed_latency_p50',    allowedP50.toFixed(2),               'ms',      '-',      '-'],
        [testDate, 'scenario1', 'allowed_latency_p95',    allowedP95.toFixed(2),               'ms',      '-',      '-'],
        [testDate, 'scenario1', 'allowed_latency_p99',    allowedP99.toFixed(2),               'ms',      '-',      '-'],
    ].map(row => row.join(',')).join('\n');

    const csv = `${csvHeader}\n${csvRows}\n`;

    // ── 3. 분석 진단 데이터 수집 ──────────────────────────────
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

    const burstRlFail     = isFail('phase:burst');
    const baselineRlFail  = isFail('phase:baseline');
    const recoveryRlFail  = isFail('phase:recovery');
    const blockedLatFail  = isFail('blocked_duration');
    const unexpectedFail  = isFail('unexpected_count');
    const noRlAtAll       = rlCount === 0 && totalReqs > 10;
    const tpsWayOff       = +avgAllowedTps > 20;
    const highUnexpected  = unexpCnt > totalReqs * 0.1;

    // ── 4. HTML: 브라우저 확인 / Notion 붙여넣기용 ───────────
    const passColor = jsonReport.pass ? '#22c55e' : '#ef4444';
    const passText  = jsonReport.pass ? 'PASS' : 'FAIL';

    // 진단 항목 생성: [조건, 증상, 가능한 원인, 확인 방법]
    const diagnostics = [];

    if (burstRlFail || noRlAtAll) {
        diagnostics.push({
            symptom: `Phase 2 (Burst)에서 429 차단율 ${(rlRate * 100).toFixed(1)}% — 목표(>50%) 미달`,
            causes: [
                {
                    text: 'RequestRateLimiter 필터가 payment-service route에 등록되지 않음',
                    check: 'application.yml → spring.cloud.gateway.routes[3].filters 에 RequestRateLimiter 존재 확인',
                },
                {
                    text: 'LocalRateLimiter Bean이 @Primary로 등록되지 않아 다른 RateLimiter가 사용됨',
                    check: 'LocalRateLimiter.java에 @Primary 어노테이션 확인, 로그에서 "local-rate-limiter" 문자열 검색',
                },
                {
                    text: 'burst-capacity 설정값이 예상(10)보다 훨씬 큼',
                    check: 'application.yml → gateway.rate-limiter.routes.payment-service.burst-capacity 값 확인',
                },
                {
                    text: 'KeyResolver가 IP를 제대로 추출하지 못해 VU별로 다른 키가 생성됨',
                    check: 'SCG 로그에서 X-Forwarded-For 헤더 값 확인. k6 테스트 머신이 프록시 뒤에 있는지 점검',
                },
                {
                    text: '테스트 트래픽(15 VU × sleep 50ms ≈ 300 req/s)이 실제로 burst-capacity를 초과하지 못함',
                    check: `실측 RPS: ${avgRps.toFixed(1)} req/s — burst-capacity(10)보다 높은지 확인`,
                },
            ],
        });
    }

    if (noRlAtAll) {
        diagnostics.push({
            symptom: `429 응답이 0건 — Rate Limiter가 전혀 동작하지 않음`,
            causes: [
                {
                    text: '요청이 payment-service route를 타지 않음 (Path predicate 불일치)',
                    check: `요청 경로 GET ${TARGET_PATH}가 Path=/api/v1/payments/** 에 매칭되는지 확인`,
                },
                {
                    text: 'LocalRateLimiter.isAllowed()에서 항상 allowed=true를 반환하는 버그',
                    check: 'LocalRateLimiter.java:55 — count <= config.getBurstCapacity() 조건 확인',
                },
                {
                    text: 'SCG가 테스트 대상이 아닌 다른 인스턴스로 요청이 갔음',
                    check: `SCG_BASE_URL(${SCG_BASE_URL})이 실제 scg-app 주소와 일치하는지 확인`,
                },
            ],
        });
    }

    if (baselineRlFail) {
        diagnostics.push({
            symptom: `Phase 1 (Baseline)에서 429 발생 — 저트래픽에서도 차단됨`,
            causes: [
                {
                    text: 'burst-capacity가 2 미만으로 설정됨 (baseline은 ~2 req/s)',
                    check: 'application.yml → gateway.rate-limiter.routes.payment-service.burst-capacity 값 확인',
                },
                {
                    text: '이전 테스트 잔여 카운터가 남아있음 (같은 1초 윈도 내 연속 실행)',
                    check: '이전 테스트 종료 후 2초 이상 대기 후 재실행',
                },
                {
                    text: '다른 트래픽(모니터링, health check 등)이 같은 IP에서 동시에 발생',
                    check: 'SCG access log에서 테스트 시간대 다른 요청 존재 여부 확인',
                },
            ],
        });
    }

    if (recoveryRlFail) {
        diagnostics.push({
            symptom: `Phase 3 (Recovery)에서 429 지속 — 트래픽 감소 후에도 복구 안 됨`,
            causes: [
                {
                    text: '1초 고정 윈도 카운터가 리셋되지 않음 (evictExpiredCounters 스케줄러 미동작)',
                    check: 'LocalRateLimiter.java — @EnableScheduling이 RateLimiterConfig에 선언되어 있는지 확인',
                },
                {
                    text: 'Phase 2 → Phase 3 전환 시점에 잔여 요청이 같은 윈도에 걸림',
                    check: '정상 동작이라면 1~2건의 429는 윈도 경계 문제로 허용 범위 (threshold: <5%)',
                },
            ],
        });
    }

    if (blockedLatFail) {
        diagnostics.push({
            symptom: `429 응답 P95 ${blockedP95.toFixed(1)}ms — 목표(<50ms) 초과`,
            causes: [
                {
                    text: 'SCG Netty event loop이 포화 상태 — 429를 반환하는 것 자체가 지연됨',
                    check: 'Grafana에서 reactor.netty.* 메트릭 확인. scg-app CPU 사용률 점검',
                },
                {
                    text: 'Rate Limiter 이전 필터 체인(Correlation → AccessLog → InternalBlock → Sanitize → JWT)에서 병목',
                    check: 'Jaeger span에서 JWT 검증 시간 확인. JWT 파싱이 CPU-bound라 과부하 시 지연 가능',
                },
                {
                    text: 'GC pause가 응답 시간에 영향',
                    check: 'Grafana → jvm.gc.pause 메트릭에서 테스트 시간대 GC pause 확인',
                },
            ],
        });
    }

    if (unexpectedFail || highUnexpected) {
        diagnostics.push({
            symptom: `비정상 응답 ${unexpCnt}건 (${(unexpCnt / Math.max(totalReqs, 1) * 100).toFixed(1)}%)`,
            causes: [
                {
                    text: '401 응답 — JWT 토큰이 테스트 도중 만료됨 (exp: 1시간)',
                    check: '테스트 총 시간(55s)은 1시간 미만이므로 만료 가능성 낮음. JWT_SECRET 변경 여부 확인',
                },
                {
                    text: '502/504 응답 — SCG와 downstream 간 네트워크 단절',
                    check: 'SCG access log에서 502/504 응답의 routeId와 downstream 서비스 상태 확인',
                },
                {
                    text: '400 응답 — 요청 형식 오류 (RequestSize 제한 등)',
                    check: 'SCG default-filters의 RequestSize maxSize(5MB) 초과 여부 — GET 요청이므로 가능성 낮음',
                },
            ],
        });
    }

    if (tpsWayOff && !noRlAtAll) {
        diagnostics.push({
            symptom: `허용 TPS ${avgAllowedTps} r/s — burst-capacity(10)의 2배 초과`,
            causes: [
                {
                    text: '1초 고정 윈도 경계 문제: epoch second 전환 시점에 순간적으로 burst × 2 허용',
                    check: 'LocalRateLimiter 알고리즘의 알려진 한계. 슬라이딩 윈도 방식이 아니므로 윈도 경계에서 최대 20 req 통과 가능',
                },
                {
                    text: 'k6 VU들이 서로 다른 IP로 인식됨 (KeyResolver 문제)',
                    check: 'remoteAddrKeyResolver가 X-Forwarded-For를 잘못 파싱하거나, IPv4/IPv6 혼용',
                },
            ],
        });
    }

    // JSON에 diagnostics 반영
    jsonReport.diagnostics = diagnostics.map(d => ({
        symptom: d.symptom,
        causes: d.causes.map(c => ({ cause: c.text, check: c.check })),
    }));

    // PASS일 때도 참고 정보 표시
    const passNotes = [];
    if (jsonReport.pass) {
        if (+avgAllowedTps > 12) {
            passNotes.push('허용 TPS가 burst-capacity(10)보다 약간 높은 것은 1초 고정 윈도 경계 효과로 정상입니다.');
        }
        passNotes.push('LocalRateLimiter는 인메모리 단일 노드 방식입니다. SCG를 수평 확장하면 노드별로 독립 카운터가 동작하므로 실제 허용 TPS = burst-capacity × 노드 수 가 됩니다. 운영 환경에서는 RedisRateLimiter로 전환이 필요합니다.');
    }

    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>SCG Rate Limiter 검증 결과</title>
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
  .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin: 16px 0; }
  .card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 16px; text-align: center; }
  .card-val { font-size: 2rem; font-weight: 700; }
</style>
</head>
<body>
<h1>SCG Rate Limiter 검증 결과 — 시나리오 1 <span class="badge">${passText}</span></h1>
<p style="color:#6b7280; font-size:0.85rem;">${testDate} | ${SCG_BASE_URL}</p>

<h2>목적</h2>
<p>payment-service route에 설정된 LocalRateLimiter(burst-capacity=10/s per IP)가 실제로 초과 트래픽을 429로 차단하는지 검증한다. 저트래픽(baseline) → 초과트래픽(burst) → 트래픽 감소(recovery) 3단계로 rate limiter의 차단/복구 동작을 확인한다.</p>

<div class="grid">
  <div class="card">
    <div style="color:#6b7280;font-size:0.85rem;">429 차단율 (Burst 구간)</div>
    <div class="card-val" style="color:${rlRate > 0.5 ? '#16a34a' : '#dc2626'}">${(rlRate * 100).toFixed(1)}%</div>
    <div style="font-size:0.8rem;">${totalReqs}건 중 ${rlCount}건 차단</div>
  </div>
  <div class="card">
    <div style="color:#6b7280;font-size:0.85rem;">Recovery 단계 복구</div>
    <div class="card-val" style="color:${recoveryRlFail ? '#dc2626' : '#16a34a'}">${recoveryRlFail ? 'FAIL' : 'PASS'}</div>
    <div style="font-size:0.8rem;">${recoveryRlFail ? '윈도 리셋 후 429 지속' : '트래픽 감소 후 정상 복구'}</div>
  </div>
</div>

<h2>설정</h2>
<table>
  <tr><th>항목</th><th>값</th></tr>
  <tr><td>rate-limiter</td><td>LocalRateLimiter (인메모리, 1초 고정 윈도)</td></tr>
  <tr><td>burst-capacity</td><td>10 req/s (per IP)</td></tr>
  <tr><td>key-resolver</td><td>remoteAddrKeyResolver (클라이언트 IP)</td></tr>
  <tr><td>target</td><td>GET ${TARGET_PATH} (payment-service route)</td></tr>
</table>

<h2>Phase 구성</h2>
<table>
  <tr><th>Phase</th><th>VU</th><th>Duration</th><th>Sleep</th><th>목적</th></tr>
  <tr><td>1. Baseline</td><td class="num">1</td><td>15s</td><td>500ms</td><td>burst-capacity 미만, 429 = 0% 확인</td></tr>
  <tr><td>2. Burst</td><td class="num">15</td><td>30s</td><td>50ms</td><td>burst-capacity 초과, 429 &gt; 50% 확인</td></tr>
  <tr><td>3. Recovery</td><td class="num">1</td><td>10s</td><td>500ms</td><td>윈도 리셋 후 복구 확인</td></tr>
</table>

<h2>결과</h2>
<table>
  <tr><th>지표</th><th class="num">값</th><th>목표</th><th>판정</th></tr>
  <tr><td>전체 요청</td><td class="num">${totalReqs}</td><td>-</td><td>-</td></tr>
  <tr><td>Rate Limiter 통과</td><td class="num">${allowedCnt}</td><td>-</td><td>-</td></tr>
  <tr><td>Rate Limiter 차단 (429)</td><td class="num">${rlCount}</td><td>-</td><td>-</td></tr>
  <tr><td>비정상 응답</td><td class="num">${unexpCnt}</td><td>&lt;5</td><td class="${unexpCnt < 5 ? 'pass' : 'fail'}">${unexpCnt < 5 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>429 차단율</td><td class="num">${(rlRate * 100).toFixed(1)}%</td><td>&gt;50%</td><td class="${rlRate > 0.5 ? 'pass' : 'fail'}">${rlRate > 0.5 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>평균 허용 TPS</td><td class="num">${avgAllowedTps} r/s</td><td>~10 r/s</td><td>-</td></tr>
</table>

<h2>레이턴시</h2>
<table>
  <tr><th>구분</th><th class="num">P50</th><th class="num">P95</th><th class="num">P99</th></tr>
  <tr><td>429 차단 응답</td><td class="num">${blockedP50.toFixed(1)}ms</td><td class="num">${blockedP95.toFixed(1)}ms</td><td class="num">${blockedP99.toFixed(1)}ms</td></tr>
  <tr><td>통과 응답</td><td class="num">${allowedP50.toFixed(1)}ms</td><td class="num">${allowedP95.toFixed(1)}ms</td><td class="num">${allowedP99.toFixed(1)}ms</td></tr>
</table>

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

<div class="info">
  <strong>설계 포인트 — LocalRateLimiter(burst-capacity=10/s per IP):</strong><br/>
  remoteAddrKeyResolver로 클라이언트 IP별로 카운터를 관리합니다. Redis가 아닌 인메모리 방식이라 외부 의존성 없이 빠르게 동작하지만,
  다중 인스턴스 배포 시 인스턴스 간 카운터를 공유하지 않습니다. 단일 노드 환경이나 sticky session 라우팅이 보장될 때 적합합니다.
</div>

<p class="meta">Generated by k6 scenario1-rate-limiter.js</p>
</body>
</html>`;

    // ── 콘솔: 최소한의 요약만 ────────────────────────────────
    const consoleMsg = [
        `\n[scenario1-rate-limiter] ${passText}`,
        `  요청: ${totalReqs} | 통과: ${allowedCnt} | 차단: ${rlCount} (${(rlRate * 100).toFixed(1)}%) | 비정상: ${unexpCnt}`,
        `  429 P95: ${blockedP95.toFixed(1)}ms | 통과 P95: ${allowedP95.toFixed(1)}ms | 허용 TPS: ${avgAllowedTps} r/s`,
        `  산출물: ${RESULT_DIR}/{html,json,csv}/scenario1-rate-limiter_${RUN_TAG}.*`,
        '',
    ].join('\n');

    return {
        stdout:                                              consoleMsg,
        [`${RESULT_DIR}/json/scenario1-rate-limiter_${RUN_TAG}.json`]: JSON.stringify(jsonReport, null, 2),
        [`${RESULT_DIR}/csv/scenario1-rate-limiter_${RUN_TAG}.csv`]:  csv,
        [`${RESULT_DIR}/html/scenario1-rate-limiter_${RUN_TAG}.html`]: html,
    };
}
