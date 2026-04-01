// SCG 시나리오 4: 필터 체인 레이턴시 오버헤드 측정
//
// SCG GlobalFilter 실행 순서 (측정 대상):
//   HIGHEST_PRECEDENCE     RequestCorrelationFilter  — UUID 생성, MDC 설정
//   HIGHEST_PRECEDENCE +2  InternalPathBlockFilter   — AntPath 매칭
//   HIGHEST_PRECEDENCE +3  RequestSanitizeFilter     — 위조 헤더 strip
//   HIGHEST_PRECEDENCE +4  JwtAuthenticationFilter   — HMAC-SHA256 검증
//   HIGHEST_PRECEDENCE +5  SecurityHeaderFilter      — response 헤더 추가
//   HIGHEST_PRECEDENCE +7  BulkheadFilter            — Resilience4j tryAcquirePermission
//   HIGHEST_PRECEDENCE +8  AccessLogFilter           — 액세스 로그 기록
//   HIGHEST_PRECEDENCE +9  AuditLogFilter            — 감사 로그 기록
//   HIGHEST_PRECEDENCE +10 RequestLogMaskingFilter   — 민감 헤더 마스킹
//   Route filter            RequestRateLimiter        — Redis 1 round-trip (burstCapacity=50)
//   Route filter            CircuitBreaker            — Resilience4j 상태 체크
//   Route filter            Retry                     — 재시도 정책
//
// 측정 방식: 동일 endpoint, 동일 RPS(15/s), 동시 실행
//   via_scg : GET /api/v1/events → SCG(8090) → concert-app  [JWT 포함]
//   direct  : GET /api/v1/events → concert-app 직접         [SCG 우회]
//
// overhead_p95 = scg_p95 - direct_p95
// 판단 기준: overhead_p95 < 10ms
// 포인트: "Redis rate-limiter round-trip + JWT 검증을 포함한 전체 필터 체인이
//            추가하는 레이턴시가 P95 기준 10ms 이내였습니다."
//
// 실행:
//   k6 run \
//     --env SCG_BASE_URL=http://192.168.124.100:8090 \
//     --env DIRECT_BASE_URL=http://192.168.124.100:8082 \
//     scenario4-filter-latency.js
//
// DIRECT_BASE_URL: concert-app 호스트 포트
//   확인: docker compose port concert-app 8080

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// ── 환경 변수 ────────────────────────────────────────────────
const SCG_BASE_URL    = __ENV.SCG_BASE_URL    || 'http://192.168.124.100:8090';
const DIRECT_BASE_URL = __ENV.DIRECT_BASE_URL || 'http://192.168.124.100:8082';
const JWT_SECRET      = __ENV.JWT_SECRET      || 'change-me-in-production-must-be-at-least-32-bytes!!';
const TARGET_PATH     = '/api/v1/events';
const RESULT_DIR      = __ENV.RESULT_DIR      || 'results';
const RUN_TAG = (() => {
    const d = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${pad(d.getMonth()+1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
})();

// ── JWT 생성 ─────────────────────────────────────────────────
function generateJwt(userId) {
    const header = encoding.b64encode(
        JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl'
    );
    const now = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({ sub: String(userId), roles: ['USER'], iat: now, exp: now + 3600 }),
        'rawurl'
    );
    const sigInput = `${header}.${payload}`;
    const signature = crypto.hmac('sha256', JWT_SECRET, sigInput, 'base64rawurl');
    return `${header}.${payload}.${signature}`;
}

// ── 커스텀 메트릭 ────────────────────────────────────────────
// SCG 경유와 직접 호출을 분리하여 오버헤드를 계산
const scgDuration    = new Trend('fl_scg_duration', true);
const directDuration = new Trend('fl_direct_duration', true);

const scgSuccessCount    = new Counter('fl_scg_success_count');
const directSuccessCount = new Counter('fl_direct_success_count');
const scgErrorCount      = new Counter('fl_scg_error_count');
const directErrorCount   = new Counter('fl_direct_error_count');

const scgSuccessRate    = new Rate('fl_scg_success_rate');
const directSuccessRate = new Rate('fl_direct_success_rate');

// ── 테스트 옵션 ──────────────────────────────────────────────
// constant-arrival-rate: 응답 시간에 관계없이 동일 RPS를 유지하여 공정한 비교
// 15 req/s: concert-service replenishRate(30) 절반 → rate-limiter 영향 최소화
export const options = {
    scenarios: {
        via_scg: {
            executor: 'constant-arrival-rate',
            rate: 15,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 5,
            maxVUs: 20,
            exec: 'scgPhase',
            tags: { path: 'scg' },
        },
        direct: {
            executor: 'constant-arrival-rate',
            rate: 15,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 5,
            maxVUs: 20,
            exec: 'directPhase',
            tags: { path: 'direct' },
        },
    },
    thresholds: {
        // SCG: 정상 응답 99% 이상
        'fl_scg_success_rate': ['rate>0.99'],
        // 직접 호출: 정상 응답 99% 이상
        'fl_direct_success_rate': ['rate>0.99'],
        // overhead 판단: handleSummary에서 계산 (scg_p95 - direct_p95 < 10ms)
        // 절대값 기준: 전체 응답 시간 500ms 이내
        'fl_scg_duration': ['p(95)<500'],
        'fl_direct_duration': ['p(95)<500'],
    },
};

// ── setup: 양쪽 연결 검증 ────────────────────────────────────
export function setup() {
    const token = generateJwt(1);

    const scgRes = http.get(`${SCG_BASE_URL}${TARGET_PATH}`, {
        headers: { 'Authorization': `Bearer ${token}` },
        timeout: '5s',
    });
    console.log(`[setup] SCG preflight: status=${scgRes.status} duration=${scgRes.timings.duration.toFixed(1)}ms`);

    if (scgRes.status === 0) {
        console.error(`[ERROR] SCG 연결 실패 (${SCG_BASE_URL}). scg-app 실행 여부 확인`);
    } else if (scgRes.status === 401) {
        console.error(`[ERROR] JWT 인증 실패. JWT_SECRET 확인`);
    } else if (scgRes.status === 429) {
        console.warn(`[WARN] SCG Rate Limiter 응답. setup 이후 정상화됨`);
    }

    const directRes = http.get(`${DIRECT_BASE_URL}${TARGET_PATH}`, {
        headers: { 'X-Auth-User-Id': '1' },
        timeout: '5s',
    });
    console.log(`[setup] Direct preflight: status=${directRes.status} duration=${directRes.timings.duration.toFixed(1)}ms`);

    if (directRes.status === 0) {
        console.error(`[ERROR] concert-app 직접 연결 실패 (${DIRECT_BASE_URL}).`);
        console.error(`        확인: docker compose port concert-app 8080`);
    }

    const overhead = scgRes.timings.duration - directRes.timings.duration;
    console.log(`[setup] 초기 오버헤드 측정: SCG=${scgRes.timings.duration.toFixed(1)}ms Direct=${directRes.timings.duration.toFixed(1)}ms Overhead=${overhead.toFixed(1)}ms`);

    return { token };
}

// ── SCG 경유 요청 ─────────────────────────────────────────────
export function scgPhase(setupData) {
    const token = setupData.token || generateJwt(1);
    const res = http.get(`${SCG_BASE_URL}${TARGET_PATH}`, {
        headers: {
            'Authorization': `Bearer ${token}`,
            'X-Test-Scenario': 'filter-latency',
            'X-Test-Path': 'via-scg',
        },
        tags: { path: 'scg' },
        timeout: '10s',
    });

    scgDuration.add(res.timings.duration, { path: 'scg' });

    const ok = res.status === 200;
    scgSuccessRate.add(ok ? 1 : 0);

    if (ok) {
        scgSuccessCount.add(1);
        check(res, { '[SCG] 200 OK': (r) => r.status === 200 });
    } else {
        scgErrorCount.add(1);
        if (res.status !== 429) {
            // 429는 rate limit이라 예외적. 그 외는 오류 로그
            console.warn(`[SCG] 비정상 응답: status=${res.status}`);
        }
    }
}

// ── 직접 호출 요청 ────────────────────────────────────────────
export function directPhase() {
    // SCG 우회: JWT 검증 없음, X-Auth-User-Id를 직접 주입
    const res = http.get(`${DIRECT_BASE_URL}${TARGET_PATH}`, {
        headers: {
            'X-Auth-User-Id': '1',
            'X-Test-Scenario': 'filter-latency',
            'X-Test-Path': 'direct',
        },
        tags: { path: 'direct' },
        timeout: '10s',
    });

    directDuration.add(res.timings.duration, { path: 'direct' });

    const ok = res.status === 200;
    directSuccessRate.add(ok ? 1 : 0);

    if (ok) {
        directSuccessCount.add(1);
        check(res, { '[DIRECT] 200 OK': (r) => r.status === 200 });
    } else {
        directErrorCount.add(1);
        console.warn(`[DIRECT] 비정상 응답: status=${res.status}`);
    }
}

export default function (setupData) {
    scgPhase(setupData || {});
}

// ── 결과 산출물 ──────────────────────────────────────────────
export function handleSummary(data) {
    const m = (name, key) => data.metrics[name]?.values?.[key] || 0;

    // SCG 지표
    const scgP50  = m('fl_scg_duration', 'p(50)');
    const scgP95  = m('fl_scg_duration', 'p(95)');
    const scgP99  = m('fl_scg_duration', 'p(99)');
    const scgMed  = m('fl_scg_duration', 'med');
    const scgMin  = m('fl_scg_duration', 'min');
    const scgMax  = m('fl_scg_duration', 'max');
    const scgSucc = m('fl_scg_success_count', 'count');
    const scgErr  = m('fl_scg_error_count', 'count');

    // 직접 지표
    const dirP50  = m('fl_direct_duration', 'p(50)');
    const dirP95  = m('fl_direct_duration', 'p(95)');
    const dirP99  = m('fl_direct_duration', 'p(99)');
    const dirMed  = m('fl_direct_duration', 'med');
    const dirMin  = m('fl_direct_duration', 'min');
    const dirMax  = m('fl_direct_duration', 'max');
    const dirSucc = m('fl_direct_success_count', 'count');
    const dirErr  = m('fl_direct_error_count', 'count');

    // ── 핵심 계산: 오버헤드 ──────────────────────────────────
    const overheadP50 = scgP50 - dirP50;
    const overheadP95 = scgP95 - dirP95;
    const overheadP99 = scgP99 - dirP99;
    const overheadMed = scgMed - dirMed;

    const passOverhead = overheadP95 < 10;
    const overallPass = passOverhead
        && m('fl_scg_success_rate', 'rate') > 0.99
        && m('fl_direct_success_rate', 'rate') > 0.99;

    const testDate = new Date().toISOString();

    // ── Diagnostics ─────────────────────────────────────────
    const diagnostics = [];

    if (scgSucc + scgErr === 0) {
        diagnostics.push({
            symptom: 'SCG 요청 결과가 없음 — SCG 연결 실패',
            causes: [
                { text: 'scg-app이 실행 중이지 않음', check: `curl ${SCG_BASE_URL}/actuator/health` },
                { text: 'SCG_BASE_URL 포트가 잘못됨', check: `docker compose port scg-app 8080` },
            ],
        });
    }
    if (dirSucc + dirErr === 0) {
        diagnostics.push({
            symptom: 'Direct 요청 결과가 없음 — concert-app 직접 연결 실패',
            causes: [
                { text: 'DIRECT_BASE_URL 포트가 잘못됨', check: `docker compose port concert-app 8080` },
                { text: 'concert-app이 실행 중이지 않음', check: `docker compose ps concert-app` },
            ],
        });
    }
    if (overheadP95 > 10 && dirSucc > 0 && scgSucc > 0) {
        const redis = `Redis rate-limiter round-trip이 큰 경우: redis-cli -h 192.168.124.101 ping 으로 레이턴시 확인`;
        diagnostics.push({
            symptom: `overhead P95 = ${overheadP95.toFixed(2)}ms — 목표(10ms) 초과`,
            causes: [
                { text: 'Redis round-trip(rate-limiter) 지연', check: redis },
                { text: 'JwtAuthenticationFilter HMAC-SHA256 CPU 부하', check: 'scg-app CPU 사용률 확인 (Grafana jvm.cpu.* 메트릭)' },
                { text: 'AccessLog/AuditLog IO 블로킹', check: 'logback-spring.xml에서 비동기 appender 설정 확인' },
                { text: 'staging 서버 부하로 인한 전반적 지연 (측정 오차)', check: '테스트 중 staging 서버 CPU/메모리 사용률 확인' },
            ],
        });
    }
    if (overheadP95 < 0 && dirSucc > 0 && scgSucc > 0) {
        diagnostics.push({
            symptom: `overhead P95 = ${overheadP95.toFixed(2)}ms — 음수 (SCG가 오히려 빠름)`,
            causes: [
                { text: 'concert-app 직접 접근 경로에 추가 네트워크 홉이 있거나 캐시 차이', check: 'DIRECT_BASE_URL 경로 확인. Nginx 등 중간 프록시 여부 확인' },
                { text: '측정 오차 (샘플 수 부족)', check: `SCG=${scgSucc}건, Direct=${dirSucc}건 — 각 300건 이상 권장` },
            ],
        });
    }

    const passNotes = [];
    if (overallPass) {
        passNotes.push(
            `SCG 필터 체인 오버헤드 P95 = ${overheadP95.toFixed(2)}ms (< 10ms 목표 달성). ` +
            `9개 GlobalFilter + Redis rate-limiter + CircuitBreaker를 거치면서도 게이트웨이가 병목이 되지 않음을 확인했습니다.`
        );
        passNotes.push(
            `오버헤드 분해 추정: JWT HMAC-SHA256 ~0.5ms, Redis rate-limiter ~1-3ms, ` +
            `나머지 필터(correlationId/sanitize/bulkhead/logging) ~1-2ms.`
        );
        passNotes.push(
            `포인트: "SCG가 병목이 아닌 근거로, SCG 경유 P95=${scgP95.toFixed(1)}ms vs ` +
            `직접 호출 P95=${dirP95.toFixed(1)}ms로 오버헤드가 ${overheadP95.toFixed(1)}ms였습니다. ` +
            `이 중 가장 큰 부분은 Redis rate-limiter round-trip이며, 이는 DDos 방어와 공정한 트래픽 분배를 위한 필수 비용입니다."`
        );
    }

    // ── JSON ────────────────────────────────────────────────
    const jsonReport = {
        scenario: 'scenario4-filter-latency',
        timestamp: testDate,
        config: {
            scgBaseUrl: SCG_BASE_URL,
            directBaseUrl: DIRECT_BASE_URL,
            targetPath: TARGET_PATH,
            ratePerSecond: 15,
            duration: '60s',
            filterChain: {
                globalFilters: [
                    'RequestCorrelationFilter (HIGHEST_PRECEDENCE)',
                    'InternalPathBlockFilter (+2)',
                    'RequestSanitizeFilter (+3)',
                    'JwtAuthenticationFilter (+4) — HMAC-SHA256',
                    'SecurityHeaderFilter (+5)',
                    'BulkheadFilter (+7) — Resilience4j',
                    'AccessLogFilter (+8)',
                    'AuditLogFilter (+9)',
                    'RequestLogMaskingFilter (+10)',
                ],
                routeFilters: [
                    'RequestRateLimiter — Redis 1 round-trip (replenishRate=30, burstCapacity=50)',
                    'CircuitBreaker (concert-service-cb)',
                    'Retry (3회, GET/HEAD, backoff 50ms→500ms)',
                ],
            },
        },
        results: {
            scg: {
                successCount: scgSucc,
                errorCount: scgErr,
                latency: { min: +scgMin.toFixed(2), med: +scgMed.toFixed(2), p50: +scgP50.toFixed(2), p95: +scgP95.toFixed(2), p99: +scgP99.toFixed(2), max: +scgMax.toFixed(2) },
            },
            direct: {
                successCount: dirSucc,
                errorCount: dirErr,
                latency: { min: +dirMin.toFixed(2), med: +dirMed.toFixed(2), p50: +dirP50.toFixed(2), p95: +dirP95.toFixed(2), p99: +dirP99.toFixed(2), max: +dirMax.toFixed(2) },
            },
            overhead: {
                med: +overheadMed.toFixed(2),
                p50: +overheadP50.toFixed(2),
                p95: +overheadP95.toFixed(2),
                p99: +overheadP99.toFixed(2),
                target: '<10ms (p95)',
                pass: passOverhead,
            },
        },
        thresholds: Object.fromEntries(
            Object.entries(data.metrics)
                .filter(([, v]) => v.thresholds)
                .map(([k, v]) => [k, v.thresholds])
        ),
        pass: overallPass,
        diagnostics: diagnostics.map(d => ({
            symptom: d.symptom,
            causes: d.causes.map(c => ({ cause: c.text, check: c.check })),
        })),
    };

    // ── CSV ─────────────────────────────────────────────────
    const csvHeader = 'test_date,scenario,metric,value,unit,target,pass';
    const csvRows = [
        [testDate, 'scenario4', 'scg_latency_p50',      scgP50.toFixed(2),           'ms', '-',      '-'],
        [testDate, 'scenario4', 'scg_latency_p95',      scgP95.toFixed(2),           'ms', '-',      '-'],
        [testDate, 'scenario4', 'scg_latency_p99',      scgP99.toFixed(2),           'ms', '-',      '-'],
        [testDate, 'scenario4', 'direct_latency_p50',   dirP50.toFixed(2),           'ms', '-',      '-'],
        [testDate, 'scenario4', 'direct_latency_p95',   dirP95.toFixed(2),           'ms', '-',      '-'],
        [testDate, 'scenario4', 'direct_latency_p99',   dirP99.toFixed(2),           'ms', '-',      '-'],
        [testDate, 'scenario4', 'overhead_p50',         overheadP50.toFixed(2),      'ms', '-',      '-'],
        [testDate, 'scenario4', 'overhead_p95',         overheadP95.toFixed(2),      'ms', '<10',    passOverhead],
        [testDate, 'scenario4', 'overhead_p99',         overheadP99.toFixed(2),      'ms', '-',      '-'],
        [testDate, 'scenario4', 'scg_success_count',    scgSucc,                     'count', '-',   '-'],
        [testDate, 'scenario4', 'direct_success_count', dirSucc,                     'count', '-',   '-'],
    ].map(r => r.join(',')).join('\n');

    const csv = `${csvHeader}\n${csvRows}\n`;

    // ── HTML ─────────────────────────────────────────────────
    const passColor = overallPass ? '#22c55e' : '#ef4444';
    const passText  = overallPass ? 'PASS' : 'FAIL';
    const ohColor   = passOverhead ? '#16a34a' : '#dc2626';

    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>SCG 필터 레이턴시 오버헤드 측정 결과</title>
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
<h1>SCG 필터 체인 레이턴시 오버헤드 — 시나리오 4 <span class="badge">${passText}</span></h1>
<p style="color:#6b7280; font-size:0.85rem;">${testDate} | SCG=${SCG_BASE_URL} | Direct=${DIRECT_BASE_URL}</p>

<h2>목적</h2>
<p>9개 GlobalFilter + 3개 Route Filter가 추가하는 레이턴시 오버헤드를 정량화한다. "게이트웨이가 병목이 아닌 근거"를 수치로 확보한다.</p>

<div class="grid">
  <div class="card">
    <div style="color:#6b7280;font-size:0.85rem;">SCG 필터 체인 오버헤드 P95</div>
    <div class="card-val" style="color:${ohColor}">${overheadP95.toFixed(1)}ms</div>
    <div style="font-size:0.8rem;">목표 &lt;10ms ${passOverhead ? '달성' : '초과'}</div>
  </div>
  <div class="card">
    <div style="color:#6b7280;font-size:0.85rem;">직접 연결 ${dirSucc > 0 ? '성공' : '실패'} (Direct)</div>
    <div class="card-val" style="color:${dirSucc > 0 ? '#16a34a' : '#dc2626'}">${dirSucc > 0 ? dirP95.toFixed(1) + 'ms' : 'FAIL'}</div>
    <div style="font-size:0.8rem;">${dirSucc > 0 ? 'concert-app P95' : 'concert-app 직접 접근 불가'}</div>
  </div>
</div>

<h2>레이턴시 비교</h2>
<table>
  <tr><th>경로</th><th class="num">P50</th><th class="num">P95</th><th class="num">P99</th><th class="num">요청 수</th></tr>
  <tr><td>SCG 경유 (필터 체인 포함)</td><td class="num">${scgP50.toFixed(1)}ms</td><td class="num">${scgP95.toFixed(1)}ms</td><td class="num">${scgP99.toFixed(1)}ms</td><td class="num">${scgSucc}</td></tr>
  <tr><td>직접 호출 (concert-app 직접)</td><td class="num">${dirP50.toFixed(1)}ms</td><td class="num">${dirP95.toFixed(1)}ms</td><td class="num">${dirP99.toFixed(1)}ms</td><td class="num">${dirSucc}</td></tr>
  <tr><td><strong>오버헤드 (SCG − Direct)</strong></td><td class="num"><strong>${overheadP50.toFixed(1)}ms</strong></td><td class="num" style="color:${ohColor}"><strong>${overheadP95.toFixed(1)}ms</strong></td><td class="num"><strong>${overheadP99.toFixed(1)}ms</strong></td><td class="num">-</td></tr>
</table>

<h2>필터 체인 구성 (오버헤드 원인)</h2>
<table>
  <tr><th>필터</th><th>추정 오버헤드</th><th>근거</th></tr>
  <tr><td>JwtAuthenticationFilter</td><td>~0.5ms</td><td>HMAC-SHA256 CPU 연산 (non-blocking)</td></tr>
  <tr><td>RequestRateLimiter (Redis)</td><td>~1–3ms</td><td>Redis round-trip (로컬 네트워크)</td></tr>
  <tr><td>RequestCorrelationFilter + Sanitize</td><td>~0.1ms</td><td>헤더 읽기/쓰기, MDC 설정</td></tr>
  <tr><td>BulkheadFilter</td><td>&lt;0.1ms</td><td>tryAcquirePermission (AtomicInteger 연산)</td></tr>
  <tr><td>AccessLogFilter + AuditLogFilter</td><td>~0.3ms</td><td>비동기 로그 큐 삽입</td></tr>
  <tr><td>CircuitBreaker 상태 체크</td><td>&lt;0.1ms</td><td>in-memory 상태 조회</td></tr>
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
  <strong>설계 포인트 — SCG 필터 체인 오버헤드:</strong><br/>
  오버헤드의 가장 큰 비중은 Redis round-trip(RequestRateLimiter ~1–3ms)입니다. 나머지 GlobalFilter들(JWT, Sanitize, CorrelationId 등)은
  각각 0.1~0.5ms 이하의 인메모리 연산입니다. Redis 서버가 동일 호스트에 있는 경우 &lt;1ms로 줄일 수 있습니다.
  포인트: "SCG가 병목이 아닌 근거로, 9개 GlobalFilter + Redis rate-limiter를 포함한 전체 오버헤드가 P95 기준 ${overheadP95.toFixed(1)}ms였습니다."
</div>

<p class="meta">Generated by k6 scenario4-filter-latency.js | rate=15req/s, duration=60s</p>
</body>
</html>`;

    const consoleMsg = [
        `\n[scenario4-filter-latency] ${passText}`,
        `  SCG   P95=${scgP95.toFixed(1)}ms (${scgSucc}건)`,
        `  Direct P95=${dirP95.toFixed(1)}ms (${dirSucc}건)`,
        `  ─────────────────────────────`,
        `  Overhead P95=${overheadP95.toFixed(1)}ms ${passOverhead ? '✓ < 10ms' : '✗ > 10ms'}`,
        `  산출물: ${RESULT_DIR}/{html,json,csv}/scenario4-filter-latency_${RUN_TAG}.*`,
        '',
    ].join('\n');

    return {
        stdout: consoleMsg,
        [`${RESULT_DIR}/json/scenario4-filter-latency_${RUN_TAG}.json`]: JSON.stringify(jsonReport, null, 2),
        [`${RESULT_DIR}/csv/scenario4-filter-latency_${RUN_TAG}.csv`]:  csv,
        [`${RESULT_DIR}/html/scenario4-filter-latency_${RUN_TAG}.html`]: html,
    };
}
