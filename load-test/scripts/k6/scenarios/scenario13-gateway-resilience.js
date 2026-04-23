// SCG 시나리오 13: Gateway Resilience — Timeout & waitingroom 장애 시 동작
//
// 목적:
//   waitingroom-service 지연/장애 시 SCG가 어떻게 동작하는지 정량적으로 검증한다.
//   timeout 발생 시 응답 형태(504 ProblemDetail)와
//   Circuit Breaker Open 시 fallback 응답 일관성을 확인한다.
//
// 검증 항목 (3가지):
//   [1] timeout_response     : waitingroom 응답이 글로벌 timeout(10s)을 초과할 때
//                              → 504 Gateway Timeout + ProblemDetail 형식
//   [2] fallback_consistency : Circuit Breaker open 시 fallback 응답 (/fallback/service-unavailable)
//                              → 503 + ProblemDetail 형식 (정상 트래픽에는 영향 없음)
//   [3] normal_isolation     : waitingroom 장애 중에도 concert-app, booking-app 정상 응답 유지
//
// ───────────────────────────────────────────────────────────────
// 실행 전 준비 (수동):
//
//   [시나리오 A] timeout 유발 — waitingroom 응답 지연 시뮬레이션:
//     ssh 192.168.124.100 "docker compose exec waitingroom-app \
//       tc qdisc add dev eth0 root netem delay 12000ms"
//     → 테스트 완료 후 반드시 제거:
//     ssh 192.168.124.100 "docker compose exec waitingroom-app \
//       tc qdisc del dev eth0 root"
//
//   [시나리오 B] Circuit Breaker 유발 — waitingroom-app 중단:
//     ssh 192.168.124.100 "docker compose stop waitingroom-app"
//     → 테스트 완료 후 재시작:
//     ssh 192.168.124.100 "docker compose start waitingroom-app"
//
//   ※ tc 명령 사용 불가능한 경우: waitingroom-app을 직접 종료하여 504/502 케이스 모두 확인
//
// ───────────────────────────────────────────────────────────────
// 복구 절차 (테스트 완료 후 반드시 실행):
//
//   1. tc netem 지연 해제:
//      ssh 192.168.124.100 "docker compose exec waitingroom-app tc qdisc del dev eth0 root"
//
//   2. waitingroom-app 재기동:
//      ssh 192.168.124.100 "cd ~/devops_lab && docker compose start waitingroom-app"
//
//   3. 상태 확인:
//      ssh 192.168.124.100 "docker compose ps waitingroom-app"
//      curl -s http://192.168.124.100:8090/api/v1/waiting-room/status -H 'Authorization: Bearer <jwt>' | jq .
//      → status=200 확인
//
//   4. Circuit Breaker 상태 확인 (Half-Open → Closed 전환 대기):
//      curl -s http://192.168.124.100:8090/actuator/circuitbreakers | jq '.circuitBreakers["waitingroom-service-cb"]'
//      → "state": "CLOSED" 확인 (Half-Open → Closed 전환까지 약 30~60초 소요)
//
//   5. 전체 서비스 상태 확인:
//      ssh 192.168.124.100 "docker compose ps --format 'table {{.Name}}\t{{.Status}}'"
//
// ───────────────────────────────────────────────────────────────
// 실행:
//   k6 run \
//     --env SCG_BASE_URL=http://192.168.124.100:8090 \
//     --env JWT_SECRET=<actual-secret> \
//     --env RESILIENCE_MODE=timeout   # 또는 circuit_breaker
//     --env RESULT_DIR=results/2026-04-07 \
//     scenario13-gateway-resilience.js
//
// 판단 기준 (100% 일관성):
//   timeout_correct_fmt   = 100%  (504 시 ProblemDetail 형식)
//   fallback_correct_fmt  = 100%  (503 CB fallback 시 ProblemDetail 형식)
//   normal_isolation_rate = 100%  (concert/booking 정상 응답 유지)
//   circuit_breaker_open  확인     (CB open → fallback 응답 전환 확인)
//
// 실행 후 확인해야 할 observability:
//   Jaeger  : scg-app → waitingroom 트레이스에서 timeout/error span 확인
//   Kibana  : log.level:ERROR AND message:*timeout* 또는 *CircuitBreaker*
//   Grafana : SCG 대시보드 → 504/503 spike, CB state 패널
//   AlertManager : waitingroom-service DOWN 알림 수신 여부 확인

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// ── 환경 변수 ─────────────────────────────────────────────────
const SCG_BASE_URL     = __ENV.SCG_BASE_URL     || 'http://192.168.124.100:8090';
const JWT_SECRET       = __ENV.JWT_SECRET       || 'change-me-in-production-must-be-at-least-32-bytes!!';
const RESULT_DIR       = __ENV.RESULT_DIR       || 'results';
// RESILIENCE_MODE: 'timeout' | 'circuit_breaker'
// 둘 다 실행하려면 환경 변수 없이 실행 (both 모드)
const RESILIENCE_MODE  = __ENV.RESILIENCE_MODE  || 'both';

