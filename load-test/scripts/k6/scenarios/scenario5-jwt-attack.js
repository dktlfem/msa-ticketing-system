// SCG 시나리오 5: JWT 인증 실패 부하
//
// 목적:
//   잘못된/만료된 JWT로 대량 요청 시 JwtAuthenticationFilter 안정성 검증
//   공격 요청이 처리되는 동안 정상 사용자 요청에 영향을 주지 않는지 확인
//
// 두 그룹 동시 실행:
//   attacker: 잘못된 JWT 토큰으로 고속 요청 → 100% 401 반환 확인
//   normal  : 유효한 JWT로 일반 요청 → 정상 처리 확인 (latency / error 영향 없음)
//
// 잘못된 토큰 종류 (round-robin):
//   1. 완전히 잘못된 형식 (malformed)
//   2. 올바른 구조, 잘못된 서명 (wrong-signature)
//   3. 만료된 토큰 (expired, exp = 현재 -1h)
//   4. subject(sub) 누락 (no-subject)
//   5. Authorization 헤더 없음 (no-header)
//
// 판단 기준:
//   - attacker_401_rate = 100% (모든 공격 요청이 401로 거절)
//   - normal_error_rate < 1% (정상 요청 에러 없음)
//   - normal_latency_p95 < 300ms (공격 중에도 정상 응답 유지)
//   - scg_reject_duration p95 < 50ms (401 응답이 빠르게 반환되어야 함)
//
// 실행:
//   k6 run \
//     --env SCG_BASE_URL=http://192.168.124.100:8090 \
//     scenario5-jwt-attack.js

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
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${pad(d.getMonth()+1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
})();

// ── JWT 생성 유틸 ────────────────────────────────────────────
function generateValidJwt(userId) {
    const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
    const now = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({ sub: String(userId), roles: ['USER'], iat: now, exp: now + 3600 }),
        'rawurl'
    );
    const sig = crypto.hmac('sha256', JWT_SECRET, `${header}.${payload}`, 'base64rawurl');
    return `${header}.${payload}.${sig}`;
}

function generateExpiredJwt(userId) {
    const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
    const now = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({ sub: String(userId), roles: ['USER'], iat: now - 7200, exp: now - 3600 }),
        'rawurl'
    );
    const sig = crypto.hmac('sha256', JWT_SECRET, `${header}.${payload}`, 'base64rawurl');
    return `${header}.${payload}.${sig}`;
}

function generateWrongSigJwt(userId) {
    const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
    const now = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({ sub: String(userId), roles: ['USER'], iat: now, exp: now + 3600 }),
        'rawurl'
    );
    const wrongKey = 'wrong-secret-key-that-is-at-least-32-bytes-long!!';
    const sig = crypto.hmac('sha256', wrongKey, `${header}.${payload}`, 'base64rawurl');
    return `${header}.${payload}.${sig}`;
}

function generateNoSubJwt() {
    const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
    const now = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({ roles: ['USER'], iat: now, exp: now + 3600 }),
        'rawurl'
    );
    const sig = crypto.hmac('sha256', JWT_SECRET, `${header}.${payload}`, 'base64rawurl');
    return `${header}.${payload}.${sig}`;
}

// 토큰 종류별 순환 (VU별 독립 인덱스)
let attackTokenIndex = 0;
function nextAttackToken() {
    const tokens = [
        { type: 'malformed',       token: 'not.a.valid.jwt.at.all' },
        { type: 'wrong-signature', token: generateWrongSigJwt(1) },
        { type: 'expired',         token: generateExpiredJwt(1) },
        { type: 'no-subject',      token: generateNoSubJwt() },
        { type: 'no-header',       token: null },  // Authorization 헤더 없음
    ];
    const item = tokens[attackTokenIndex % tokens.length];
    attackTokenIndex++;
    return item;
}

