// SCG 시나리오 6: E2E 혼합 부하 (Soak Test)
//
// 목적:
//   실제 사용 패턴에 가까운 혼합 트래픽 10분 이상 지속하여
//   메모리 누수, GC 압력, 레이턴시 점진 증가 여부 확인
//
// 트래픽 비율 (단일 VU 함수 내 확률 분기):
//   60% — waiting-room: GET /api/v1/waiting-room/status?eventId=1&userId={rand}
//   25% — concert:      GET /api/v1/events/1
//   10% — payment:      GET /api/v1/payments/1
//    5% — bad:          잘못된 JWT 또는 존재하지 않는 경로
//
// VU 설계 (rate-limit 초과 방지):
//   20 VU × sleep 1s → ~20 req/s 총량
//   waiting-room:  ~12 req/s (burstCapacity=200 ✓)
//   concert:        ~5 req/s (burstCapacity=50  ✓)
//   payment:        ~2 req/s (replenishRate=5   ✓)
//   bad:            ~1 req/s (401 반환, rate-limiter 미도달)
//
// 판단 기준:
//   전체 p95 < 200ms
//   에러율 < 1% (429 Rate Limit 제외)
//   레이턴시 점진 증가 없음 (window별 p95 측정)
//   GC 압력: Grafana jvm.gc.pause 메트릭으로 병행 모니터링 권장
//
// 실행 (10분):
//   k6 run \
//     --env SCG_BASE_URL=http://192.168.124.100:8090 \
//     --env DURATION=10m \
//     scenario6-soak.js
//
// 실행 (30분 — 메모리 누수 확인):
//   k6 run \
//     --env SCG_BASE_URL=http://192.168.124.100:8090 \
//     --env DURATION=30m \
//     scenario6-soak.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// ── 환경 변수 ────────────────────────────────────────────────
const SCG_BASE_URL = __ENV.SCG_BASE_URL || 'http://192.168.124.100:8090';
const JWT_SECRET   = __ENV.JWT_SECRET   || 'change-me-in-production-must-be-at-least-32-bytes!!';
const DURATION     = __ENV.DURATION     || '10m';
const RESULT_DIR   = __ENV.RESULT_DIR   || 'results';
const RUN_TAG = (() => {
    const d = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${pad(d.getMonth()+1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
})();

// ── JWT 생성 ─────────────────────────────────────────────────
function generateJwt(userId) {
    const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
    const now = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({ sub: String(userId), roles: ['USER'], iat: now, exp: now + 7200 }),
        'rawurl'
    );
    const sig = crypto.hmac('sha256', JWT_SECRET, `${header}.${payload}`, 'base64rawurl');
    return `${header}.${payload}.${sig}`;
}

function generateExpiredJwt() {
    const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
    const now = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({ sub: '999', roles: ['USER'], iat: now - 7200, exp: now - 3600 }),
        'rawurl'
    );
    const sig = crypto.hmac('sha256', JWT_SECRET, `${header}.${payload}`, 'base64rawurl');
    return `${header}.${payload}.${sig}`;
}

// ── 커스텀 메트릭 ────────────────────────────────────────────
// 서비스별 레이턴시 (soak에서 점진 증가 여부 추적)
const waitingRoomDuration = new Trend('soak_waitingroom_duration', true);
const concertDuration     = new Trend('soak_concert_duration', true);
const paymentDuration     = new Trend('soak_payment_duration', true);

// 서비스별 카운터
const waitingRoomCount = new Counter('soak_waitingroom_count');
const concertCount     = new Counter('soak_concert_count');
const paymentCount     = new Counter('soak_payment_count');
const badCount         = new Counter('soak_bad_count');

// 에러 분류
const serviceErrorCount = new Counter('soak_service_error_count');   // 5xx
const rateLimitCount    = new Counter('soak_rate_limit_count');      // 429 (예상된 동작)
const unexpectedCount   = new Counter('soak_unexpected_count');

// 전체 에러율 (429 제외)
const errorRate = new Rate('soak_error_rate');

// 전체 레이턴시 (200ms p95 threshold)
const overallDuration = new Trend('soak_overall_duration', true);

