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
    return `s7_${d.getFullYear()}${String(d.getMonth()+1).padStart(2,'0')}${String(d.getDate()).padStart(2,'0')}_`
         + `${String(d.getHours()).padStart(2,'0')}${String(d.getMinutes()).padStart(2,'0')}`;
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

    const blockPass    = bypass === 0 && blockRateVal >= 99.9 ? '✅ PASS' : '❌ FAIL';
    const normalPass   = normalErrPct < 1 && normalP95 < 300 ? '✅ PASS' : '❌ FAIL';
    const overallPass  = blockPass.startsWith('✅') && normalPass.startsWith('✅') ? '✅ PASS' : '❌ FAIL';

    // JSON
    const jsonResult = {
        scenario: 'scenario7-internal-block',
        runTag: RUN_TAG,
        timestamp: new Date().toISOString(),
        verdict: overallPass,
        internalBlock: {
            totalRequests: totalInternal,
            blocked:       blocked,
            bypass:        bypass,
            blockRate_pct: parseFloat(blockRateVal.toFixed(2)),
            block_p50_ms:  parseFloat(blockP50.toFixed(2)),
            block_p95_ms:  parseFloat(blockP95.toFixed(2)),
            block_p99_ms:  parseFloat(blockP99.toFixed(2)),
            verdict:       blockPass,
        },
        normalTraffic: {
            totalRequests:  totalNormal,
            errorCount:     normalErrCount,
            errorRate_pct:  parseFloat(normalErrPct.toFixed(2)),
            normal_p50_ms:  parseFloat(normalP50.toFixed(2)),
            normal_p95_ms:  parseFloat(normalP95.toFixed(2)),
            normal_p99_ms:  parseFloat(normalP99.toFixed(2)),
            verdict:        normalPass,
        },
    };

    // CSV
    const csvLines = [
        'metric,value',
        `run_tag,${RUN_TAG}`,
        `overall_verdict,${overallPass}`,
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

    // HTML
    const bypassWarning = bypass > 0
        ? `<div style="background:#c0392b;color:#fff;padding:16px;border-radius:6px;margin:16px 0;font-size:1.1em">
             ⛔ CRITICAL: ${bypass}건의 /internal/** 요청이 차단되지 않고 통과했습니다.
             InternalPathBlockFilter 설정 또는 SCG 라우팅을 즉시 확인하십시오.
           </div>`
        : `<div style="background:#27ae60;color:#fff;padding:12px;border-radius:6px;margin:16px 0">
             ✅ bypass = 0 — 모든 /internal/** 요청이 완전히 차단됨
           </div>`;

    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8">
  <title>SCG Scenario 7: Internal Block — ${RUN_TAG}</title>
  <style>
    body { font-family: 'Segoe UI', sans-serif; background: #f5f6fa; margin: 0; padding: 24px; color: #2d3436; }
    h1   { color: #2c3e50; border-bottom: 3px solid #3498db; padding-bottom: 8px; }
    h2   { color: #34495e; margin-top: 32px; }
    .verdict { font-size: 1.4em; font-weight: bold; padding: 14px 20px; border-radius: 8px; margin: 16px 0; display: inline-block; }
    .pass  { background: #27ae60; color: #fff; }
    .fail  { background: #c0392b; color: #fff; }
    table  { border-collapse: collapse; width: 100%; margin-top: 12px; }
    th, td { border: 1px solid #dfe6e9; padding: 10px 14px; text-align: left; }
    th     { background: #3498db; color: #fff; }
    tr:nth-child(even) { background: #f8f9fa; }
    .key   { font-weight: bold; }
    .block-section { background: #fff; padding: 20px; border-radius: 8px; margin-top: 16px; box-shadow: 0 1px 4px rgba(0,0,0,.08); }
    code   { background: #ecf0f1; padding: 2px 6px; border-radius: 3px; font-size: .9em; }
    .filter-chain { background: #2d3436; color: #dfe6e9; padding: 16px; border-radius: 6px; font-family: monospace; font-size: .85em; line-height: 1.6; }
  </style>
</head>
<body>
  <h1>SCG Scenario 7: /internal/** 차단 검증</h1>
  <p>실행 ID: <strong>${RUN_TAG}</strong> | 생성: ${new Date().toLocaleString('ko-KR')}</p>

  <div class="verdict ${overallPass.startsWith('✅') ? 'pass' : 'fail'}">${overallPass}</div>
  ${bypassWarning}

  <div class="block-section">
    <h2>InternalPathBlockFilter 차단 결과</h2>
    <table>
      <tr><th>항목</th><th>값</th><th>기준</th><th>판정</th></tr>
      <tr>
        <td class="key">총 /internal 요청 수</td>
        <td>${totalInternal}</td><td>—</td><td>—</td>
      </tr>
      <tr>
        <td class="key">차단 (403) 수</td>
        <td>${blocked}</td><td>—</td><td>—</td>
      </tr>
      <tr style="font-weight:bold;${bypass > 0 ? 'background:#ffeaa7' : ''}">
        <td class="key">Bypass (비-403) 수</td>
        <td>${bypass}</td>
        <td>= 0 (단 한 건도 허용 불가)</td>
        <td>${bypass === 0 ? '✅' : '❌ FAIL'}</td>
      </tr>
      <tr>
        <td class="key">차단율</td>
        <td>${blockRateVal.toFixed(2)}%</td>
        <td>≥ 99.9%</td>
        <td>${blockRateVal >= 99.9 ? '✅' : '❌'}</td>
      </tr>
      <tr><td class="key">차단 응답시간 p50</td><td>${blockP50.toFixed(1)} ms</td><td>—</td><td>—</td></tr>
      <tr><td class="key">차단 응답시간 p95</td><td>${blockP95.toFixed(1)} ms</td><td>&lt; 50ms</td><td>${blockP95 < 50 ? '✅' : '⚠️'}</td></tr>
      <tr><td class="key">차단 응답시간 p99</td><td>${blockP99.toFixed(1)} ms</td><td>—</td><td>—</td></tr>
    </table>
    <p style="margin-top:12px;font-size:.9em;color:#636e72">
      💡 차단 응답시간이 짧을수록 InternalPathBlockFilter가 필터 체인 초기에 빠르게 종료됨을 의미합니다.
      JwtAuthenticationFilter(+4), Rate-limiter, Bulkhead 모두 실행되지 않으므로 &lt;5ms가 기대값입니다.
    </p>
  </div>

  <div class="block-section">
    <h2>정상 트래픽 영향도</h2>
    <table>
      <tr><th>항목</th><th>값</th><th>기준</th><th>판정</th></tr>
      <tr><td class="key">총 정상 요청 수</td><td>${totalNormal}</td><td>—</td><td>—</td></tr>
      <tr>
        <td class="key">에러율 (429 제외)</td>
        <td>${normalErrPct.toFixed(2)}%</td>
        <td>&lt; 1%</td>
        <td>${normalErrPct < 1 ? '✅' : '❌'}</td>
      </tr>
      <tr><td class="key">p50 응답시간</td><td>${normalP50.toFixed(1)} ms</td><td>—</td><td>—</td></tr>
      <tr>
        <td class="key">p95 응답시간</td>
        <td>${normalP95.toFixed(1)} ms</td>
        <td>&lt; 300ms</td>
        <td>${normalP95 < 300 ? '✅' : '❌'}</td>
      </tr>
      <tr><td class="key">p99 응답시간</td><td>${normalP99.toFixed(1)} ms</td><td>—</td><td>—</td></tr>
    </table>
  </div>

  <div class="block-section">
    <h2>검증 대상 경로 목록</h2>
    <table>
      <tr><th>경로</th><th>대상 서비스</th><th>설명</th></tr>
      <tr><td><code>/internal/v1/reservations/1</code></td><td>booking-app</td><td>예약 내부 조회</td></tr>
      <tr><td><code>/internal/v1/seats/1</code></td><td>concert-app</td><td>좌석 내부 조회</td></tr>
      <tr><td><code>/internal/v1/waiting-room/tokens/validate</code></td><td>waitingroom-app</td><td>토큰 검증</td></tr>
      <tr><td><code>/internal/v1/reservations/1/confirm</code></td><td>booking-app</td><td>예약 확정</td></tr>
      <tr><td><code>/internal/v1/seats/1/hold</code></td><td>concert-app</td><td>좌석 선점</td></tr>
    </table>
  </div>

  <div class="block-section">
    <h2>SCG GlobalFilter 실행 순서 (차단 근거)</h2>
    <div class="filter-chain">
ORDER  필터                           동작
──────────────────────────────────────────────────────────────────
+1    GatewayAccessLogGlobalFilter   요청 로그 기록 (모든 요청 통과)
+2    InternalPathBlockFilter  ◀◀◀  /internal/** → 403 즉시 반환, 이후 필터 실행 안 함
+3    RequestSanitizeFilter          X-Auth-User-Id 외부 유입 헤더 제거
+4    JwtAuthenticationFilter        JWT 검증 (internal path는 여기 도달하지 않음)
+5    CorrelationIdGlobalFilter       X-Correlation-Id 주입
+6    RequestRateLimiter             Redis 기반 rate limiting (internal path 미도달)
+7    BulkheadFilter                 동시 요청 제한 (internal path 미도달)
+8    CircuitBreaker                 서킷 브레이커 (internal path 미도달)
+9    SCG 내장 RouteToRequestUrl     실제 라우팅 (internal path 미도달)
    </div>
    <p style="font-size:.9em;color:#636e72;margin-top:8px">
      InternalPathBlockFilter가 HIGHEST_PRECEDENCE+2에 위치하므로 JWT가 없어도 403이 반환됩니다.
      bypass 결과가 401(JWT 없음)이라면 필터 order 설정을 확인해야 합니다.
    </p>
  </div>

  <div class="block-section">
    <h2>면접 설명 포인트</h2>
    <ul>
      <li>내부 API는 SCG 라우팅 테이블에 등록되지 않아 외부에서 라우팅 자체가 안 됩니다.</li>
      <li>설령 /internal/** 경로가 SCG에 도달하더라도 <code>InternalPathBlockFilter</code>가 GlobalFilter로 등록되어 모든 요청에 적용됩니다.</li>
      <li>차단 필터가 필터 체인 초기(order=+2)에 위치해 JWT 검증, Redis rate-limit, Bulkhead 자원을 소모하지 않습니다.</li>
      <li>AntPathMatcher를 사용하므로 <code>/internal/v1/seats/1/hold</code>처럼 깊은 경로도 모두 차단됩니다.</li>
      <li>이 테스트가 bypass=0을 확인함으로써 "코드 리뷰 통과" 수준이 아닌 "부하 중 실제 동작 검증" 수준의 보안 보증을 제공합니다.</li>
    </ul>
  </div>
</body>
</html>`;

    return {
        [`${RESULT_DIR}/${RUN_TAG}_scenario7.json`]: JSON.stringify(jsonResult, null, 2),
        [`${RESULT_DIR}/${RUN_TAG}_scenario7.csv`]:  csvLines,
        [`${RESULT_DIR}/${RUN_TAG}_scenario7.html`]: html,
        stdout: `\n=== Scenario 7: Internal Block ===\n`
              + `Verdict       : ${overallPass}\n`
              + `bypass count  : ${bypass}   (must be 0)\n`
              + `block rate    : ${blockRateVal.toFixed(2)}%  (must be ≥ 99.9%)\n`
              + `block p95     : ${blockP95.toFixed(1)} ms\n`
              + `normal p95    : ${normalP95.toFixed(1)} ms  (< 300ms)\n`
              + `normal errors : ${normalErrPct.toFixed(2)}%  (< 1%)\n`
              + `Reports → ${RESULT_DIR}/${RUN_TAG}_scenario7.{json,csv,html}\n`,
    };
}