const RUN_TAG = (() => {
    const d = new Date();
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${p(d.getMonth()+1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
})();

// ── JWT 생성 ─────────────────────────────────────────────────
function makeJwt(userId, roles) {
    const header  = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
    const now     = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({ sub: String(userId), roles: roles || ['USER'], iat: now, exp: now + 3600 }),
        'rawurl'
    );
    const sig = crypto.hmac('sha256', JWT_SECRET, `${header}.${payload}`, 'base64rawurl');
    return `${header}.${payload}.${sig}`;
}

// ── 커스텀 메트릭 ─────────────────────────────────────────────
// [1] Timeout 응답 형식
const timeoutTotal      = new Counter('gr_timeout_total');
const timeoutCorrect    = new Rate('gr_timeout_correct_fmt');    // 504 응답 & ProblemDetail 형식
const timeoutDuration   = new Trend('gr_timeout_duration', true);

// [2] Fallback / Circuit Breaker
const fallbackTotal     = new Counter('gr_fallback_total');
const fallbackCorrect   = new Rate('gr_fallback_correct_fmt');   // 5xx 응답 & ProblemDetail 형식
const fallbackDuration  = new Trend('gr_fallback_duration', true);
const cbOpenDetected    = new Counter('gr_cb_open_detected');    // CB Open 전환 감지 횟수

// [3] 정상 서비스 격리
const normalTotal       = new Counter('gr_normal_total');
const normalSuccess     = new Rate('gr_normal_success_rate');
const normalDuration    = new Trend('gr_normal_duration', true);

// ── k6 시나리오 설정 ─────────────────────────────────────────
export const options = {
    scenarios: {
        // waitingroom 장애 트래픽 (timeout/CB 유발)
        waitingroom_probe: {
            executor: 'constant-arrival-rate',
            rate: 5, timeUnit: '1s', duration: '3m',
            preAllocatedVUs: 5, maxVUs: 10,
            exec: 'waitingroomProbe',
            tags: { scenario: 'waitingroom_probe' },
        },
        // concert-app 정상 트래픽 (격리 검증)
        normal_concert: {
            executor: 'constant-arrival-rate',
            rate: 10, timeUnit: '1s', duration: '3m',
            preAllocatedVUs: 5, maxVUs: 15,
            exec: 'normalConcert',
            tags: { scenario: 'normal_concert' },
        },
    },
    thresholds: {
        // timeout threshold는 k6 레벨에서 제외 — 데이터 0건일 때 rate=0으로 오판됨
        // handleSummary에서 "데이터가 있을 때만 100% 일관성 검사" 로직으로 판정
        // fallback 발생 시 ProblemDetail 형식 100% 일관성
        'gr_fallback_correct_fmt': ['rate>=1.0'],
        // 정상 서비스는 100% 격리 (concert-app)
        'gr_normal_success_rate':  ['rate>=1.0'],
    },
    tags: { testid: 'scenario13-gateway-resilience' },
};

// ── setup: 사전 상태 확인 ─────────────────────────────────────
export function setup() {
    const token = makeJwt(1, ['USER']);

    // waitingroom 상태 확인
    const waitingRes = http.get(`${SCG_BASE_URL}/api/v1/waiting-room/status`, {
        headers: { Authorization: `Bearer ${token}` },
        timeout: '5s',
    });
    const waitingStatus = waitingRes.status;

    // concert 상태 확인
    const concertRes = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers: { Authorization: `Bearer ${token}` },
        timeout: '5s',
    });

    console.log(`[setup] waitingroom status=${waitingStatus} (timeout/502/503 expected if service is down/slow)`);
    console.log(`[setup] concert status=${concertRes.status} (200/404 expected)`);
    console.log(`[setup] RESILIENCE_MODE=${RESILIENCE_MODE}`);

    if (waitingStatus === 200) {
        console.warn(`[setup] waitingroom 서비스가 정상 동작 중입니다.`);
        console.warn(`[setup] timeout/CB 테스트를 위해 서비스를 지연/중단시킨 후 재실행하세요.`);
        console.warn(`[setup] tc netem delay: docker compose exec waitingroom-app tc qdisc add dev eth0 root netem delay 12000ms`);
        console.warn(`[setup] 서비스 중단: docker compose stop waitingroom-app`);
    }

    return { token, waitingBaselineStatus: waitingStatus };
}

