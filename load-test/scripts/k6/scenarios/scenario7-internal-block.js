// SCG 시나리오 7: /internal/** 차단 검증
//
// 목적:
//   InternalPathBlockFilter(order=HIGHEST_PRECEDENCE+2)가 부하 중에도
//   /internal/** 경로를 100% 차단하는지 검증.
//   정상 트래픽과 혼합 시에도 단 한 건도 통과(bypass)되지 않아야 한다.
//
// 두 시나리오 동시 실행:
//   [normal_traffic]   GET /api/v1/events/1           — 10 req/s (정상 공연 조회)
//   [internal_attack]  GET /internal/v1/reservations/1  — 30 req/s (차단 검증)
//
// 판단 기준:
//   block_rate      = 100%  (internal 요청이 전부 403)
//   bypass_count    = 0     (200/404/5xx가 나오면 즉시 실패)
//   normal_p95      < 300ms
//   normal_error    < 1%    (429 제외)
//
// 핵심 필터:
//   InternalPathBlockFilter (HIGHEST_PRECEDENCE+2)
//     - AntPathMatcher로 /internal/** 매칭
//     - 매칭 시 403 ProblemDetail 반환, 다음 필터로 전달하지 않음
//     - JwtAuthenticationFilter(+4)보다 먼저 실행 → JWT 없어도 403 반환
//     - Rate-limiter(route filter)보다 먼저 실행 → Redis 조회 없이 차단
//
// 실행:
//   k6 run \
//     --env SCG_BASE_URL=http://192.168.124.100:8090 \
//     scenario7-internal-block.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// ── 환경 변수 ────────────────────────────────────────────────
const SCG_BASE_URL = __ENV.SCG_BASE_URL || 'http://192.168.124.100:8090';
const JWT_SECRET   = __ENV.JWT_SECRET   || 'change-me-in-production-must-be-at-least-32-bytes!!';
const RESULT_DIR   = __ENV.RESULT_DIR   || 'results';
const RUN_TAG = (() => {
    const d = new Date();
    return `${d.getFullYear()}${String(d.getMonth()+1).padStart(2,'0')}${String(d.getDate()).padStart(2,'0')}-`
         + `${String(d.getHours()).padStart(2,'0')}${String(d.getMinutes()).padStart(2,'0')}${String(d.getSeconds()).padStart(2,'0')}`;
})();

// ── 커스텀 메트릭 ─────────────────────────────────────────────
// internal_attack 시나리오
const internalReqs    = new Counter('ib_internal_total');     // 전체 internal 요청 수
const internalBlocked = new Counter('ib_internal_blocked');   // 403 수 (차단 성공)
const internalBypass  = new Counter('ib_internal_bypass');    // 비-403 수 (차단 실패 — 반드시 0)
const blockRate       = new Rate('ib_block_rate');            // 차단율 (목표: 100%)
const blockDuration   = new Trend('ib_block_duration');       // 차단 응답 시간 (필터가 빠르면 < 5ms 기대)

// normal_traffic 시나리오
const normalReqs      = new Counter('ib_normal_total');
const normalErrors    = new Counter('ib_normal_errors');
const normalDuration  = new Trend('ib_normal_duration');
const normalErrorRate = new Rate('ib_normal_error_rate');

// ── k6 시나리오 설정 ──────────────────────────────────────────
export const options = {
    scenarios: {
        // 공격자: /internal/** 경로 고속 요청
        internal_attack: {
            executor: 'constant-arrival-rate',
            rate: 30,
            timeUnit: '1s',
            duration: '2m',
            preAllocatedVUs: 10,
            maxVUs: 20,
            tags: { scenario: 'internal_attack' },
        },
        // 정상 트래픽: 공연 조회 (공격 중에도 정상 응답 유지 확인)
        normal_traffic: {
            executor: 'constant-arrival-rate',
            rate: 10,
            timeUnit: '1s',
            duration: '2m',
            preAllocatedVUs: 5,
            maxVUs: 10,
            tags: { scenario: 'normal_traffic' },
        },
    },
    thresholds: {
        // 핵심 보증: bypass가 단 1건이라도 있으면 테스트 실패
        'ib_internal_bypass': ['count == 0'],
        // 차단율 100% 보증
        'ib_block_rate':      ['rate >= 0.999'],
        // 차단 자체는 빨라야 함 (필터가 일찍 종료하므로)
        'ib_block_duration':  ['p(95) < 50'],
        // 정상 트래픽 영향 없음
        'ib_normal_error_rate': ['rate < 0.01'],
        'ib_normal_duration':   ['p(95) < 300'],
    },
    tags: { testid: 'scenario7-internal-block' },
};

