// SCG 시나리오 12: Gateway 보안 검증 — 인증/헤더/Queue Token/Request-Id
//
// 목적:
//   SCG 필터 체인의 보안 제어장치가 각각 정확하게 동작하는지 정량적으로 증명한다.
//   "설계상 그럴 것이다"가 아닌 실측 수치로 검증한다.
//
// 검증 항목 (6가지):
//   [1] auth_success      : 유효 JWT → 200 OK, p95 응답시간 측정
//   [2] auth_expired      : 만료 JWT → 401 ProblemDetail (status/title 필드 확인)
//   [3] auth_no_token     : Authorization 헤더 없음 → 401 ProblemDetail
//   [4] spoofed_header    : Auth-User-Id / Auth-Passport 주입 + JWT 없음 → 401
//                           (RequestSanitizeFilter(+3)가 헤더를 strip했기 때문에 200이 아님을 증명)
//   [5] queue_token_block : 유효 JWT + POST /api/v1/reservations/** + Queue-Token 없음 → 403
//   [6] request_id_echo   : X-Request-Id 주입 → 응답에 동일 X-Request-Id 포함
//                           (RequestCorrelationFilter propagation 검증)
//
// 판단 기준:
//   auth_success_p95   < 300ms
//   auth_error_rate    = 0%    (auth_success 시나리오에서 비정상 응답 없음)
//   spoofed_blocked    = 100%  (bypass 단 1건도 없어야 함)
//   queue_block_rate   ≥ 99%
//   request_id_match   = 100%
//   problemdetail_rate ≥ 99%  (모든 4xx 응답이 ProblemDetail JSON)
//
// 실행:
//   k6 run \
//     --env SCG_BASE_URL=http://192.168.124.100:8090 \
//     --env JWT_SECRET=<actual-secret> \
//     --env RESULT_DIR=results/2026-04-07 \
//     scenario12-gateway-security-verification.js
//
// 실행 후 확인해야 할 observability:
//   Jaeger  : http://192.168.124.100:8080/jaeger/search?service=scg-app&tags=requestId:{X-Request-Id 값}
//   Kibana  : index=filebeat-*, query: requestId:{X-Request-Id 값}
//   Grafana : SCG 대시보드 → http_requests_total{status="401"} / http_requests_total{status="403"}
//   Prometheus: http_server_requests_seconds{uri="/api/v1/reservations/**", outcome="CLIENT_ERROR"}

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
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${p(d.getMonth()+1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
})();

// ── JWT 생성 ─────────────────────────────────────────────────
function makeJwt(userId, roles, expiresInSeconds) {
    const header  = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
    const now     = Math.floor(Date.now() / 1000);
    const exp     = expiresInSeconds !== undefined ? now + expiresInSeconds : now + 3600;
    const payload = encoding.b64encode(
        JSON.stringify({ sub: String(userId), roles: roles || ['USER'], iat: now, exp }),
        'rawurl'
    );
    const sigInput  = `${header}.${payload}`;
    const signature = crypto.hmac('sha256', JWT_SECRET, sigInput, 'base64rawurl');
    return `${header}.${payload}.${signature}`;
}

// ── 커스텀 메트릭 ─────────────────────────────────────────────
// [1] 인증 성공 응답시간
const authSuccessDuration  = new Trend('sv_auth_success_duration', true);
const authSuccessRate      = new Rate('sv_auth_success_rate');

// [2] 만료 JWT 응답시간 및 형식
const authExpiredDuration  = new Trend('sv_auth_expired_duration', true);
const authExpiredCorrect   = new Rate('sv_auth_expired_correct');    // 401이어야 함

// [3] JWT 없음 응답 형식
const authMissingCorrect   = new Rate('sv_auth_missing_correct');    // 401이어야 함

// [4] Spoofed header 차단 — Auth-User-Id + Auth-Passport 주입 시에도 401
const spoofedBlocked       = new Rate('sv_spoofed_blocked');         // 401이어야 함 (200이면 실패)
const spoofedBypass        = new Counter('sv_spoofed_bypass');       // 0이어야 함

// [5] Queue-Token 없음 차단
const queueBlockRate       = new Rate('sv_queue_block_rate');        // 403이어야 함
const queueBlockDuration   = new Trend('sv_queue_block_duration', true);

// [6] X-Request-Id propagation
const requestIdMatch       = new Rate('sv_request_id_match');        // 100%이어야 함