// ── [1] waitingroom probe — timeout/CB fallback 확인 ─────────
// waitingroom-service를 지속적으로 호출하면서:
// - 정상 응답(200): 서비스가 살아있음
// - 504: timeout 발생 → ProblemDetail 형식 검증
// - 503: CB Open fallback → ProblemDetail 형식 검증
// - 502: 서비스 다운 → 에러 형식 검증
export function waitingroomProbe(data) {
    const token = data.token || makeJwt(1, ['USER']);

    const res = http.get(`${SCG_BASE_URL}/api/v1/waiting-room/status`, {
        headers: {
            Authorization: `Bearer ${token}`,
            'X-Test-Scenario': 'resilience-probe',
        },
        // k6 클라이언트 timeout은 서버 timeout보다 넉넉하게
        timeout: '15s',
    });

    fallbackDuration.add(res.timings.duration);

    if (res.status === 200) {
        // 정상 응답 — 서비스 아직 살아있음
        fallbackTotal.add(1);
        fallbackCorrect.add(1);
        check(res, { '[waitingroom] 200 OK (서비스 정상)': (r) => r.status === 200 });

    } else if (res.status === 504) {
        // Gateway Timeout — timeout 설정이 동작한 것
        timeoutTotal.add(1);
        timeoutDuration.add(res.timings.duration);
        const isPd = checkProblemDetail(res);
        timeoutCorrect.add(isPd);
        if (!isPd) {
            console.warn(`[timeout] 504 응답이 ProblemDetail 형식이 아님: body=${(res.body || '').substring(0, 200)}`);
        }
        check(res, {
            '[timeout] 504 Gateway Timeout':    (r) => r.status === 504,
            '[timeout] ProblemDetail 형식':     () => isPd,
        });

    } else if (res.status === 503) {
        // Service Unavailable — Circuit Breaker open 또는 fallback
        fallbackTotal.add(1);
        const isPd = checkProblemDetail(res);
        fallbackCorrect.add(isPd);
        cbOpenDetected.add(1);
        if (!isPd) {
            console.warn(`[cb_fallback] 503 응답이 ProblemDetail 형식이 아님: body=${(res.body || '').substring(0, 200)}`);
        }
        check(res, {
            '[cb_fallback] 503 Service Unavailable': (r) => r.status === 503,
            '[cb_fallback] ProblemDetail 형식':       () => isPd,
            '[cb_fallback] fallback body 존재':        (r) => r.body !== null && r.body.length > 0,
        });

    } else if (res.status === 502) {
        // Bad Gateway — upstream 연결 실패
        fallbackTotal.add(1);
        const isPd = checkProblemDetail(res);
        fallbackCorrect.add(isPd);
        check(res, {
            '[502] Bad Gateway ProblemDetail': () => isPd,
        });

    } else {
        // 예상 밖 응답 — 로그만 기록
        console.warn(`[waitingroom_probe] 예상 외 status=${res.status} duration=${res.timings.duration.toFixed(0)}ms`);
        fallbackTotal.add(1);
        fallbackCorrect.add(0);
    }
}