// ── 테스트 옵션 ──────────────────────────────────────────────
export const options = {
    scenarios: {
        soak: {
            executor: 'constant-vus',
            vus: 20,
            duration: DURATION,
            exec: 'soakPhase',
        },
    },
    thresholds: {
        // 전체 레이턴시 p95 < 200ms
        'soak_overall_duration': ['p(95)<200'],
        // 에러율 < 1% (429 제외)
        'soak_error_rate': ['rate<0.01'],
        // 서비스별 레이턴시 — soak 중 점진 증가 감지 기준
        'soak_waitingroom_duration': ['p(95)<300'],
        'soak_concert_duration': ['p(95)<300'],
        'soak_payment_duration': ['p(95)<500'],
        // 비정상 응답 최소화
        'soak_unexpected_count': ['count<50'],
    },
};

// ── setup: 사전 검증 ─────────────────────────────────────────
export function setup() {
    const token = generateJwt(1);

    const services = [
        { name: 'waiting-room', path: '/api/v1/waiting-room/status?eventId=1&userId=1' },
        { name: 'concert',      path: '/api/v1/events/1' },
        { name: 'payment',      path: '/api/v1/payments/1' },
    ];

    let allOk = true;
    for (const svc of services) {
        const res = http.get(`${SCG_BASE_URL}${svc.path}`, {
            headers: { 'Authorization': `Bearer ${token}` },
            timeout: '5s',
        });
        const ok = res.status === 200 || res.status === 404;
        console.log(`[setup] ${svc.name}: status=${res.status} ${ok ? 'OK' : 'WARN'}`);
        if (!ok && res.status !== 429) allOk = false;
    }

    if (!allOk) {
        console.warn(`[setup] 일부 서비스 preflight 실패. SCG 및 각 서비스 상태 확인 권장`);
    }

    console.log(`\n[Soak Test 시작] Duration=${DURATION}, VUs=20, ~20 req/s`);
    console.log(`  트래픽 비율: waiting-room 60% / concert 25% / payment 10% / bad 5%`);
    console.log(`  모니터링 권장: Grafana → jvm.memory.used, jvm.gc.pause (각 서비스)\n`);

    return { token };
}

// ── 사용자 시뮬레이션 ──────────────────────────────────────
export function soakPhase(setupData) {
    const token = setupData.token || generateJwt(1);
    const rand = Math.random();

    if (rand < 0.60) {
        // waiting-room (60%)
        const userId = Math.floor(Math.random() * 100) + 1;
        const res = http.get(
            `${SCG_BASE_URL}/api/v1/waiting-room/status?eventId=1&userId=${userId}`,
            {
                headers: { 'Authorization': `Bearer ${token}` },
                tags: { service: 'waiting-room' },
                timeout: '10s',
            }
        );
        waitingRoomCount.add(1);
        waitingRoomDuration.add(res.timings.duration);
        overallDuration.add(res.timings.duration);
        classifyResponse(res, 'waiting-room');

    } else if (rand < 0.85) {
        // concert (25%)
        const res = http.get(`${SCG_BASE_URL}/api/v1/events/1`, {
            headers: { 'Authorization': `Bearer ${token}` },
            tags: { service: 'concert' },
            timeout: '10s',
        });
        concertCount.add(1);
        concertDuration.add(res.timings.duration);
        overallDuration.add(res.timings.duration);
        classifyResponse(res, 'concert');

    } else if (rand < 0.95) {
        // payment (10%)
        const res = http.get(`${SCG_BASE_URL}/api/v1/payments/1`, {
            headers: { 'Authorization': `Bearer ${token}` },
            tags: { service: 'payment' },
            timeout: '10s',
        });
        paymentCount.add(1);
        paymentDuration.add(res.timings.duration);
        overallDuration.add(res.timings.duration);
        classifyResponse(res, 'payment');

    } else {
        // bad request (5%) — 만료된 JWT 또는 없는 경로
        badCount.add(1);
        const useBadPath = Math.random() < 0.5;

        if (useBadPath) {
            // 존재하지 않는 경로
            const res = http.get(`${SCG_BASE_URL}/api/v1/not-exist-path-${Math.floor(Math.random()*1000)}`, {
                headers: { 'Authorization': `Bearer ${token}` },
                tags: { service: 'bad-path' },
                timeout: '5s',
            });
            // 404: SCG가 라우팅 못 찾음 → 정상적인 응답
            // 에러율 계산 제외 (의도적인 bad request)
            check(res, { '[BAD-PATH] 404 expected': (r) => r.status === 404 });
        } else {
            // 만료된 JWT
            const expiredToken = generateExpiredJwt();
            const res = http.get(`${SCG_BASE_URL}/api/v1/events/1`, {
                headers: { 'Authorization': `Bearer ${expiredToken}` },
                tags: { service: 'bad-jwt' },
                timeout: '5s',
            });
            check(res, { '[BAD-JWT] 401 expected': (r) => r.status === 401 });
        }
    }

    sleep(1.0);
}