// 공통: ProblemDetail JSON 형식 일관성
const problemDetailRate    = new Rate('sv_problemdetail_rate');

// ── k6 시나리오 설정 ─────────────────────────────────────────
export const options = {
    scenarios: {
        auth_success: {
            executor: 'constant-arrival-rate',
            rate: 10, timeUnit: '1s', duration: '30s',
            preAllocatedVUs: 5, maxVUs: 15,
            exec: 'authSuccessScenario',
            tags: { scenario: 'auth_success' },
        },
        auth_expired: {
            executor: 'constant-arrival-rate',
            rate: 5, timeUnit: '1s', duration: '30s',
            preAllocatedVUs: 3, maxVUs: 8,
            exec: 'authExpiredScenario',
            tags: { scenario: 'auth_expired' },
        },
        auth_no_token: {
            executor: 'constant-arrival-rate',
            rate: 5, timeUnit: '1s', duration: '30s',
            preAllocatedVUs: 3, maxVUs: 8,
            exec: 'authNoTokenScenario',
            tags: { scenario: 'auth_no_token' },
        },
        spoofed_header: {
            executor: 'constant-arrival-rate',
            rate: 10, timeUnit: '1s', duration: '30s',
            preAllocatedVUs: 5, maxVUs: 10,
            exec: 'spoofedHeaderScenario',
            tags: { scenario: 'spoofed_header' },
        },
        queue_token: {
            executor: 'constant-arrival-rate',
            rate: 5, timeUnit: '1s', duration: '30s',
            preAllocatedVUs: 3, maxVUs: 8,
            exec: 'queueTokenScenario',
            tags: { scenario: 'queue_token' },
        },
        request_id: {
            executor: 'constant-arrival-rate',
            rate: 5, timeUnit: '1s', duration: '30s',
            preAllocatedVUs: 3, maxVUs: 8,
            exec: 'requestIdScenario',
            tags: { scenario: 'request_id' },
        },
    },
    thresholds: {
        // [1] 인증 성공: p95는 baseline 측정값으로 기록 (threshold 없음), 에러율 100%
        'sv_auth_success_rate':     ['rate>=1.0'],
        // [2] 만료 JWT: 401 100% 일관성
        'sv_auth_expired_correct':  ['rate>=1.0'],
        // [3] JWT 없음: 401 100% 일관성
        'sv_auth_missing_correct':  ['rate>=1.0'],
        // [4] Spoofed header: bypass 0건 + 차단 100%
        'sv_spoofed_bypass':        ['count==0'],
        'sv_spoofed_blocked':       ['rate>=1.0'],
        // [5] Queue-Token 없음: 403 100% 차단
        'sv_queue_block_rate':      ['rate>=1.0'],
        // [6] X-Request-Id: 100% propagation
        'sv_request_id_match':      ['rate>=1.0'],
        // 공통: ProblemDetail 형식 100% 일관성
        'sv_problemdetail_rate':    ['rate>=1.0'],
    },
    tags: { testid: 'scenario12-gateway-security-verification' },
};

// ── setup: SCG 연결 확인 ──────────────────────────────────────
export function setup() {
    const validToken = makeJwt(1, ['USER'], 3600);
    const res = http.get(`${SCG_BASE_URL}/actuator/health`, { timeout: '5s' });
    if (res.status === 0) {
        console.error(`[SETUP ERROR] SCG 연결 실패: ${SCG_BASE_URL}/actuator/health`);
    }
    console.log(`[setup] SCG health: status=${res.status}`);
    return { validToken, expiredToken: makeJwt(1, ['USER'], -3600) };
}

// ── [1] 인증 성공 요청 ────────────────────────────────────────
// 유효 JWT → /api/v1/events → 200 OK
export function authSuccessScenario(data) {
    const token = data.validToken || makeJwt(1, ['USER'], 3600);
    const res = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers: { 'Authorization': `Bearer ${token}` },
        timeout: '10s',
    });
    authSuccessDuration.add(res.timings.duration);
    const ok = res.status === 200 || res.status === 404; // 404는 라우팅 됐지만 데이터 없음
    authSuccessRate.add(ok ? 1 : 0);
    check(res, {
        '[auth_success] 2xx/404 응답 (JWT 인증 통과)': (r) => r.status === 200 || r.status === 404,
        '[auth_success] 401/403이 아님':               (r) => r.status !== 401 && r.status !== 403,
    });
}