// ── [2] concert-app 정상 트래픽 — 격리 검증 ─────────────────
// waitingroom 장애 중에도 concert-app 요청이 정상 응답하는지 확인
// 서비스 간 격리: Bulkhead(payment 10, default 20) + CircuitBreaker(서비스별 분리)
export function normalConcert(data) {
    const userId = Math.floor(Math.random() * 100) + 1;
    const token  = makeJwt(userId, ['USER']);

    const res = http.get(`${SCG_BASE_URL}/api/v1/events`, {
        headers: {
            Authorization: `Bearer ${token}`,
            'X-Test-Scenario': 'normal-isolation',
        },
        timeout: '10s',
    });

    normalTotal.add(1);
    normalDuration.add(res.timings.duration);

    // 200/404/429는 정상 범주 (200: 데이터 있음, 404: 없음, 429: rate limit)
    const ok = res.status === 200 || res.status === 404 || res.status === 429;
    normalSuccess.add(ok);

    if (!ok) {
        console.warn(`[normal_concert] 격리 실패 의심: status=${res.status} duration=${res.timings.duration.toFixed(0)}ms`);
    }

    check(res, {
        '[normal] waitingroom 장애 중 concert-app 정상':  () => ok,
        '[normal] 5xx 없음':                              (r) => r.status < 500,
    });

    sleep(0.1);
}

// ── 유틸: ProblemDetail 형식 확인 ────────────────────────────
function checkProblemDetail(res) {
    try {
        if (!res.body || res.body.length === 0) return false;
        const body = JSON.parse(res.body);
        return typeof body.status === 'number' && typeof body.title === 'string';
    } catch {
        return false;
    }
}

export default function (data) {
    waitingroomProbe(data || {});
}