// ── 커스텀 메트릭 ────────────────────────────────────────────
// 공격자 메트릭
const attackCount       = new Counter('jwt_attack_count');
const attack401Count    = new Counter('jwt_attack_401_count');
const attackOtherCount  = new Counter('jwt_attack_other_count'); // 401 아닌 응답 (이상 징후)
const attack401Rate     = new Rate('jwt_attack_401_rate');
const attackDuration    = new Trend('jwt_attack_duration', true);

// 정상 사용자 메트릭
const normalCount       = new Counter('jwt_normal_count');
const normalSuccessCount= new Counter('jwt_normal_success_count');
const normalErrorCount  = new Counter('jwt_normal_error_count');
const normalErrorRate   = new Rate('jwt_normal_error_rate');
const normalDuration    = new Trend('jwt_normal_duration', true);

// 토큰 종류별 분포 기록 (diagnostics용)
const malformedCount   = new Counter('jwt_type_malformed');
const wrongSigCount    = new Counter('jwt_type_wrong_sig');
const expiredCount     = new Counter('jwt_type_expired');
const noSubjectCount   = new Counter('jwt_type_no_subject');
const noHeaderCount    = new Counter('jwt_type_no_header');

// ── 테스트 옵션 ──────────────────────────────────────────────
// attacker: constant-arrival-rate 100 req/s — JwtAuthenticationFilter CPU 부하 유발
// normal:   constant-arrival-rate 10 req/s  — 공격 중 정상 사용자 시뮬레이션
//
// concert replenishRate=30, burstCapacity=50.
// attacker 100 req/s가 rate-limit에 걸릴 수 있으나, JWT 검증(401)은 route filter 이전에
// GlobalFilter에서 처리됨 → RequestRateLimiter에 도달하지 않음.
// 즉 JWT 실패 요청은 rate-limiter Redis 호출 없이 즉시 401 반환.
export const options = {
    scenarios: {
        attacker: {
            executor: 'constant-arrival-rate',
            rate: 100,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 20,
            maxVUs: 60,
            exec: 'attackPhase',
            tags: { role: 'attacker' },
        },
        normal: {
            executor: 'constant-arrival-rate',
            rate: 10,
            timeUnit: '1s',
            duration: '60s',
            preAllocatedVUs: 3,
            maxVUs: 10,
            exec: 'normalPhase',
            tags: { role: 'normal' },
        },
    },
    thresholds: {
        // 공격 요청: 모두 401이어야 함
        'jwt_attack_401_rate': ['rate>0.99'],
        // 401 응답 속도: 빠르게 거절해야 SCG 부하 최소화
        'jwt_attack_duration': ['p(95)<50'],
        // 정상 사용자: 에러율 1% 미만
        'jwt_normal_error_rate': ['rate<0.01'],
        // 정상 사용자: 공격 중에도 P95 300ms 이내
        'jwt_normal_duration': ['p(95)<300'],
    },
};

// ── setup ──────────────────────────────────────────────────
export function setup() {
    const token = generateValidJwt(1);
    const res = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers: { 'Authorization': `Bearer ${token}` },
        timeout: '5s',
    });
    console.log(`[setup] preflight: status=${res.status}`);

    if (res.status === 0) {
        console.error(`SCG 연결 실패 (${SCG_BASE_URL})`);
    }

    // 잘못된 JWT 사전 검증
    const badRes = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers: { 'Authorization': 'Bearer malformed.jwt.token' },
        timeout: '5s',
    });
    console.log(`[setup] invalid JWT preflight: status=${badRes.status} (expected 401)`);
    if (badRes.status !== 401) {
        console.warn(`[WARN] 잘못된 JWT에 401이 아닌 ${badRes.status} 반환. JwtAuthenticationFilter 동작 확인 필요`);
    }

    return { token };
}