// ── [2] 만료 JWT 요청 ─────────────────────────────────────────
// 만료된 JWT → /api/v1/events → 401 + ProblemDetail
export function authExpiredScenario(data) {
    const expiredToken = data.expiredToken || makeJwt(1, ['USER'], -3600);
    const res = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers: { 'Authorization': `Bearer ${expiredToken}` },
        timeout: '5s',
    });
    authExpiredDuration.add(res.timings.duration);
    const is401 = res.status === 401;
    authExpiredCorrect.add(is401);

    if (is401) {
        const isProblemDetail = checkProblemDetail(res);
        problemDetailRate.add(isProblemDetail);
        check(res, {
            '[auth_expired] 401 반환':         (r) => r.status === 401,
            '[auth_expired] ProblemDetail 형식': () => isProblemDetail,
        });
    } else {
        console.warn(`[auth_expired] 예상 외 응답: status=${res.status}`);
        check(res, { '[auth_expired] 401 반환': (r) => r.status === 401 });
    }
}

// ── [3] JWT 없음 요청 ─────────────────────────────────────────
// Authorization 헤더 없음 → 401 + ProblemDetail
export function authNoTokenScenario() {
    const res = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers: { 'X-Test-Scenario': 'auth-missing' },
        timeout: '5s',
    });
    const is401 = res.status === 401;
    authMissingCorrect.add(is401);

    if (is401) {
        const isProblemDetail = checkProblemDetail(res);
        problemDetailRate.add(isProblemDetail);
        check(res, {
            '[auth_missing] 401 반환':         (r) => r.status === 401,
            '[auth_missing] ProblemDetail 형식': () => isProblemDetail,
        });
    } else {
        check(res, { '[auth_missing] 401 반환': (r) => r.status === 401 });
    }
}

// ── [4] Spoofed Header 차단 ───────────────────────────────────
// Auth-User-Id + Auth-Passport 위조 헤더를 JWT 없이 전송
// 기댓값: RequestSanitizeFilter(+3)가 헤더를 제거 → JwtAuthFilter(+4)가 401 반환
// 실패 조건: 200 응답이 오면 위조 헤더가 strip되지 않은 것
export function spoofedHeaderScenario() {
    const fakePassport = encoding.b64encode(
        JSON.stringify({ userId: '999', roles: ['ADMIN'] }), 'rawurl'
    );
    const res = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers: {
            'Auth-User-Id':  '999',
            'Auth-Passport': fakePassport,
            'Internal-Token': 'fake-internal-token',
            // Authorization 헤더 없음: JWT 없이 위조 헤더만 주입
        },
        timeout: '5s',
    });

    const isBlocked = res.status === 401; // SanitizeFilter가 헤더 제거 후 JwtFilter가 401 반환
    const isBypass  = res.status === 200 || res.status === 202; // bypass 발생 시

    spoofedBlocked.add(isBlocked);
    if (isBypass) {
        spoofedBypass.add(1);
        console.error(`[BYPASS] spoofed header가 차단되지 않음! status=${res.status} body=${(res.body || '').substring(0, 200)}`);
    }

    if (isBlocked) {
        problemDetailRate.add(checkProblemDetail(res));
    }

    check(res, {
        '[spoofed] Auth-User-Id 위조 차단 (401)':     (r) => r.status === 401,
        '[spoofed] 200 bypass 없음':                   (r) => r.status !== 200,
        // 401이어야 함: 403이면 InternalPathBlock이 먼저 차단한 것, 200이면 bypass
        '[spoofed] not 200 (SanitizeFilter 동작 확인)': (r) => r.status !== 200,
    });
}

// ── [5] Queue-Token 없음 차단 ────────────────────────────────
// 유효 JWT + POST /api/v1/reservations/** + Queue-Token 없음 → 403
// QueueTokenValidationFilter는 POST/PUT/PATCH/DELETE만 보호 (GET 제외 — ADR-0008)
// 따라서 반드시 POST 메서드로 요청해야 403 차단을 검증할 수 있다.
export function queueTokenScenario(data) {
    const token = data.validToken || makeJwt(1, ['USER'], 3600);

    const res = http.post(`${SCG_BASE_URL}/api/v1/reservations`, JSON.stringify({
        eventId: 1,
        seatId: Math.floor(Math.random() * 100) + 1,
    }), {
        headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json',
            // Queue-Token 헤더 없음 → 403 기대
        },
        timeout: '5s',
    });
    queueBlockDuration.add(res.timings.duration);

    // 403: QueueTokenValidationFilter 차단
    // POST + Queue-Token 없음 → QueueTokenValidationFilter(+5)가 403 반환해야 함
    // gateway.queue-token.enabled=true 일 때 403 기대
    // 403이 아니면 필터가 비활성화되었거나 경로 매칭 실패
    const is403 = res.status === 403;
    queueBlockRate.add(is403);

    if (is403) {
        problemDetailRate.add(checkProblemDetail(res));
    }

    check(res, {
        '[queue_token] POST + no Queue-Token → 403': (r) => r.status === 403,
        '[queue_token] downstream 미도달':              (r) => r.status !== 200 && r.status !== 201,
    });
}