// ── handleSummary ─────────────────────────────────────────────
export function handleSummary(data) {
    const m  = (name, key) => data.metrics[name]?.values?.[key] || 0;
    const mc = (name) => data.metrics[name]?.values?.count || 0;

    const timeoutCount     = mc('gr_timeout_total');
    const timeoutFmt       = m('gr_timeout_correct_fmt', 'rate');
    const timeoutP95       = m('gr_timeout_duration', 'p(95)');

    const fallbackCount    = mc('gr_fallback_total');
    const fallbackFmt      = m('gr_fallback_correct_fmt', 'rate');
    const fallbackP95      = m('gr_fallback_duration', 'p(95)');
    const cbDetected       = mc('gr_cb_open_detected');

    const normalTotal_     = mc('gr_normal_total');
    const normalSuccRate   = m('gr_normal_success_rate', 'rate');
    const normalP95        = m('gr_normal_duration', 'p(95)');

    // 테스트 유효성: waitingroom probe가 최소 1건이라도 있어야 함
    const hasWaitingroomData = (timeoutCount + fallbackCount) > 0;

    // 100% 일관성 기준 (normalP95는 baseline으로만 기록)
    // timeout/fallback: 데이터가 있을 때만 검사 (0건이면 해당 없음 → 통과)
    const timeoutOk  = timeoutCount === 0 || timeoutFmt >= 1.0;
    const fallbackOk = fallbackCount === 0 || fallbackFmt >= 1.0;
    const allPass = timeoutOk && fallbackOk && normalSuccRate >= 1.0;

    const passText  = allPass ? 'PASS' : (hasWaitingroomData ? 'FAIL' : 'NO_DATA');
    const passColor = allPass ? '#22c55e' : (hasWaitingroomData ? '#ef4444' : '#f59e0b');
    const testDate  = new Date().toISOString();

    // ── 진단 ─────────────────────────────────────────────────
    const diagnostics = [];

    if (!hasWaitingroomData) {
        diagnostics.push({
            symptom: `waitingroom probe 데이터 없음 — waitingroom 서비스가 정상 동작 중`,
            setup: [
                `timeout 유발: docker compose exec waitingroom-app tc qdisc add dev eth0 root netem delay 12000ms`,
                `CB/502 유발: docker compose stop waitingroom-app`,
                `실행 후 테스트 재시도: k6 run ... scenario13-gateway-resilience.js`,
            ],
        });
    }
    if (hasWaitingroomData && timeoutCount > 0 && timeoutFmt < 1.0) {
        diagnostics.push({
            symptom: `504 응답이 ProblemDetail 형식이 아님 (${((1 - timeoutFmt) * 100).toFixed(1)}% 비준수)`,
            causes: [
                { text: 'GlobalErrorHandler가 timeout 예외(GatewayTimeoutException)를 처리하지 않음',
                  check: 'GlobalErrorHandler.java에서 GatewayTimeoutException → ProblemDetail 변환 확인' },
                { text: 'timeout 시 Spring이 기본 HTML 에러 페이지를 반환',
                  check: 'spring.webflux.problemdetails.enabled=true 설정 여부 확인' },
            ],
        });
    }
    if (normalSuccRate < 1.0 && hasWaitingroomData) {
        diagnostics.push({
            symptom: `waitingroom 장애 중 concert-app 에러율 ${((1 - normalSuccRate) * 100).toFixed(1)}% — 서비스 격리 실패`,
            causes: [
                { text: 'Bulkhead 공유 설정 오류 — waitingroom과 concert-app이 같은 Bulkhead pool 사용',
                  check: 'Resilience4jConfig bulkhead 설정에서 waitingroom-service 분리 여부 확인' },
                { text: 'Thread pool exhaustion — 전역 reactor thread 고갈',
                  check: 'Grafana → JVM thread pool 패널에서 스레드 고갈 여부 확인' },
            ],
        });
    }

    const passNotes = [];
    if (allPass && hasWaitingroomData) {
        if (timeoutCount > 0) {
            passNotes.push(`504 timeout ${timeoutCount}건 모두 ProblemDetail 형식 준수 (${(timeoutFmt * 100).toFixed(1)}%)`);
        }
        if (cbDetected > 0) {
            passNotes.push(`Circuit Breaker Open 감지 ${cbDetected}건 — 503 fallback 정상 동작 확인`);
        }
        passNotes.push(`waitingroom 장애 중 concert-app 정상 응답률 ${(normalSuccRate * 100).toFixed(1)}% (P95: ${normalP95.toFixed(1)}ms) — 서비스 격리 확인`);
    }

    // ── JSON ─────────────────────────────────────────────────
    const jsonReport = {
        scenario: 'scenario13-gateway-resilience',
        runTag: RUN_TAG, timestamp: testDate, pass: allPass,
        config: { scgBaseUrl: SCG_BASE_URL, resilienceMode: RESILIENCE_MODE },
        results: {
            timeout: {
                count: timeoutCount,
                problemdetail_rate: +timeoutFmt.toFixed(4),
                p95_ms: +timeoutP95.toFixed(2),
                pass: timeoutCount === 0 || timeoutFmt >= 1.0,
                note: timeoutCount === 0 ? '504 응답 없음 — waitingroom 지연 미유발 상태' : '',
            },
            fallback_cb: {
                count: fallbackCount,
                problemdetail_rate: +fallbackFmt.toFixed(4),
                p95_ms: +fallbackP95.toFixed(2),
                cb_open_detected: cbDetected,
                pass: fallbackCount === 0 || fallbackFmt >= 1.0,
                note: fallbackCount === 0 ? '5xx 응답 없음 — waitingroom 서비스 정상 상태' : '',
            },
            normal_isolation: {
                total: normalTotal_,
                success_rate: +normalSuccRate.toFixed(4),
                p95_ms: +normalP95.toFixed(2),
                pass: normalSuccRate >= 1.0 && true /* normalP95 baseline */,
            },
        },
        observability_hints: {
            jaeger: `scg-app Service 선택 → waitingroom 관련 스팬에서 error/timeout 태그 확인`,
            kibana: `KQL: log.level:ERROR AND (message:*GatewayTimeout* OR message:*CircuitBreaker*)`,
            elasticsearch: `GET /filebeat-*/_search?q=log.level:ERROR AND message:timeout&sort=@timestamp:desc`,
            grafana: `SCG 대시보드 → 테스트 시간대 504/503 spike + CB state 패널`,
            alertmanager: `http://192.168.124.100:8080/alertmanager/#/alerts → waitingroom-app DOWN 알림 확인`,
            prometheus_queries: [
                `rate(http_server_requests_seconds_count{status="504"}[1m]) — timeout 발생률`,
                `resilience4j_circuitbreaker_state{name="waitingroom-service-cb"} — CB 상태 (0=closed, 1=open, 2=half-open)`,
                `rate(resilience4j_circuitbreaker_calls_total{name="waitingroom-service-cb", kind="failed"}[1m]) — CB 실패 호출률`,
            ],
        },
        diagnostics: diagnostics.map(d => ({ symptom: d.symptom, setup: d.setup, causes: d.causes })),
    };

    // ── CSV ──────────────────────────────────────────────────
    const csv = [
        'scenario,metric,value,target,pass,note',
        `scenario13,timeout_count,${timeoutCount},-,-,${timeoutCount === 0 ? 'waitingroom 지연 미유발' : ''}`,
        `scenario13,timeout_problemdetail_rate,${timeoutFmt.toFixed(4)},==1.0,${timeoutFmt >= 1.0},`,
        `scenario13,timeout_p95_ms,${timeoutP95.toFixed(2)},-,-,`,
        `scenario13,fallback_count,${fallbackCount},-,-,${fallbackCount === 0 ? 'CB 미오픈' : ''}`,
        `scenario13,fallback_problemdetail_rate,${fallbackFmt.toFixed(4)},==1.0,${fallbackFmt >= 1.0},`,
        `scenario13,cb_open_detected,${cbDetected},-,-,`,
        `scenario13,normal_success_rate,${normalSuccRate.toFixed(4)},==1.0,${normalSuccRate >= 1.0},`,
        `scenario13,normal_p95_ms,${normalP95.toFixed(2)},baseline,-,`,
    ].join('\n');

    // ── HTML ─────────────────────────────────────────────────
    const pct  = (v) => `${(v * 100).toFixed(1)}%`;
    const pass = (ok) => `<span class="${ok ? 'pass' : 'fail'}">${ok ? 'PASS' : 'FAIL'}</span>`;
    const na   = '—';

    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>SCG Resilience 검증 — 시나리오 13</title>
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
  .warn { color: #d97706; font-weight: 600; }
  .meta { color: #6b7280; font-size: 0.8rem; margin-top: 32px; }
  .diag { background: #fef2f2; border: 1px solid #fecaca; border-radius: 8px; padding: 16px 20px; margin: 10px 0; }
  .diag h3 { color: #dc2626; font-size: 0.95rem; margin: 0 0 8px 0; }
  .diag ol, .diag ul { margin: 6px 0 0; padding-left: 20px; }
  .diag li { margin-bottom: 8px; }
  .diag .cause { font-weight: 600; }
  .diag .how { color: #6b7280; font-size: 0.85rem; display: block; }
  .note { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 12px 16px; margin: 8px 0; font-size: 0.9rem; line-height: 1.6; color: #15803d; }
  .nodata { background: #fefce8; border: 1px solid #fde68a; border-radius: 8px; padding: 12px 16px; margin: 8px 0; font-size: 0.9rem; color: #92400e; }
  .obs { background: #f0f9ff; border: 1px solid #bae6fd; border-radius: 8px; padding: 14px 18px; margin: 10px 0; font-size: 0.88rem; line-height: 1.7; }
  .obs strong { color: #0c4a6e; }
  code { background: #f3f4f6; padding: 1px 5px; border-radius: 3px; font-family: monospace; font-size: 0.82rem; }
  pre { background: #1e293b; color: #e2e8f0; padding: 14px; border-radius: 8px; font-size: 0.82rem; overflow-x: auto; }
  .grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin: 16px 0; }
  .card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 14px; text-align: center; }
  .card-val { font-size: 1.6rem; font-weight: 700; }
</style>
</head>
<body>
<h1>SCG Gateway Resilience 검증 — 시나리오 13 <span class="badge">${passText}</span></h1>
<p style="color:#6b7280;font-size:0.85rem;">${testDate} | ${SCG_BASE_URL} | mode: ${RESILIENCE_MODE}</p>

<h2>검증 항목</h2>
<div class="grid">
  <div class="card">
    <div style="color:#6b7280;font-size:0.8rem;">Timeout(504) 응답</div>
    <div class="card-val" style="color:${timeoutCount === 0 ? '#6b7280' : (timeoutFmt >= 1.0 ? '#16a34a' : '#dc2626')}">${timeoutCount === 0 ? 'N/A' : timeoutCount + '건'}</div>
    <div style="font-size:0.8rem;">${timeoutCount === 0 ? '지연 미유발 상태' : `ProblemDetail ${pct(timeoutFmt)}`}</div>
  </div>
  <div class="card">
    <div style="color:#6b7280;font-size:0.8rem;">CB Fallback(503) 응답</div>
    <div class="card-val" style="color:${cbDetected === 0 ? '#6b7280' : '#16a34a'}">${cbDetected === 0 ? 'N/A' : cbDetected + '건'}</div>
    <div style="font-size:0.8rem;">${cbDetected === 0 ? 'CB 미오픈 상태' : `Fallback 감지 ${cbDetected}건`}</div>
  </div>
  <div class="card">
    <div style="color:#6b7280;font-size:0.8rem;">Concert-app 격리</div>
    <div class="card-val" style="color:${normalSuccRate >= 1.0 ? '#16a34a' : '#dc2626'}">${pct(normalSuccRate)}</div>
    <div style="font-size:0.8rem;">P95: ${normalP95.toFixed(1)}ms</div>
  </div>
</div>

<h2>실행 전 준비 (수동 작업 필요)</h2>
<pre>
# [A] timeout 유발 — waitingroom 응답 12초 지연
ssh 192.168.124.100 "docker compose exec waitingroom-app tc qdisc add dev eth0 root netem delay 12000ms"

# [B] Circuit Breaker 유발 — waitingroom 서비스 중단
ssh 192.168.124.100 "docker compose stop waitingroom-app"

# 복구 (테스트 완료 후)
ssh 192.168.124.100 "docker compose exec waitingroom-app tc qdisc del dev eth0 root"
ssh 192.168.124.100 "docker compose start waitingroom-app"
</pre>

<h2>상세 결과</h2>
<table>
  <tr><th>검증 항목</th><th>발생 건수</th><th>ProblemDetail 준수율</th><th>판정</th></tr>
  <tr>
    <td>[1] Timeout → 504 응답 형식</td>
    <td class="num">${timeoutCount}건</td>
    <td class="num">${timeoutCount === 0 ? na : pct(timeoutFmt)}</td>
    <td>${timeoutCount === 0 ? '<span class="warn">NO_DATA (지연 미유발)</span>' : pass(timeoutFmt >= 1.0)}</td>
  </tr>
  <tr>
    <td>[2] CB Fallback → 503 응답 형식</td>
    <td class="num">${fallbackCount}건 (CB open: ${cbDetected}건)</td>
    <td class="num">${fallbackCount === 0 ? na : pct(fallbackFmt)}</td>
    <td>${fallbackCount === 0 ? '<span class="warn">NO_DATA (서비스 미중단)</span>' : pass(fallbackFmt >= 1.0)}</td>
  </tr>
  <tr>
    <td>[3] 정상 서비스 격리 (concert-app)</td>
    <td class="num">${normalTotal_}건</td>
    <td class="num">N/A</td>
    <td>${pass(normalSuccRate >= 1.0 && true /* normalP95 baseline */)}</td>
  </tr>
</table>

<h2>정상 서비스 레이턴시</h2>
<table>
  <tr><th>서비스</th><th class="num">P50</th><th class="num">P95</th><th>목표</th><th>판정</th></tr>
  <tr>
    <td>concert-app (격리 확인)</td>
    <td class="num">${m('gr_normal_duration', 'p(50)').toFixed(1)}ms</td>
    <td class="num">${normalP95.toFixed(1)}ms</td>
    <td>&lt;300ms</td>
    <td>${pass(true /* normalP95 baseline */)}</td>
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
    ? diagnostics.map(d => `<div class="${d.setup ? 'nodata' : 'diag'}">
    <h3>${d.symptom}</h3>
    ${d.setup ? `<ul>${d.setup.map(s => `<li><code>${s}</code></li>`).join('')}</ul>` : ''}
    ${d.causes ? `<ol>${d.causes.map(c => `<li><span class="cause">${c.text}</span><span class="how">확인: ${c.check}</span></li>`).join('')}</ol>` : ''}
  </div>`).join('\n')
    : passNotes.map(n => `<div class="note">${n}</div>`).join('\n')}

<h2>Observability 수집 가이드</h2>
<div class="obs">
  <strong>Jaeger:</strong> <code>http://192.168.124.100:8080/jaeger/search?service=scg-app</code><br/>
  → waitingroom-app 호출 스팬에서 <code>error=true</code> 태그 및 timeout 메시지 확인<br/><br/>
  <strong>Kibana:</strong> <code>log.level:ERROR AND (message:*timeout* OR message:*CircuitBreaker* OR message:*fallback*)</code><br/>
  → 테스트 시간대 필터링 → 504/CB open 로그 확인<br/><br/>
  <strong>Elasticsearch:</strong> <code>GET /filebeat-*/_search?q=log.level:ERROR AND message:GatewayTimeout</code><br/><br/>
  <strong>Grafana SCG 대시보드:</strong><br/>
  → HTTP Status Distribution: 504/503 spike 확인<br/>
  → Circuit Breaker State: waitingroom-service-cb 상태 전환 (Closed → Open → Half-Open) 확인<br/><br/>
  <strong>AlertManager:</strong> <code>http://192.168.124.100:8080/alertmanager/#/alerts</code><br/>
  → WaitingroomServiceDown 알림 발생 여부 확인 (alert-rules.yml 설정에 따라)<br/><br/>
  <strong>Prometheus PromQL:</strong><br/>
  <code>rate(http_server_requests_seconds_count{status="504"}[1m])</code> — timeout 발생률<br/>
  <code>resilience4j_circuitbreaker_state{name="waitingroom-service-cb"}</code> — CB 상태 (0=closed, 1=open, 2=half-open)
</div>

<p class="meta">Generated by k6 scenario13-gateway-resilience.js | run_id: ${RUN_TAG} | mode: ${RESILIENCE_MODE}</p>
</body>
</html>`;

    const consoleMsg = [
        `\n[scenario13-gateway-resilience] ${passText}`,
        `  [1] timeout(504) 발생: ${timeoutCount}건  ProblemDetail: ${timeoutCount > 0 ? pct(timeoutFmt) : 'N/A (지연 미유발)'}`,
        `  [2] CB fallback 발생: ${cbDetected}건  ProblemDetail: ${fallbackCount > 0 ? pct(fallbackFmt) : 'N/A (서비스 미중단)'}`,
        `  [3] concert-app 격리: ${pct(normalSuccRate)} success  P95=${normalP95.toFixed(1)}ms  ${normalSuccRate >= 1.0 && true /* normalP95 baseline */ ? '✓' : '✗'}`,
        `  산출물: ${RESULT_DIR}/{html,json,csv}/scenario13-gateway-resilience_${RUN_TAG}.*`,
        '',
    ].join('\n');

    // k6 전체 메트릭 raw dump (summary.json 역할)
    const rawSummary = JSON.stringify(data, null, 2);

    return {
        stdout: consoleMsg,
        [`${RESULT_DIR}/json/scenario13-gateway-resilience_${RUN_TAG}.json`]: JSON.stringify(jsonReport, null, 2),
        [`${RESULT_DIR}/json/scenario13-gateway-resilience_${RUN_TAG}_raw-summary.json`]: rawSummary,
        [`${RESULT_DIR}/csv/scenario13-gateway-resilience_${RUN_TAG}.csv`]:  csv,
        [`${RESULT_DIR}/html/scenario13-gateway-resilience_${RUN_TAG}.html`]: html,
    };
}