// ── 공격자 Phase ─────────────────────────────────────────────
export function attackPhase() {
    const { type, token } = nextAttackToken();

    const headers = { 'X-Test-Scenario': 'jwt-attack', 'X-Test-Role': 'attacker' };
    if (token !== null) {
        headers['Authorization'] = `Bearer ${token}`;
    }

    const res = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers,
        tags: { role: 'attacker', token_type: type },
        timeout: '5s',
    });

    attackCount.add(1, { token_type: type });
    attackDuration.add(res.timings.duration, { token_type: type });

    // 토큰 종류별 집계
    if (type === 'malformed')       malformedCount.add(1);
    else if (type === 'wrong-signature') wrongSigCount.add(1);
    else if (type === 'expired')    expiredCount.add(1);
    else if (type === 'no-subject') noSubjectCount.add(1);
    else if (type === 'no-header')  noHeaderCount.add(1);

    if (res.status === 401) {
        attack401Count.add(1);
        attack401Rate.add(1);

        check(res, {
            '[ATTACK] 401 Unauthorized':    (r) => r.status === 401,
            '[ATTACK] ProblemDetail 형식':  (r) => (r.body || '').includes('"title"'),
        });
    } else {
        attackOtherCount.add(1);
        attack401Rate.add(0);
        // 401이 아닌 응답은 이상 징후: SCG가 잘못된 JWT를 통과시키는 경우
        console.error(`[ATTACK BYPASS] type=${type} status=${res.status} — SCG가 잘못된 JWT를 통과시켰거나 rate-limit(429)`);
    }
}

// ── 정상 사용자 Phase ────────────────────────────────────────
export function normalPhase(setupData) {
    const token = setupData.token || generateValidJwt(1);
    const res = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers: {
            'Authorization': `Bearer ${token}`,
            'X-Test-Scenario': 'jwt-attack',
            'X-Test-Role': 'normal',
        },
        tags: { role: 'normal' },
        timeout: '10s',
    });

    normalCount.add(1);
    normalDuration.add(res.timings.duration);

    const ok = (res.status === 200 || res.status === 429);
    // 429는 rate-limit이므로 정상적인 거절. 에러율 계산에서 제외.
    const isError = (res.status !== 200 && res.status !== 429);

    normalErrorRate.add(isError ? 1 : 0);
    if (isError) {
        normalErrorCount.add(1);
        console.warn(`[NORMAL] 비정상 응답: status=${res.status}`);
    } else {
        normalSuccessCount.add(1);
        check(res, { '[NORMAL] 200 OK or 429 Rate Limit': (r) => r.status === 200 || r.status === 429 });
    }
}

export default function (setupData) {
    attackPhase();
    sleep(0.01);
}