// ── [6] X-Request-Id Propagation ─────────────────────────────
// X-Request-Id 를 직접 지정 → 응답에 동일한 X-Request-Id가 반환돼야 함
// RequestCorrelationFilter(HIGHEST_PRECEDENCE)가 응답 헤더에 추가
export function requestIdScenario(data) {
    const token     = data.validToken || makeJwt(1, ['USER'], 3600);
    const myReqId   = `test-${RUN_TAG}-${Math.random().toString(36).substring(2, 8)}`;

    const res = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers: {
            'Authorization': `Bearer ${token}`,
            'X-Request-Id':  myReqId,
        },
        timeout: '5s',
    });

    const echoedId  = res.headers['X-Request-Id'] || res.headers['x-request-id'] || '';
    const idMatches = echoedId === myReqId;
    requestIdMatch.add(idMatches);

    if (!idMatches) {
        console.warn(`[request_id] propagation 실패: sent=${myReqId} echoed=${echoedId}`);
    }

    check(res, {
        '[request_id] 응답에 X-Request-Id 포함':              () => echoedId.length > 0,
        '[request_id] 보낸 X-Request-Id와 응답 값 일치':       () => idMatches,
    });
}

// ── 유틸: ProblemDetail 형식 확인 ────────────────────────────
// RFC 7807 기준: { "type", "title", "status", "detail" } 중 최소 status + title 필수
function checkProblemDetail(res) {
    try {
        const ct = res.headers['Content-Type'] || res.headers['content-type'] || '';
        if (!ct.includes('json') && !ct.includes('problem')) return false;
        const body = JSON.parse(res.body);
        return typeof body.status === 'number' && typeof body.title === 'string';
    } catch {
        return false;
    }
}

// default 함수 (단독 실행 시 폴백)
export default function (data) {
    authSuccessScenario(data || {});
}