// ── JWT 생성 유틸리티 ─────────────────────────────────────────
function base64url(data) {
    return encoding.b64encode(data, 'rawurl');
}

function sign(input, secret) {
    const key = encoding.b64encode(secret, 'rawurl');
    return base64url(crypto.hmac('sha256', key, input, 'binary'));
}

function makeJwt(userId, roles) {
    const header  = base64url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
    const payload = base64url(JSON.stringify({
        sub: String(userId),
        roles: roles || ['USER'],
        iat: Math.floor(Date.now() / 1000),
        exp: Math.floor(Date.now() / 1000) + 3600,
    }));
    const sig = sign(`${header}.${payload}`, JWT_SECRET);
    return `${header}.${payload}.${sig}`;
}

// ── internal_attack: /internal/** 차단 검증 ──────────────────
//
// 시도하는 경로 목록:
//   /internal/v1/reservations/1       (booking-app 내부 예약 조회)
//   /internal/v1/seats/1              (concert-app 내부 좌석 조회)
//   /internal/v1/waiting-room/tokens/validate  (waitingroom-app 내부 토큰 검증)
//   /internal/v1/reservations/1/confirm        (booking-app 내부 예약 확정)
//   /internal/v1/seats/1/hold                  (concert-app 내부 좌석 선점)
//
// 모든 경로가 InternalPathBlockFilter에 의해 403으로 차단돼야 함.
// JWT 없이 전송해서 JwtAuthenticationFilter(+4) 이전에 차단됨을 확인.
const INTERNAL_PATHS = [
    '/internal/v1/reservations/1',
    '/internal/v1/seats/1',
    '/internal/v1/waiting-room/tokens/validate',
    '/internal/v1/reservations/1/confirm',
    '/internal/v1/seats/1/hold',
];

export function internal_attack() {
    const path = INTERNAL_PATHS[Math.floor(Math.random() * INTERNAL_PATHS.length)];
    const url  = `${SCG_BASE_URL}${path}`;

    const start = Date.now();
    // JWT 없이 전송: 403이 아닌 401이 나오면 JwtAuthFilter가 먼저 실행된 것 → 설계 오류
    const res = http.get(url, {
        tags: { scenario: 'internal_attack', path_category: 'internal' },
        timeout: '5s',
    });
    const elapsed = Date.now() - start;

    internalReqs.add(1);
    blockDuration.add(elapsed);

    const is403 = res.status === 403;
    blockRate.add(is403);

    if (is403) {
        internalBlocked.add(1);
    } else {
        // bypass 발생: 상태코드와 응답 본문을 기록
        internalBypass.add(1);
        console.error(`[BYPASS] path=${path} status=${res.status} body=${res.body ? res.body.substring(0, 200) : '(empty)'}`);
    }

    check(res, {
        // 반드시 403 차단 응답
        'internal path blocked (403)': (r) => r.status === 403,
        // JWT가 없어도 403이어야 함 (401이면 InternalPathBlockFilter보다 JwtAuthFilter가 먼저 실행된 것)
        'not 401 (block before JWT check)': (r) => r.status !== 401,
        // ProblemDetail 응답 형식 확인
        'response has status field': (r) => {
            try {
                const body = JSON.parse(r.body);
                return body.status === 403 || body.title !== undefined;
            } catch {
                return false;
            }
        },
    });
}