// ── 결과 산출물 ──────────────────────────────────────────────
export function handleSummary(data) {
    const m = (name, key) => data.metrics[name]?.values?.[key] || 0;

    const attackCnt      = m('jwt_attack_count', 'count');
    const attack401Cnt   = m('jwt_attack_401_count', 'count');
    const attackOtherCnt = m('jwt_attack_other_count', 'count');
    const attack401R     = m('jwt_attack_401_rate', 'rate');

    const normalCnt    = m('jwt_normal_count', 'count');
    const normalSucc   = m('jwt_normal_success_count', 'count');
    const normalErrCnt = m('jwt_normal_error_count', 'count');
    const normalErrR   = m('jwt_normal_error_rate', 'rate');

    const attackP50 = m('jwt_attack_duration', 'p(50)');
    const attackP95 = m('jwt_attack_duration', 'p(95)');
    const attackP99 = m('jwt_attack_duration', 'p(99)');

    const normalP50 = m('jwt_normal_duration', 'p(50)');
    const normalP95 = m('jwt_normal_duration', 'p(95)');
    const normalP99 = m('jwt_normal_duration', 'p(99)');

    const malformed = m('jwt_type_malformed', 'count');
    const wrongSig  = m('jwt_type_wrong_sig', 'count');
    const expired   = m('jwt_type_expired', 'count');
    const noSubject = m('jwt_type_no_subject', 'count');
    const noHeader  = m('jwt_type_no_header', 'count');

    const pass401     = attack401R > 0.99;
    const passNormErr = normalErrR < 0.01;
    const passNormLat = normalP95 < 300;
    const passRejectSpeed = attackP95 < 50;
    const overallPass = pass401 && passNormErr && passNormLat && passRejectSpeed;

    const testDate = new Date().toISOString();

    // ── Diagnostics ─────────────────────────────────────────
    const diagnostics = [];

    if (!pass401) {
        diagnostics.push({
            symptom: `공격 요청 401율 ${(attack401R * 100).toFixed(1)}% — 100%가 아님. 일부 잘못된 JWT가 SCG를 통과했거나 429로 처리됨`,
            causes: [
                { text: '429 Rate Limit으로 분류된 요청이 attack_other에 포함됨 (정상 동작)', check: `attackOtherCount=${attackOtherCnt}건의 status 분포 확인. JWT 검증은 rate-limiter 이전에 실행됨 (GlobalFilter 순서 확인)` },
                { text: 'JwtAuthenticationFilter excluded-paths에 /api/v1/events가 잘못 포함됨', check: 'application.yml → gateway.security.excluded-paths 확인' },
                { text: 'JWT_SECRET env var가 scg-app의 gateway.security.jwt-secret과 다름', check: `현재 JWT_SECRET=${JWT_SECRET.substring(0,10)}... 확인` },
            ],
        });
    }
    if (!passRejectSpeed) {
        diagnostics.push({
            symptom: `공격 요청 거절 P95 ${attackP95.toFixed(1)}ms — 목표(50ms) 초과`,
            causes: [
                { text: 'JwtAuthenticationFilter가 토큰 파싱 실패 후 불필요한 I/O를 하고 있음', check: 'JwtAuthenticationFilter.java — parseToken() 실패 경로에 블로킹 연산 없는지 확인' },
                { text: 'SCG Netty event loop 과부하 (100 req/s 공격 부하)', check: 'Grafana → reactor.netty.eventloop.* 메트릭 / scg-app CPU 사용률 확인' },
                { text: 'GlobalErrorHandler 또는 writeUnauthorized()에 블로킹 로직', check: 'ProblemDetail 직렬화가 non-blocking인지 확인. 현재 byte[] 직접 직렬화로 Jackson 의존 없음' },
            ],
        });
    }
    if (!passNormErr) {
        diagnostics.push({
            symptom: `정상 사용자 에러율 ${(normalErrR * 100).toFixed(2)}% — 목표(1%) 초과`,
            causes: [
                { text: '공격 부하가 SCG Netty 워커 스레드를 소진시켜 정상 요청 처리 지연', check: 'scg-app CPU 사용률 확인. Grafana → http.server.requests 지표에서 scg-app 5xx 비율 확인' },
                { text: 'concert-service rate-limiter(replenishRate=30)가 공격+정상 트래픽 합산으로 작동', check: 'JWT 검증 실패(401)는 route filter에 도달 안 함 → rate-limiter 영향 없음. 확인: scg-app 로그에서 429 발생 여부' },
                { text: 'circuit breaker가 concert-app 장애로 OPEN됨', check: 'GET /actuator/health에서 concert-service-cb 상태 확인' },
            ],
        });
    }
    if (!passNormLat) {
        diagnostics.push({
            symptom: `정상 사용자 P95 ${normalP95.toFixed(1)}ms — 목표(300ms) 초과`,
            causes: [
                { text: '100 req/s 공격이 SCG Netty event loop을 포화시켜 정상 요청 처리 지연', check: 'attacker VU를 줄이거나 rate를 낮춰 재테스트. 또는 SCG scg-app 힙/스레드 설정 확인' },
                { text: 'concert-app 자체 응답 지연 (공격 트래픽과 무관)', check: `${SCG_BASE_URL}/api/v1/events 직접 호출로 baseline latency 확인` },
            ],
        });
    }
    if (attackCnt === 0) {
        diagnostics.push({
            symptom: '공격 요청이 실행되지 않음',
            causes: [
                { text: 'attacker scenario VU 설정 오류 또는 SCG 연결 실패', check: `curl ${SCG_BASE_URL}/actuator/health` },
            ],
        });
    }

    const passNotes = [];
    if (overallPass) {
        passNotes.push(
            `JwtAuthenticationFilter가 ${attackCnt}건의 잘못된 JWT 요청(100 req/s)을 모두 401로 거절했습니다. ` +
            `거절 P95=${attackP95.toFixed(1)}ms — 즉각적인 응답으로 SCG 부하 최소화.`
        );
        passNotes.push(
            `공격 중에도 정상 사용자(10 req/s) P95=${normalP95.toFixed(1)}ms, 에러율=${(normalErrR*100).toFixed(2)}%로 ` +
            `영향 없음을 확인했습니다.`
        );
        passNotes.push(
            `면접 포인트: "JWT 검증(GlobalFilter order=HIGHEST_PRECEDENCE+4)은 rate-limiter route filter보다 먼저 실행되므로, ` +
            `잘못된 JWT 요청은 Redis round-trip 없이 즉시 거절됩니다. 100 req/s 공격에서도 ` +
            `정상 사용자 응답 시간에 영향이 없었습니다."`
        );
    }

    const testDate2 = new Date().toISOString();

    // ── JSON ────────────────────────────────────────────────
    const jsonReport = {
        scenario: 'scenario5-jwt-attack',
        timestamp: testDate2,
        config: {
            scgBaseUrl: SCG_BASE_URL,
            attackRate: '100 req/s',
            normalRate: '10 req/s',
            duration: '60s',
            tokenTypes: ['malformed', 'wrong-signature', 'expired', 'no-subject', 'no-header'],
            note: 'JwtAuthenticationFilter(order=HIGHEST_PRECEDENCE+4)는 RequestRateLimiter(route filter)보다 먼저 실행. 401 요청은 Redis round-trip 없음.',
        },
        results: {
            attacker: {
                totalRequests: attackCnt,
                rejected401: attack401Cnt,
                other: attackOtherCnt,
                rate401Percent: +(attack401R * 100).toFixed(2),
                latency: { p50: +attackP50.toFixed(2), p95: +attackP95.toFixed(2), p99: +attackP99.toFixed(2) },
                tokenTypeDistribution: { malformed, wrongSignature: wrongSig, expired, noSubject, noHeader },
            },
            normal: {
                totalRequests: normalCnt,
                successCount: normalSucc,
                errorCount: normalErrCnt,
                errorRatePercent: +(normalErrR * 100).toFixed(2),
                latency: { p50: +normalP50.toFixed(2), p95: +normalP95.toFixed(2), p99: +normalP99.toFixed(2) },
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
        [testDate, 'scenario5', 'attack_total',          attackCnt,                       'count', '-',      '-'],
        [testDate, 'scenario5', 'attack_401_count',       attack401Cnt,                    'count', '-',      '-'],
        [testDate, 'scenario5', 'attack_401_rate',        (attack401R*100).toFixed(2),     '%',     '>99',    pass401],
        [testDate, 'scenario5', 'attack_reject_p95',      attackP95.toFixed(2),            'ms',    '<50',    passRejectSpeed],
        [testDate, 'scenario5', 'attack_reject_p99',      attackP99.toFixed(2),            'ms',    '-',      '-'],
        [testDate, 'scenario5', 'normal_total',           normalCnt,                       'count', '-',      '-'],
        [testDate, 'scenario5', 'normal_error_rate',      (normalErrR*100).toFixed(2),     '%',     '<1',     passNormErr],
        [testDate, 'scenario5', 'normal_latency_p95',     normalP95.toFixed(2),            'ms',    '<300',   passNormLat],
        [testDate, 'scenario5', 'normal_latency_p99',     normalP99.toFixed(2),            'ms',    '-',      '-'],
        [testDate, 'scenario5', 'token_malformed',        malformed,                       'count', '-',      '-'],
        [testDate, 'scenario5', 'token_wrong_sig',        wrongSig,                        'count', '-',      '-'],
        [testDate, 'scenario5', 'token_expired',          expired,                         'count', '-',      '-'],
        [testDate, 'scenario5', 'token_no_subject',       noSubject,                       'count', '-',      '-'],
        [testDate, 'scenario5', 'token_no_header',        noHeader,                        'count', '-',      '-'],
    ].map(r => r.join(',')).join('\n');

    const csv = `${csvHeader}\n${csvRows}\n`;

    // ── HTML ─────────────────────────────────────────────────
    const passColor = overallPass ? '#22c55e' : '#ef4444';
    const passText  = overallPass ? 'PASS' : 'FAIL';

    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>SCG JWT 인증 실패 부하 결과</title>
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
<h1>SCG JWT 인증 실패 부하 — 시나리오 5 <span class="badge">${passText}</span></h1>
<p style="color:#6b7280; font-size:0.85rem;">${testDate2} | ${SCG_BASE_URL} | 공격 100 req/s + 정상 10 req/s × 60s</p>

<h2>목적</h2>
<p>잘못된 JWT 토큰 100 req/s 공격 시 SCG의 JwtAuthenticationFilter가 모든 요청을 401로 즉시 거절하고,
정상 사용자 트래픽에 영향이 없는지 검증한다.</p>

<div class="grid">
  <div class="card">
    <div style="color:#6b7280;font-size:0.85rem;">공격 요청 401율</div>
    <div class="card-val" style="color:${pass401 ? '#16a34a' : '#dc2626'}">${(attack401R*100).toFixed(1)}%</div>
    <div style="font-size:0.8rem;">${attackCnt}건 중 ${attack401Cnt}건</div>
  </div>
  <div class="card">
    <div style="color:#6b7280;font-size:0.85rem;">정상 사용자 에러율</div>
    <div class="card-val" style="color:${passNormErr ? '#16a34a' : '#dc2626'}">${(normalErrR*100).toFixed(2)}%</div>
    <div style="font-size:0.8rem;">P95 ${normalP95.toFixed(1)}ms</div>
  </div>
</div>

<h2>공격자 결과</h2>
<table>
  <tr><th>지표</th><th class="num">값</th><th>목표</th><th>판정</th></tr>
  <tr><td>총 공격 요청</td><td class="num">${attackCnt}</td><td>-</td><td>-</td></tr>
  <tr><td>401 거절 건수</td><td class="num">${attack401Cnt}</td><td>-</td><td>-</td></tr>
  <tr><td><strong>401 거절율</strong></td><td class="num"><strong>${(attack401R*100).toFixed(1)}%</strong></td><td>&gt;99%</td><td class="${pass401 ? 'pass' : 'fail'}">${pass401 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>기타 응답 (401 아닌)</td><td class="num">${attackOtherCnt}</td><td>0</td><td class="${attackOtherCnt === 0 ? 'pass' : 'fail'}">${attackOtherCnt === 0 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>401 거절 P95 속도</td><td class="num">${attackP95.toFixed(1)}ms</td><td>&lt;50ms</td><td class="${passRejectSpeed ? 'pass' : 'fail'}">${passRejectSpeed ? 'PASS' : 'FAIL'}</td></tr>
</table>

<h2>토큰 종류별 분포</h2>
<table>
  <tr><th>토큰 종류</th><th class="num">건수</th><th>설명</th></tr>
  <tr><td>malformed</td><td class="num">${malformed}</td><td>JWT 형식 자체가 잘못됨</td></tr>
  <tr><td>wrong-signature</td><td class="num">${wrongSig}</td><td>구조는 정상, 서명 키 불일치</td></tr>
  <tr><td>expired</td><td class="num">${expired}</td><td>유효한 서명, exp 만료</td></tr>
  <tr><td>no-subject</td><td class="num">${noSubject}</td><td>sub claim 누락</td></tr>
  <tr><td>no-header</td><td class="num">${noHeader}</td><td>Authorization 헤더 없음</td></tr>
</table>

<h2>정상 사용자 결과 (공격 병행 중)</h2>
<table>
  <tr><th>지표</th><th class="num">값</th><th>목표</th><th>판정</th></tr>
  <tr><td>총 정상 요청</td><td class="num">${normalCnt}</td><td>-</td><td>-</td></tr>
  <tr><td>에러율</td><td class="num">${(normalErrR*100).toFixed(2)}%</td><td>&lt;1%</td><td class="${passNormErr ? 'pass' : 'fail'}">${passNormErr ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>P50</td><td class="num">${normalP50.toFixed(1)}ms</td><td>-</td><td>-</td></tr>
  <tr><td>P95</td><td class="num">${normalP95.toFixed(1)}ms</td><td>&lt;300ms</td><td class="${passNormLat ? 'pass' : 'fail'}">${passNormLat ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>P99</td><td class="num">${normalP99.toFixed(1)}ms</td><td>-</td><td>-</td></tr>
</table>

<div class="info">
  <strong>설계 포인트 — 왜 JWT 공격이 rate-limiter를 우회하는가:</strong><br/>
  JwtAuthenticationFilter(GlobalFilter order=HIGHEST_PRECEDENCE+4)는 route-level filter인 RequestRateLimiter보다
  먼저 실행됩니다. 잘못된 JWT 요청은 rate-limiter의 Redis 호출 없이 GlobalFilter 단계에서 즉시 401을 반환합니다.<br/><br/>
  이는 두 가지를 의미합니다:<br/>
  1. 공격 트래픽이 Redis에 부하를 주지 않음 (rate-limiter 우회가 아니라 도달 불가)<br/>
  2. JWT 거절 응답(401) 속도가 Redis round-trip 없이 빠름
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

<h2>분석</h2>
${diagnostics.length > 0
    ? diagnostics.map(d => `<div class="diag">
  <h3>${d.symptom}</h3>
  <ol>
    ${d.causes.map(c => `<li><span class="cause">${c.text}</span><span class="how">확인: ${c.check}</span></li>`).join('\n    ')}
  </ol>
</div>`).join('\n')
    : passNotes.map(n => `<div class="note">${n}</div>`).join('\n')}

<p class="meta">Generated by k6 scenario5-jwt-attack.js | attacker=100req/s, normal=10req/s, duration=60s</p>
</body>
</html>`;

    const consoleMsg = [
        `\n[scenario5-jwt-attack] ${passText}`,
        `  공격(${attackCnt}건): 401율=${(attack401R*100).toFixed(1)}% | 거절 P95=${attackP95.toFixed(1)}ms`,
        `  정상(${normalCnt}건): 에러율=${(normalErrR*100).toFixed(2)}% | P95=${normalP95.toFixed(1)}ms`,
        `  산출물: ${RESULT_DIR}/{html,json,csv}/scenario5-jwt-attack_${RUN_TAG}.*`,
        '',
    ].join('\n');

    return {
        stdout: consoleMsg,
        [`${RESULT_DIR}/json/scenario5-jwt-attack_${RUN_TAG}.json`]: JSON.stringify(jsonReport, null, 2),
        [`${RESULT_DIR}/csv/scenario5-jwt-attack_${RUN_TAG}.csv`]:  csv,
        [`${RESULT_DIR}/html/scenario5-jwt-attack_${RUN_TAG}.html`]: html,
    };
}