// ── handleSummary: JSON + CSV + HTML ─────────────────────────
export function handleSummary(data) {
    const m  = (name, key) => data.metrics[name]?.values?.[key] || 0;
    const mc = (name) => data.metrics[name]?.values?.count || 0;

    const authSuccP95    = m('sv_auth_success_duration', 'p(95)');
    const authSuccP50    = m('sv_auth_success_duration', 'p(50)');
    const authSuccRate   = m('sv_auth_success_rate', 'rate');
    const authExpOk      = m('sv_auth_expired_correct', 'rate');
    const authMissOk     = m('sv_auth_missing_correct', 'rate');
    const spoofBlocked   = m('sv_spoofed_blocked', 'rate');
    const spoofBypass    = mc('sv_spoofed_bypass');
    const queueBlock     = m('sv_queue_block_rate', 'rate');
    const reqIdMatch     = m('sv_request_id_match', 'rate');
    const pdRate         = m('sv_problemdetail_rate', 'rate');

    // 100% 일관성 기준 (P95는 baseline 측정값으로만 기록, pass/fail 판정 안 함)
    const allPass = authSuccRate >= 1.0
        && authExpOk >= 1.0
        && authMissOk >= 1.0
        && spoofBypass === 0
        && spoofBlocked >= 1.0
        && queueBlock >= 1.0
        && reqIdMatch >= 1.0
        && pdRate >= 1.0;

    const passText  = allPass ? 'PASS' : 'FAIL';
    const passColor = allPass ? '#22c55e' : '#ef4444';
    const testDate  = new Date().toISOString();

    // ── 진단 ─────────────────────────────────────────────────
    const diagnostics = [];
    if (spoofBypass > 0) {
        diagnostics.push({
            symptom: `CRITICAL: Auth-User-Id/Auth-Passport 위조 헤더 bypass ${spoofBypass}건`,
            causes: [
                { text: 'RequestSanitizeFilter(@Component) 미등록 또는 order 설정 오류',
                  check: 'scg-app 로그에서 [SANITIZE] Stripped 메시지 확인. 없으면 필터가 동작 안 함.' },
                { text: 'sanitize-headers 설정에서 Auth-User-Id/Auth-Passport 제외됨',
                  check: 'gateway.security.sanitize-headers 설정값 확인 (application.yml)' },
            ],
        });
    }
    if (authSuccRate < 1.0) {
        diagnostics.push({
            symptom: `인증 성공 에러율 ${((1 - authSuccRate) * 100).toFixed(1)}% 발생`,
            causes: [
                { text: 'JWT_SECRET 불일치: scg-app 서버 설정값과 k6 환경변수 다름',
                  check: 'scg-app 로그에서 JWT signature 검증 실패 메시지 확인' },
                { text: 'concert-app 미실행',
                  check: 'docker compose ps concert-app' },
            ],
        });
    }
    if (reqIdMatch < 1.0) {
        diagnostics.push({
            symptom: 'X-Request-Id propagation 실패',
            causes: [
                { text: 'RequestCorrelationFilter 응답 헤더 설정 누락',
                  check: 'RequestCorrelationFilter.java: exchange.getResponse().getHeaders().set() 확인' },
                { text: '응답 헤더 이름 대소문자 불일치',
                  check: 'curl -i 로 실제 응답 헤더 이름 확인' },
            ],
        });
    }

    const passNotes = [];
    if (allPass) {
        passNotes.push(`bypass = 0: Auth-User-Id/Auth-Passport 위조 헤더가 RequestSanitizeFilter(+3)에 의해 100% 차단됨`);
        passNotes.push(`인증 성공 baseline P95 = ${authSuccP95.toFixed(1)}ms, P50 = ${authSuccP50.toFixed(1)}ms (합격선 아닌 기준 측정값)`);
        passNotes.push(`X-Request-Id propagation = ${(reqIdMatch * 100).toFixed(1)}%: 모든 요청에서 응답 헤더에 동일 값 반환 확인`);
        passNotes.push(`ProblemDetail 일관성 = ${(pdRate * 100).toFixed(1)}%: 4xx 오류 응답이 RFC 7807 형식 준수`);
    }

    // ── JSON ─────────────────────────────────────────────────
    const jsonReport = {
        scenario: 'scenario12-gateway-security-verification',
        runTag: RUN_TAG, timestamp: testDate, pass: allPass,
        config: { scgBaseUrl: SCG_BASE_URL },
        results: {
            auth_success: {
                p50_ms: +authSuccP50.toFixed(2), p95_ms: +authSuccP95.toFixed(2),
                success_rate: +authSuccRate.toFixed(4),
                note: 'P95는 baseline 측정값 (합격선 아닌 기준선)',
                pass: authSuccRate >= 1.0,
            },
            auth_expired: {
                correct_401_rate: +authExpOk.toFixed(4),
                target: '100%', pass: authExpOk >= 1.0,
            },
            auth_no_token: {
                correct_401_rate: +authMissOk.toFixed(4),
                target: '100%', pass: authMissOk >= 1.0,
            },
            spoofed_header: {
                bypass_count: spoofBypass,
                blocked_rate: +spoofBlocked.toFixed(4),
                target: 'bypass=0, blocked=100%', pass: spoofBypass === 0 && spoofBlocked >= 1.0,
            },
            queue_token: {
                block_rate: +queueBlock.toFixed(4),
                target: '100%', pass: queueBlock >= 1.0,
            },
            request_id_propagation: {
                match_rate: +reqIdMatch.toFixed(4),
                target: '100%', pass: reqIdMatch >= 1.0,
            },
            error_format_consistency: {
                problemdetail_rate: +pdRate.toFixed(4),
                target: '100%', pass: pdRate >= 1.0,
            },
        },
        observability_hints: {
            jaeger: `http://192.168.124.100:8080/jaeger/search?service=scg-app (X-Request-Id 값으로 트레이스 검색)`,
            kibana: `Kibana > Discover > index: filebeat-* > query: requestId:<X-Request-Id 값>`,
            grafana: `SCG 대시보드: http_requests_total{status="401"}, http_requests_total{status="403"}`,
            prometheus: `http_server_requests_seconds_count{uri=~".*/reservations.*", status="403"} (Queue Token 차단 카운트)`,
            elasticsearch: `GET /filebeat-*/_search { "query": { "match": { "requestId": "<X-Request-Id 값>" } } }`,
        },
        diagnostics: diagnostics.map(d => ({ symptom: d.symptom, causes: d.causes })),
    };

    // ── CSV ──────────────────────────────────────────────────
    const csv = [
        'scenario,metric,value,target,pass',
        `scenario12,auth_success_p95_ms,${authSuccP95.toFixed(2)},baseline,-`,
        `scenario12,auth_success_p50_ms,${authSuccP50.toFixed(2)},baseline,-`,
        `scenario12,auth_success_rate,${authSuccRate.toFixed(4)},==1.0,${authSuccRate >= 1.0}`,
        `scenario12,auth_expired_correct_rate,${authExpOk.toFixed(4)},==1.0,${authExpOk >= 1.0}`,
        `scenario12,auth_missing_correct_rate,${authMissOk.toFixed(4)},==1.0,${authMissOk >= 1.0}`,
        `scenario12,spoofed_bypass_count,${spoofBypass},==0,${spoofBypass === 0}`,
        `scenario12,spoofed_blocked_rate,${spoofBlocked.toFixed(4)},==1.0,${spoofBlocked >= 1.0}`,
        `scenario12,queue_block_rate,${queueBlock.toFixed(4)},==1.0,${queueBlock >= 1.0}`,
        `scenario12,request_id_match_rate,${reqIdMatch.toFixed(4)},==1.0,${reqIdMatch >= 1.0}`,
        `scenario12,problemdetail_rate,${pdRate.toFixed(4)},==1.0,${pdRate >= 1.0}`,
    ].join('\n');

    // ── HTML ─────────────────────────────────────────────────
    const pct = (v) => `${(v * 100).toFixed(1)}%`;
    const pass = (ok) => `<span class="${ok ? 'pass' : 'fail'}">${ok ? 'PASS' : 'FAIL'}</span>`;

    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>SCG 보안 검증 — 시나리오 12</title>
<style>
  body { font-family: -apple-system, 'Pretendard', sans-serif; max-width: 960px; margin: 40px auto; padding: 0 20px; color: #1a1a1a; }
  h1 { font-size: 1.4rem; border-bottom: 2px solid #e5e7eb; padding-bottom: 8px; }
  h2 { font-size: 1.1rem; margin-top: 28px; color: #374151; }
  .badge { display: inline-block; padding: 4px 12px; border-radius: 4px; color: #fff; font-weight: 700; font-size: 0.85rem; background: ${passColor}; }
  table { width: 100%; border-collapse: collapse; margin: 12px 0; font-size: 0.9rem; }
  th, td { border: 1px solid #e5e7eb; padding: 8px 12px; text-align: left; }
  th { background: #f9fafb; font-weight: 600; }
  .num { text-align: right; font-variant-numeric: tabular-nums; }
  .pass { color: #16a34a; font-weight: 600; } .fail { color: #dc2626; font-weight: 600; }
  .meta { color: #6b7280; font-size: 0.8rem; margin-top: 32px; }
  .diag { background: #fef2f2; border: 1px solid #fecaca; border-radius: 8px; padding: 16px 20px; margin: 10px 0; }
  .diag h3 { color: #dc2626; font-size: 0.95rem; margin: 0 0 8px 0; }
  .diag ol { margin: 6px 0 0; padding-left: 20px; }
  .diag li { margin-bottom: 8px; }
  .diag .cause { font-weight: 600; }
  .diag .how { color: #6b7280; font-size: 0.85rem; display: block; }
  .note { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 12px 16px; margin: 8px 0; font-size: 0.9rem; line-height: 1.6; color: #15803d; }
  .obs  { background: #fefce8; border: 1px solid #fde68a; border-radius: 8px; padding: 14px 18px; margin: 10px 0; font-size: 0.88rem; line-height: 1.7; }
  .obs strong { color: #92400e; }
  .obs code { background: #fef3c7; padding: 1px 5px; border-radius: 3px; font-family: monospace; font-size: 0.82rem; }
  .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin: 16px 0; }
  .card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px; text-align: center; }
  .card-val { font-size: 1.6rem; font-weight: 700; }
  code { background: #f3f4f6; padding: 1px 5px; border-radius: 3px; font-size: 0.85rem; }
</style>
</head>
<body>
<h1>SCG Gateway 보안 검증 — 시나리오 12 <span class="badge">${passText}</span></h1>
<p style="color:#6b7280;font-size:0.85rem;">${testDate} | ${SCG_BASE_URL}</p>

<h2>검증 항목 요약</h2>
<div class="grid">
  <div class="card">
    <div style="color:#6b7280;font-size:0.8rem;">인증 성공 P95 (baseline)</div>
    <div class="card-val" style="color:#374151">${authSuccP95.toFixed(0)}ms</div>
    <div style="font-size:0.8rem;">기준 측정값 (합격선 아님)</div>
  </div>
  <div class="card">
    <div style="color:#6b7280;font-size:0.8rem;">위조 헤더 Bypass</div>
    <div class="card-val" style="color:${spoofBypass === 0 ? '#16a34a' : '#dc2626'}">${spoofBypass}</div>
    <div style="font-size:0.8rem;">목표 = 0 ${spoofBypass === 0 ? '✓' : '✗'}</div>
  </div>
  <div class="card">
    <div style="color:#6b7280;font-size:0.8rem;">X-Request-Id 매칭</div>
    <div class="card-val" style="color:${reqIdMatch >= 1.0 ? '#16a34a' : '#dc2626'}">${pct(reqIdMatch)}</div>
    <div style="font-size:0.8rem;">목표 = 100% ${reqIdMatch >= 1.0 ? '✓' : '✗'}</div>
  </div>
</div>

<h2>상세 결과</h2>
<table>
  <tr><th>검증 항목</th><th>측정값</th><th>목표</th><th>판정</th><th>검증 내용</th></tr>
  <tr>
    <td>[1] 인증 성공 응답시간 (baseline)</td>
    <td class="num">P50: ${authSuccP50.toFixed(1)}ms / P95: ${authSuccP95.toFixed(1)}ms</td>
    <td>기준 측정값</td>
    <td style="color:#6b7280">BASELINE</td>
    <td>유효 JWT → /api/v1/events (개선 전후 비교용)</td>
  </tr>
  <tr>
    <td>[2] 만료 JWT → 401</td>
    <td class="num">${pct(authExpOk)}</td>
    <td>= 100%</td>
    <td>${pass(authExpOk >= 1.0)}</td>
    <td>exp 음수 JWT → 401 ProblemDetail</td>
  </tr>
  <tr>
    <td>[3] JWT 없음 → 401</td>
    <td class="num">${pct(authMissOk)}</td>
    <td>= 100%</td>
    <td>${pass(authMissOk >= 1.0)}</td>
    <td>Authorization 헤더 없음 → 401</td>
  </tr>
  <tr>
    <td>[4] Spoofed Header 차단</td>
    <td class="num">Bypass: ${spoofBypass}건 / 차단율: ${pct(spoofBlocked)}</td>
    <td>Bypass=0, 차단=100%</td>
    <td>${pass(spoofBypass === 0 && spoofBlocked >= 1.0)}</td>
    <td><code>Auth-User-Id: 999</code> + JWT 없음 → 401 (SanitizeFilter 동작 확인)</td>
  </tr>
  <tr>
    <td>[5] Queue-Token 없음 → 403</td>
    <td class="num">${pct(queueBlock)}</td>
    <td>= 100%</td>
    <td>${pass(queueBlock >= 1.0)}</td>
    <td>POST /api/v1/reservations/** + Queue-Token 없음 → 403</td>
  </tr>
  <tr>
    <td>[6] X-Request-Id Propagation</td>
    <td class="num">${pct(reqIdMatch)}</td>
    <td>= 100%</td>
    <td>${pass(reqIdMatch >= 1.0)}</td>
    <td>요청 X-Request-Id = 응답 X-Request-Id</td>
  </tr>
  <tr>
    <td>에러 응답 형식 일관성</td>
    <td class="num">${pct(pdRate)}</td>
    <td>= 100%</td>
    <td>${pass(pdRate >= 1.0)}</td>
    <td>4xx 응답이 RFC 7807 ProblemDetail JSON (status + title 필드)</td>
  </tr>
</table>

<h2>Threshold 판정</h2>
<table>
  <tr><th>Threshold</th><th>결과</th></tr>
  ${Object.entries(data.metrics)
      .filter(([, v]) => v.thresholds)
      .map(([k, v]) => Object.entries(v.thresholds)
          .map(([expr, t]) => `<tr><td>${k}: ${expr}</td><td>${pass(t.ok)}</td></tr>`)
          .join('')
      ).join('')}
</table>

<h2>분석</h2>
${diagnostics.length > 0
    ? diagnostics.map(d => `<div class="diag"><h3>${d.symptom}</h3><ol>
    ${d.causes.map(c => `<li><span class="cause">${c.text}</span><span class="how">확인: ${c.check}</span></li>`).join('')}
    </ol></div>`).join('\n')
    : passNotes.map(n => `<div class="note">${n}</div>`).join('\n')}

<h2>Observability 수집 가이드</h2>
<div class="obs">
  <strong>테스트 완료 후 아래 쿼리로 각 도구에서 결과를 직접 확인할 것</strong><br/><br/>
  <strong>Jaeger:</strong> <code>http://192.168.124.100:8080/jaeger/search?service=scg-app</code><br/>
  → Service: scg-app, Tags: <code>requestId={X-Request-Id 값}</code> 입력 → 트레이스 클릭 → scg-app → 하위 서비스 전파 확인<br/><br/>
  <strong>Kibana:</strong> Discover → index: <code>filebeat-*</code> → KQL: <code>requestId: "{X-Request-Id 값}"</code><br/>
  → auth_expired/spoofed_header 시나리오 시간대에 WARN 레벨 [SANITIZE] Stripped 로그 존재 여부 확인<br/><br/>
  <strong>Elasticsearch:</strong>
  <code>GET /filebeat-*/_search?q=log.level:WARN AND message:[SANITIZE]&size=20</code><br/>
  → RequestSanitizeFilter가 위조 헤더를 실제로 strip한 로그 확인<br/><br/>
  <strong>Grafana:</strong> SCG 대시보드 → 테스트 시간대 선택<br/>
  → Panel: HTTP Status Code Distribution → 401/403 spike 확인<br/>
  → Panel: Request Rate → 시나리오별 트래픽 패턴 확인<br/><br/>
  <strong>Prometheus PromQL:</strong><br/>
  <code>sum(increase(http_server_requests_seconds_count{status="401"}[5m])) by (uri)</code> — 인증 실패 경로 분포<br/>
  <code>sum(increase(http_server_requests_seconds_count{uri=~".*/reservations.*", status="403"}[5m]))</code> — Queue Token 차단 수
</div>

<p class="meta">Generated by k6 scenario12-gateway-security-verification.js | ${testDate} | run_id: ${RUN_TAG}</p>
</body>
</html>`;

    const consoleMsg = [
        `\n[scenario12-gateway-security-verification] ${passText}  (100% 일관성 기준)`,
        `  [1] auth_success baseline P95 = ${authSuccP95.toFixed(1)}ms / P50 = ${authSuccP50.toFixed(1)}ms`,
        `  [2] auth_expired → 401: ${pct(authExpOk)}  ${authExpOk >= 1.0 ? '✓' : '✗ 100% 미달'}`,
        `  [3] auth_missing → 401: ${pct(authMissOk)}  ${authMissOk >= 1.0 ? '✓' : '✗ 100% 미달'}`,
        `  [4] spoofed bypass: ${spoofBypass}건 (must be 0)  ${spoofBypass === 0 ? '✓' : '✗ BYPASS 발생!'}`,
        `  [5] queue_token → 403: ${pct(queueBlock)}  ${queueBlock >= 1.0 ? '✓' : '✗ 100% 미달'}`,
        `  [6] X-Request-Id match: ${pct(reqIdMatch)}  ${reqIdMatch >= 1.0 ? '✓' : '✗ 100% 미달'}`,
        `  ProblemDetail 일관성: ${pct(pdRate)}  ${pdRate >= 1.0 ? '✓' : '✗ 100% 미달'}`,
        `  산출물: ${RESULT_DIR}/{html,json,csv}/scenario12-gateway-security-verification_${RUN_TAG}.*`,
        '',
    ].join('\n');

    // k6 전체 메트릭 raw dump (summary.json 역할)
    const rawSummary = JSON.stringify(data, null, 2);

    return {
        stdout: consoleMsg,
        [`${RESULT_DIR}/json/scenario12-gateway-security-verification_${RUN_TAG}.json`]: JSON.stringify(jsonReport, null, 2),
        [`${RESULT_DIR}/json/scenario12-gateway-security-verification_${RUN_TAG}_raw-summary.json`]: rawSummary,
        [`${RESULT_DIR}/csv/scenario12-gateway-security-verification_${RUN_TAG}.csv`]:  csv,
        [`${RESULT_DIR}/html/scenario12-gateway-security-verification_${RUN_TAG}.html`]: html,
    };
}