// ── normal_traffic: 정상 공연 조회 ────────────────────────────
export function normal_traffic() {
    const userId = Math.floor(Math.random() * 100) + 1;
    const jwt    = makeJwt(userId, ['USER']);

    const start = Date.now();
    const res = http.get(`${SCG_BASE_URL}/api/v1/events/1`, {
        headers: { Authorization: `Bearer ${jwt}` },
        tags: { scenario: 'normal_traffic', path_category: 'external' },
        timeout: '5s',
    });
    const elapsed = Date.now() - start;

    normalReqs.add(1);
    normalDuration.add(elapsed);

    const isError = res.status !== 200 && res.status !== 429;
    normalErrors.add(isError ? 1 : 0);
    normalErrorRate.add(isError);

    check(res, {
        'normal traffic: not 5xx':     (r) => r.status < 500,
        'normal traffic: not blocked':  (r) => r.status !== 403,
        'normal traffic: has response': (r) => r.body !== null && r.body.length > 0,
    });

    sleep(0.1);
}

// ── default 함수: 시나리오별로 분기 ──────────────────────────
export default function () {
    // options.scenarios에서 executor가 호출하므로 실제로는 사용되지 않음
    // 단독 실행 시 폴백
    internal_attack();
}

// ── handleSummary: JSON + CSV + HTML 리포트 ───────────────────
export function handleSummary(data) {
    const metrics = data.metrics;

    function mv(name, stat) {
        try { return metrics[name] && metrics[name].values ? (metrics[name].values[stat] || 0) : 0; }
        catch { return 0; }
    }

    const totalInternal  = mv('ib_internal_total', 'count');
    const blocked        = mv('ib_internal_blocked', 'count');
    const bypass         = mv('ib_internal_bypass', 'count');
    const blockRateVal   = totalInternal > 0 ? (blocked / totalInternal * 100) : 0;
    const blockP50       = mv('ib_block_duration', 'p(50)');
    const blockP95       = mv('ib_block_duration', 'p(95)');
    const blockP99       = mv('ib_block_duration', 'p(99)');

    const totalNormal    = mv('ib_normal_total', 'count');
    const normalErrCount = mv('ib_normal_errors', 'count');
    const normalErrPct   = totalNormal > 0 ? (normalErrCount / totalNormal * 100) : 0;
    const normalP50      = mv('ib_normal_duration', 'p(50)');
    const normalP95      = mv('ib_normal_duration', 'p(95)');
    const normalP99      = mv('ib_normal_duration', 'p(99)');

    const isPass    = bypass === 0 && blockRateVal >= 99.9 && normalErrPct < 1 && normalP95 < 300;
    const passColor = isPass ? '#22c55e' : '#ef4444';
    const passText  = isPass ? 'PASS' : 'FAIL';
    const testDate  = new Date().toISOString();

    // ── JSON ────────────────────────────────────────────────
    const jsonResult = {
        scenario:  'scenario7-internal-block',
        runTag:    RUN_TAG,
        timestamp: testDate,
        pass:      isPass,
        config: {
            scgBaseUrl:  SCG_BASE_URL,
            targetPath:  '/internal/**',
        },
        results: {
            internalTotal:  totalInternal,
            blocked:        blocked,
            bypass:         bypass,
            blockRate_pct:  parseFloat(blockRateVal.toFixed(2)),
            normalTotal:    totalNormal,
            normalErrors:   normalErrCount,
            normalError_pct: parseFloat(normalErrPct.toFixed(2)),
        },
        latency: {
            blockDuration: {
                p50: parseFloat(blockP50.toFixed(2)),
                p95: parseFloat(blockP95.toFixed(2)),
                p99: parseFloat(blockP99.toFixed(2)),
            },
            normalDuration: {
                p50: parseFloat(normalP50.toFixed(2)),
                p95: parseFloat(normalP95.toFixed(2)),
                p99: parseFloat(normalP99.toFixed(2)),
            },
        },
        thresholds: {
            'ib_internal_bypass':   { 'count == 0':    { ok: bypass === 0 } },
            'ib_block_rate':        { 'rate >= 0.999': { ok: blockRateVal >= 99.9 } },
            'ib_block_duration':    { 'p(95) < 50':    { ok: blockP95 < 50 } },
            'ib_normal_error_rate': { 'rate < 0.01':   { ok: normalErrPct < 1 } },
            'ib_normal_duration':   { 'p(95) < 300':   { ok: normalP95 < 300 } },
        },
    };

    // ── CSV ─────────────────────────────────────────────────
    const csvLines = [
        'metric,value',
        `run_tag,${RUN_TAG}`,
        `overall_pass,${isPass}`,
        `internal_total,${totalInternal}`,
        `internal_blocked,${blocked}`,
        `internal_bypass,${bypass}`,
        `block_rate_pct,${blockRateVal.toFixed(2)}`,
        `block_p50_ms,${blockP50.toFixed(2)}`,
        `block_p95_ms,${blockP95.toFixed(2)}`,
        `block_p99_ms,${blockP99.toFixed(2)}`,
        `normal_total,${totalNormal}`,
        `normal_error_pct,${normalErrPct.toFixed(2)}`,
        `normal_p50_ms,${normalP50.toFixed(2)}`,
        `normal_p95_ms,${normalP95.toFixed(2)}`,
        `normal_p99_ms,${normalP99.toFixed(2)}`,
    ].join('\n');

    // ── 진단 / 통과 노트 ────────────────────────────────────
    const diagnostics = [];
    const passNotes   = [];

    if (bypass > 0) {
        diagnostics.push({
            symptom: `CRITICAL: /internal/** bypass ${bypass}건 감지`,
            causes: [
                { text: 'InternalPathBlockFilter 미등록 또는 order 설정 오류',
                  check: 'scg-app GlobalFilter @Component 누락 여부, Order 값 확인 (HIGHEST_PRECEDENCE+2)' },
                { text: '/internal/** 경로가 라우팅 테이블에 등록되어 필터 우회',
                  check: 'application.yml routes에서 /internal/** predicates 제거 여부 확인' },
            ],
        });
    }
    if (blockRateVal < 99.9 && bypass === 0) {
        diagnostics.push({
            symptom: `차단율 ${blockRateVal.toFixed(1)}% — 기준 99.9% 미달`,
            causes: [
                { text: '일부 요청이 403 이외의 상태코드로 응답됨 (500/502 등)',
                  check: 'k6 bypass counter 로그에서 실제 응답 상태 코드 확인' },
            ],
        });
    }
    if (normalP95 >= 300) {
        diagnostics.push({
            symptom: `정상 트래픽 p95 ${normalP95.toFixed(0)}ms — 기준 300ms 초과`,
            causes: [
                { text: '차단 트래픽 처리 오버헤드가 정상 트래픽 레이턴시에 영향',
                  check: 'Jaeger에서 정상 트래픽 트레이스 확인' },
            ],
        });
    }
    if (diagnostics.length === 0) {
        passNotes.push(`bypass = 0 — 모든 /internal/** 요청이 InternalPathBlockFilter(order=+2)에 의해 완전히 차단됨`);
        passNotes.push(`차단율 ${blockRateVal.toFixed(2)}% ≥ 99.9%  |  차단 p95 ${blockP95.toFixed(1)}ms  |  정상 트래픽 p95 ${normalP95.toFixed(1)}ms`);
    }

    // ── HTML (scenario5 디자인 스타일) ───────────────────────
    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>SCG Internal Block 검증 결과</title>
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
  code { background: #f3f4f6; padding: 2px 6px; border-radius: 3px; font-size: 0.85rem; }
  pre { background: #1e293b; color: #e2e8f0; padding: 16px; border-radius: 8px; font-size: 0.82rem; line-height: 1.6; overflow-x: auto; }
</style>
</head>
<body>
<h1>SCG Internal Block 검증 결과 — 시나리오 7 <span class="badge">${passText}</span></h1>
<p style="color:#6b7280; font-size:0.85rem;">${testDate} | ${SCG_BASE_URL}</p>

<h2>목적</h2>
<p>InternalPathBlockFilter(order=HIGHEST_PRECEDENCE+2)가 부하 중에도 <code>/internal/**</code> 경로를 100% 차단하는지 검증한다.
정상 트래픽(10 req/s)과 내부 경로 공격(30 req/s)을 동시에 실행하여, bypass 건수가 단 한 건도 발생하지 않아야 한다.</p>

<div class="grid">
  <div class="card">
    <div style="color:#6b7280;font-size:0.85rem;">/internal 차단율</div>
    <div class="card-val" style="color:${blockRateVal >= 99.9 ? '#16a34a' : '#dc2626'}">${blockRateVal.toFixed(2)}%</div>
    <div style="font-size:0.8rem;">${totalInternal}건 중 ${blocked}건 차단 — 목표 ≥99.9%</div>
  </div>
  <div class="card">
    <div style="color:#6b7280;font-size:0.85rem;">정상 트래픽 에러율</div>
    <div class="card-val" style="color:${normalErrPct < 1 ? '#16a34a' : '#dc2626'}">${normalErrPct.toFixed(2)}%</div>
    <div style="font-size:0.8rem;">P95 ${normalP95.toFixed(1)}ms</div>
  </div>
</div>

<h2>설정</h2>
<table>
  <tr><th>항목</th><th>값</th></tr>
  <tr><td>차단 대상</td><td>/internal/** (AntPathMatcher)</td></tr>
  <tr><td>필터</td><td>InternalPathBlockFilter (GlobalFilter, order=HIGHEST_PRECEDENCE+2)</td></tr>
  <tr><td>internal 공격 트래픽</td><td>30 req/s (constant-arrival-rate, 2분)</td></tr>
  <tr><td>정상 트래픽</td><td>10 req/s (GET /api/v1/events/1, 2분)</td></tr>
</table>

<h2>InternalPathBlockFilter 차단 결과</h2>
<table>
  <tr><th>지표</th><th class="num">값</th><th>목표</th><th>판정</th></tr>
  <tr><td>총 /internal 요청</td><td class="num">${totalInternal}</td><td>—</td><td>—</td></tr>
  <tr><td>차단 (403) 수</td><td class="num">${blocked}</td><td>—</td><td>—</td></tr>
  <tr><td>Bypass (비-403) 수</td><td class="num">${bypass}</td><td>= 0</td><td class="${bypass === 0 ? 'pass' : 'fail'}">${bypass === 0 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>차단율</td><td class="num">${blockRateVal.toFixed(2)}%</td><td>≥ 99.9%</td><td class="${blockRateVal >= 99.9 ? 'pass' : 'fail'}">${blockRateVal >= 99.9 ? 'PASS' : 'FAIL'}</td></tr>
</table>

<h2>레이턴시</h2>
<table>
  <tr><th>구분</th><th class="num">P50</th><th class="num">P95</th><th class="num">P99</th></tr>
  <tr><td>차단 응답 (403)</td><td class="num">${blockP50.toFixed(1)}ms</td><td class="num">${blockP95.toFixed(1)}ms</td><td class="num">${blockP99.toFixed(1)}ms</td></tr>
  <tr><td>정상 트래픽 응답</td><td class="num">${normalP50.toFixed(1)}ms</td><td class="num">${normalP95.toFixed(1)}ms</td><td class="num">${normalP99.toFixed(1)}ms</td></tr>
</table>

<h2>정상 트래픽 영향도</h2>
<table>
  <tr><th>지표</th><th class="num">값</th><th>목표</th><th>판정</th></tr>
  <tr><td>총 정상 요청</td><td class="num">${totalNormal}</td><td>—</td><td>—</td></tr>
  <tr><td>에러율 (429 제외)</td><td class="num">${normalErrPct.toFixed(2)}%</td><td>&lt; 1%</td><td class="${normalErrPct < 1 ? 'pass' : 'fail'}">${normalErrPct < 1 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>p95 응답시간</td><td class="num">${normalP95.toFixed(1)}ms</td><td>&lt; 300ms</td><td class="${normalP95 < 300 ? 'pass' : 'fail'}">${normalP95 < 300 ? 'PASS' : 'FAIL'}</td></tr>
</table>

<h2>Threshold 판정</h2>
<table>
  <tr><th>Threshold</th><th>결과</th></tr>
  <tr><td>ib_internal_bypass: count == 0</td><td class="${bypass === 0 ? 'pass' : 'fail'}">${bypass === 0 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>ib_block_rate: rate &ge; 0.999</td><td class="${blockRateVal >= 99.9 ? 'pass' : 'fail'}">${blockRateVal >= 99.9 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>ib_block_duration: p(95) &lt; 50ms</td><td class="${blockP95 < 50 ? 'pass' : 'fail'}">${blockP95 < 50 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>ib_normal_error_rate: rate &lt; 0.01</td><td class="${normalErrPct < 1 ? 'pass' : 'fail'}">${normalErrPct < 1 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>ib_normal_duration: p(95) &lt; 300ms</td><td class="${normalP95 < 300 ? 'pass' : 'fail'}">${normalP95 < 300 ? 'PASS' : 'FAIL'}</td></tr>
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
  <strong>설계 포인트 — InternalPathBlockFilter(order=HIGHEST_PRECEDENCE+2):</strong><br/>
  JwtAuthenticationFilter(+4), RequestRateLimiter보다 먼저 실행되어, /internal/** 요청은 JWT 검증 없이 즉시 403을 반환합니다.
  Redis 호출도 발생하지 않아 공격 트래픽이 downstream 서비스나 Redis에 부하를 주지 않습니다.
</div>

<h2>검증 대상 경로</h2>
<table>
  <tr><th>경로</th><th>대상 서비스</th><th>설명</th></tr>
  <tr><td><code>/internal/v1/reservations/1</code></td><td>booking-app</td><td>예약 내부 조회</td></tr>
  <tr><td><code>/internal/v1/seats/1</code></td><td>concert-app</td><td>좌석 내부 조회</td></tr>
  <tr><td><code>/internal/v1/waiting-room/tokens/validate</code></td><td>waitingroom-app</td><td>토큰 검증</td></tr>
  <tr><td><code>/internal/v1/reservations/1/confirm</code></td><td>booking-app</td><td>예약 확정</td></tr>
  <tr><td><code>/internal/v1/seats/1/hold</code></td><td>concert-app</td><td>좌석 선점</td></tr>
</table>

<h2>SCG GlobalFilter 실행 순서</h2>
<pre>ORDER  필터                           동작
──────────────────────────────────────────────────────────────────
+1    GatewayAccessLogGlobalFilter   요청 로그 기록 (모든 요청 통과)
+2    InternalPathBlockFilter  ◀◀◀  /internal/** → 403 즉시 반환, 이후 필터 실행 안 함
+3    RequestSanitizeFilter          Auth-User-Id 등 외부 유입 헤더 제거
+4    JwtAuthenticationFilter        JWT 검증 (internal path는 여기 도달하지 않음)
+5    CorrelationIdGlobalFilter      Correlation-Id 주입
+6    RequestRateLimiter             Redis 기반 rate limiting (internal path 미도달)
+7    BulkheadFilter                 동시 요청 제한 (internal path 미도달)
+8    CircuitBreaker                 서킷 브레이커 (internal path 미도달)</pre>

<p class="meta">Generated by k6 scenario7-internal-block.js</p>
</body>
</html>`;

    const consoleMsg = [
        `\n[scenario7-internal-block] ${passText}`,
        `  bypass: ${bypass} (must be 0) | block rate: ${blockRateVal.toFixed(2)}% | block p95: ${blockP95.toFixed(1)}ms`,
        `  normal p95: ${normalP95.toFixed(1)}ms | normal errors: ${normalErrPct.toFixed(2)}%`,
        `  산출물: ${RESULT_DIR}/{html,json,csv}/scenario7-internal-block_${RUN_TAG}.*`,
        '',
    ].join('\n');

    return {
        stdout:                                                            consoleMsg,
        [`${RESULT_DIR}/json/scenario7-internal-block_${RUN_TAG}.json`]:  JSON.stringify(jsonResult, null, 2),
        [`${RESULT_DIR}/csv/scenario7-internal-block_${RUN_TAG}.csv`]:    csvLines,
        [`${RESULT_DIR}/html/scenario7-internal-block_${RUN_TAG}.html`]:  html,
    };
}