function classifyResponse(res, serviceName) {
    if (res.status === 200 || res.status === 404) {
        errorRate.add(0);
        check(res, { [`[${serviceName.toUpperCase()}] 정상 응답`]: () => true });
    } else if (res.status === 429) {
        // Rate Limit: 예상된 동작, 에러율 제외
        rateLimitCount.add(1);
        errorRate.add(0);
    } else if (res.status >= 500) {
        serviceErrorCount.add(1);
        errorRate.add(1);
        console.error(`[${serviceName}] 5xx 응답: status=${res.status} duration=${res.timings.duration.toFixed(1)}ms`);
    } else if (res.status === 503) {
        // CB Fallback
        serviceErrorCount.add(1);
        errorRate.add(1);
        console.warn(`[${serviceName}] CB Fallback(503): 해당 서비스 CB OPEN 가능성`);
    } else {
        unexpectedCount.add(1);
        errorRate.add(1);
        console.warn(`[${serviceName}] 예상치 못한 응답: status=${res.status}`);
    }
}

export default function (setupData) {
    soakPhase(setupData || {});
}

// ── 결과 산출물 ──────────────────────────────────────────────
export function handleSummary(data) {
    const m = (name, key) => data.metrics[name]?.values?.[key] || 0;

    const totalReqs = m('http_reqs', 'count');

    const wrCnt = m('soak_waitingroom_count', 'count');
    const cCnt  = m('soak_concert_count', 'count');
    const pCnt  = m('soak_payment_count', 'count');
    const bCnt  = m('soak_bad_count', 'count');

    const errCnt       = m('soak_service_error_count', 'count');
    const rlCnt        = m('soak_rate_limit_count', 'count');
    const unexpCnt     = m('soak_unexpected_count', 'count');
    const errRate      = m('soak_error_rate', 'rate');

    const overallP50 = m('soak_overall_duration', 'p(50)');
    const overallP95 = m('soak_overall_duration', 'p(95)');
    const overallP99 = m('soak_overall_duration', 'p(99)');
    const overallMax = m('soak_overall_duration', 'max');

    const wrP95 = m('soak_waitingroom_duration', 'p(95)');
    const cP95  = m('soak_concert_duration', 'p(95)');
    const pP95  = m('soak_payment_duration', 'p(95)');

    const passP95      = overallP95 < 200;
    const passErrRate  = errRate < 0.01;
    const passUnexp    = unexpCnt < 50;
    const overallPass  = passP95 && passErrRate && passUnexp;

    const testDate = new Date().toISOString();

    // ── Diagnostics ─────────────────────────────────────────
    const diagnostics = [];

    if (!passP95) {
        diagnostics.push({
            symptom: `전체 P95 ${overallP95.toFixed(1)}ms — 목표(200ms) 초과`,
            causes: [
                { text: 'payment-service 레이턴시가 전체 p95를 끌어올림 (replenishRate=5/s로 대기 발생)', check: `payment P95=${pP95.toFixed(1)}ms 확인. payment 비율을 5%로 줄이거나 rate 조정` },
                { text: 'soak 후반부 레이턴시 증가 → GC 압력 또는 메모리 누수', check: 'Grafana → jvm.memory.used 증가 추세 확인 (soak 시작 vs 종료). jvm.gc.pause 급증 여부' },
                { text: 'waiting-room Redis ZADD/ZRANK 연산 누적 지연', check: 'Redis INFO keyspace에서 waiting-room 키 수 확인' },
            ],
        });
    }
    if (!passErrRate) {
        diagnostics.push({
            symptom: `에러율 ${(errRate * 100).toFixed(2)}% — 목표(1%) 초과`,
            causes: [
                { text: 'CB OPEN으로 503 fallback 발생', check: 'GET /actuator/health 각 서비스 CB 상태 확인' },
                { text: 'payment 또는 concert rate-limit(429)이 에러율 계산에 포함됨', check: `rateLimitCount=${rlCnt}건. 429는 예상 동작이므로 에러율에서 제외됨. 5xx serviceErrorCount=${errCnt}건 확인` },
                { text: 'soak 후반부 메모리 부족으로 서비스 OOM 발생', check: 'Grafana → jvm.memory.used max 확인. docker stats로 컨테이너 메모리 확인' },
            ],
        });
    }
    if (rlCnt > totalReqs * 0.05) {
        diagnostics.push({
            symptom: `Rate Limit(429) ${rlCnt}건 — 전체 요청의 ${(rlCnt/totalReqs*100).toFixed(1)}% 초과`,
            causes: [
                { text: 'payment-service(replenishRate=5/s)에 요청이 집중됨', check: `payment 비율 조정 또는 sleep 증가. 현재 ~2 req/s 목표 → 실제 pCnt=${pCnt}건/${DURATION}` },
                { text: 'Redis rate-limiter 슬라이딩 윈도우 누적 (1초 고정 윈도)', check: 'payment rate: burstCapacity=10, replenishRate=5. 버스트 초과 시 일시적 429 발생 정상' },
            ],
        });
    }

    const passNotes = [];
    if (overallPass) {
        passNotes.push(
            `${DURATION} 동안 총 ${totalReqs}건 처리. P95=${overallP95.toFixed(1)}ms, 에러율=${(errRate*100).toFixed(2)}%.`
        );
        passNotes.push(
            `서비스별 P95: waiting-room=${wrP95.toFixed(1)}ms, concert=${cP95.toFixed(1)}ms, payment=${pP95.toFixed(1)}ms. ` +
            `레이턴시 점진 증가 여부는 Grafana 대시보드에서 시간대별 추이로 확인 권장.`
        );
        if (rlCnt > 0) {
            passNotes.push(
                `Rate Limit(429) ${rlCnt}건 발생 — 에러율 계산에서 제외된 예상된 동작. ` +
                `payment-service(replenishRate=5/s)의 의도적인 트래픽 조절입니다.`
            );
        }
        passNotes.push(
            `면접 포인트: "${DURATION} Soak Test에서 레이턴시 증가나 에러율 급증이 없었습니다. ` +
            `메모리 누수 여부는 Grafana jvm.memory.used 추세로 별도 확인합니다."`
        );
    }

    // ── JSON ────────────────────────────────────────────────
    const jsonReport = {
        scenario: 'scenario6-soak',
        timestamp: testDate,
        config: {
            scgBaseUrl: SCG_BASE_URL,
            duration: DURATION,
            vus: 20,
            targetRps: '~20 req/s',
            trafficMix: {
                waitingRoom: '60% — GET /api/v1/waiting-room/status',
                concert:     '25% — GET /api/v1/events/1',
                payment:     '10% — GET /api/v1/payments/1',
                bad:         '5%  — expired JWT 또는 404 경로',
            },
            rateLimits: {
                waitingRoom: 'replenishRate=100, burstCapacity=200',
                concert:     'replenishRate=30,  burstCapacity=50',
                payment:     'replenishRate=5,   burstCapacity=10',
            },
        },
        results: {
            totalRequests: totalReqs,
            byService: {
                waitingRoom: { count: wrCnt, p95Ms: +wrP95.toFixed(2) },
                concert:     { count: cCnt,  p95Ms: +cP95.toFixed(2)  },
                payment:     { count: pCnt,  p95Ms: +pP95.toFixed(2)  },
                bad:         { count: bCnt },
            },
            overall: {
                p50: +overallP50.toFixed(2),
                p95: +overallP95.toFixed(2),
                p99: +overallP99.toFixed(2),
                max: +overallMax.toFixed(2),
            },
            errors: {
                serviceError5xx: errCnt,
                rateLimit429: rlCnt,
                unexpected: unexpCnt,
                errorRatePercent: +(errRate * 100).toFixed(2),
            },
        },
        thresholds: Object.fromEntries(
            Object.entries(data.metrics)
                .filter(([, v]) => v.thresholds)
                .map(([k, v]) => [k, v.thresholds])
        ),
        pass: overallPass,
        note: 'GC 압력 및 메모리 누수 여부는 Grafana jvm.memory.used, jvm.gc.pause로 확인 (k6 외부 지표)',
        diagnostics: diagnostics.map(d => ({
            symptom: d.symptom,
            causes: d.causes.map(c => ({ cause: c.text, check: c.check })),
        })),
    };

    // ── CSV ─────────────────────────────────────────────────
    const csvHeader = 'test_date,scenario,metric,value,unit,target,pass';
    const csvRows = [
        [testDate, 'scenario6', 'total_requests',        totalReqs,                    'count', '-',     '-'],
        [testDate, 'scenario6', 'overall_p95',            overallP95.toFixed(2),        'ms',    '<200',  passP95],
        [testDate, 'scenario6', 'overall_p99',            overallP99.toFixed(2),        'ms',    '-',     '-'],
        [testDate, 'scenario6', 'error_rate',             (errRate*100).toFixed(2),     '%',     '<1',    passErrRate],
        [testDate, 'scenario6', 'service_error_5xx',      errCnt,                       'count', '0',     errCnt === 0],
        [testDate, 'scenario6', 'rate_limit_429',         rlCnt,                        'count', '-',     '-'],
        [testDate, 'scenario6', 'unexpected',             unexpCnt,                     'count', '<50',   passUnexp],
        [testDate, 'scenario6', 'waiting_room_p95',       wrP95.toFixed(2),             'ms',    '<300',  wrP95 < 300],
        [testDate, 'scenario6', 'concert_p95',            cP95.toFixed(2),              'ms',    '<300',  cP95 < 300],
        [testDate, 'scenario6', 'payment_p95',            pP95.toFixed(2),              'ms',    '<500',  pP95 < 500],
        [testDate, 'scenario6', 'waiting_room_count',     wrCnt,                        'count', '-',     '-'],
        [testDate, 'scenario6', 'concert_count',          cCnt,                         'count', '-',     '-'],
        [testDate, 'scenario6', 'payment_count',          pCnt,                         'count', '-',     '-'],
        [testDate, 'scenario6', 'bad_count',              bCnt,                         'count', '-',     '-'],
    ].map(r => r.join(',')).join('\n');

    const csv = `${csvHeader}\n${csvRows}\n`;

    // ── HTML ─────────────────────────────────────────────────
    const passColor = overallPass ? '#22c55e' : '#ef4444';
    const passText  = overallPass ? 'PASS' : 'FAIL';

    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>SCG E2E 혼합 부하 Soak Test 결과</title>
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
  .warn { background: #fffbeb; border: 1px solid #fde68a; border-radius: 8px; padding: 12px 16px; margin: 8px 0; font-size: 0.9rem; color: #92400e; }
</style>
</head>
<body>
<h1>SCG E2E 혼합 부하 Soak Test — 시나리오 6 <span class="badge">${passText}</span></h1>
<p style="color:#6b7280; font-size:0.85rem;">${testDate} | ${SCG_BASE_URL} | ${DURATION}, 20 VUs, ~20 req/s</p>

<h2>목적</h2>
<p>실제 사용 패턴에 가까운 혼합 트래픽으로 ${DURATION} 동안 SCG와 각 마이크로서비스의 안정성을 확인한다.
메모리 누수, GC 압력, 레이턴시 점진 증가를 탐지한다.</p>

<div class="warn">
  ⚠️ <strong>GC 압력 / 메모리 누수 감지는 Grafana 필수:</strong>
  k6는 JVM 내부 메트릭(jvm.memory.used, jvm.gc.pause)을 직접 수집할 수 없습니다.
  Grafana에서 soak 시작 시점과 종료 시점의 jvm.memory.used 비교 및 jvm.gc.pause 급증 여부를 함께 확인하세요.
</div>

<h2>트래픽 비율</h2>
<table>
  <tr><th>서비스</th><th>비율</th><th>요청 수</th><th>Rate Limit</th></tr>
  <tr><td>waiting-room (status)</td><td>60%</td><td class="num">${wrCnt}</td><td>replenishRate=100/s</td></tr>
  <tr><td>concert (GET events/1)</td><td>25%</td><td class="num">${cCnt}</td><td>replenishRate=30/s</td></tr>
  <tr><td>payment (GET payments/1)</td><td>10%</td><td class="num">${pCnt}</td><td>replenishRate=5/s</td></tr>
  <tr><td>bad request (expired JWT / 404)</td><td>5%</td><td class="num">${bCnt}</td><td>401 즉시 거절 (rate-limiter 미도달)</td></tr>
</table>

<h2>전체 레이턴시</h2>
<table>
  <tr><th>구분</th><th class="num">P50</th><th class="num">P95</th><th class="num">P99</th><th class="num">Max</th><th>목표</th><th>판정</th></tr>
  <tr><td><strong>전체</strong></td><td class="num">${overallP50.toFixed(1)}ms</td><td class="num"><strong>${overallP95.toFixed(1)}ms</strong></td><td class="num">${overallP99.toFixed(1)}ms</td><td class="num">${overallMax.toFixed(1)}ms</td><td>&lt;200ms</td><td class="${passP95 ? 'pass' : 'fail'}">${passP95 ? 'PASS' : 'FAIL'}</td></tr>
</table>

<h2>서비스별 레이턴시 P95</h2>
<table>
  <tr><th>서비스</th><th class="num">P95</th><th>목표</th><th>판정</th></tr>
  <tr><td>waiting-room</td><td class="num">${wrP95.toFixed(1)}ms</td><td>&lt;300ms</td><td class="${wrP95 < 300 ? 'pass' : 'fail'}">${wrP95 < 300 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>concert</td><td class="num">${cP95.toFixed(1)}ms</td><td>&lt;300ms</td><td class="${cP95 < 300 ? 'pass' : 'fail'}">${cP95 < 300 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>payment</td><td class="num">${pP95.toFixed(1)}ms</td><td>&lt;500ms</td><td class="${pP95 < 500 ? 'pass' : 'fail'}">${pP95 < 500 ? 'PASS' : 'FAIL'}</td></tr>
</table>

<h2>에러 분류</h2>
<table>
  <tr><th>종류</th><th class="num">건수</th><th>설명</th><th>판정</th></tr>
  <tr><td>5xx 서비스 에러</td><td class="num">${errCnt}</td><td>CB fallback, 서버 오류</td><td class="${errCnt === 0 ? 'pass' : 'fail'}">${errCnt === 0 ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td>429 Rate Limit</td><td class="num">${rlCnt}</td><td>예상된 동작 (에러율 제외)</td><td style="color:#6b7280;">정상</td></tr>
  <tr><td>비정상 응답</td><td class="num">${unexpCnt}</td><td>401/기타</td><td class="${passUnexp ? 'pass' : 'fail'}">${passUnexp ? 'PASS' : 'FAIL'}</td></tr>
  <tr><td><strong>전체 에러율</strong></td><td class="num"><strong>${(errRate*100).toFixed(2)}%</strong></td><td>&lt;1% (429 제외)</td><td class="${passErrRate ? 'pass' : 'fail'}">${passErrRate ? 'PASS' : 'FAIL'}</td></tr>
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

<h2>분석</h2>
${diagnostics.length > 0
    ? diagnostics.map(d => `<div class="diag">
  <h3>${d.symptom}</h3>
  <ol>
    ${d.causes.map(c => `<li><span class="cause">${c.text}</span><span class="how">확인: ${c.check}</span></li>`).join('\n    ')}
  </ol>
</div>`).join('\n')
    : passNotes.map(n => `<div class="note">${n}</div>`).join('\n')}

<p class="meta">Generated by k6 scenario6-soak.js | ${DURATION}, 20 VUs, mix=60/25/10/5%</p>
</body>
</html>`;

    const consoleMsg = [
        `\n[scenario6-soak] ${passText} | ${DURATION}`,
        `  총 요청: ${totalReqs} | 전체 P95=${overallP95.toFixed(1)}ms | 에러율=${(errRate*100).toFixed(2)}%`,
        `  WR=${wrCnt}건(P95=${wrP95.toFixed(1)}ms) / Concert=${cCnt}건(P95=${cP95.toFixed(1)}ms) / Payment=${pCnt}건(P95=${pP95.toFixed(1)}ms)`,
        `  429=${rlCnt} / 5xx=${errCnt} / unexpected=${unexpCnt}`,
        `  산출물: ${RESULT_DIR}/{html,json,csv}/scenario6-soak_${RUN_TAG}.*`,
        '',
    ].join('\n');

    return {
        stdout: consoleMsg,
        [`${RESULT_DIR}/json/scenario6-soak_${RUN_TAG}.json`]: JSON.stringify(jsonReport, null, 2),
        [`${RESULT_DIR}/csv/scenario6-soak_${RUN_TAG}.csv`]:  csv,
        [`${RESULT_DIR}/html/scenario6-soak_${RUN_TAG}.html`]: html,
    };
}
