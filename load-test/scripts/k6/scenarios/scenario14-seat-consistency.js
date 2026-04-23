// ─────────────────────────────────────────────────────────────────────────────
// Scenario 14: 좌석/예약 정합성 검증
//
// 목적:
//   동일 좌석(TARGET_SEAT_ID)에 N개 동시 예약 요청이 들어올 때,
//   DB 불변식이 최종적으로 깨지지 않음을 증명한다.
//   "분산락이 작동할 것이다"가 아닌, 테스트 종료 후 DB 상태로 직접 증명한다.
//
// 검증 범위:
//   이 스크립트는 HTTP 트래픽 생성 + k6 레벨 5xx 판정까지만 담당.
//   핵심 PASS/FAIL 판정은 테스트 종료 후 아래 SQL로 직접 수행한다.
//
//   [불변식 1] ticketing_booking.reservations: seat_id = {TARGET_SEAT_ID} → count <= 1
//   [불변식 2] ticketing_concert.seats:        seat_id = {TARGET_SEAT_ID} → status = 'HOLD', version = 1
//   [불변식 3] k6 내: 5xx count == 0
//
// PASS/FAIL 기준:
//   PASS: 불변식 1~3 모두 충족
//   FAIL: 불변식 1 위반(oversell) 또는 불변식 2 위반(version > 1) 또는 불변식 3 위반(5xx)
//
// 응답 코드 분포 (참고 수치 — 판정 기준 아님):
//   201 Created     → 분산락 획득 + 낙관적락 통과 → 예약 생성 (기대: 1건)
//   429 Too Many    → 분산락 대기 타임아웃 (5s) 초과 → RESERVATION_LOCK_CONFLICT(R003)
//   409 Conflict    → 분산락 통과 후 낙관적락 충돌  → SEAT_ALREADY_HELD(S001)
//   503             → SCG Bulkhead 초과 시 거절 (booking-service maxConcurrentCalls=20)
//
// 동시성 제어 구조 (실제 코드 기준):
//   1단계: Redisson 분산락 (reservation:lock:seat:{seatId}, wait=5s, lease=15s)
//          → 동일 seatId 요청 직렬화. 타임아웃 시 429 반환.
//   2단계: 낙관적락 @Version (concert-app SeatEntity)
//          → 분산락 통과 후에도 DB 레벨 race condition 방어. 충돌 시 409 반환.
//
// 테스트 전 필수 작업:
//   1. SQL_INIT.sql 실행 → TARGET_SEAT_ID 좌석을 AVAILABLE/version=0으로 초기화
//   2. 대기열 토큰 사전 주입 (아래 setup() 주석의 SQL 참고)
//
// 테스트 후 필수 작업:
//   1. SQL_VERIFY.sql 실행 → 불변식 검증
//   2. 필요 시 SQL_CLEANUP.sql 실행 → 테스트 데이터 정리
//
// 실행:
//   k6 run \
//     --env SCG_BASE_URL=http://192.168.124.100:8090 \
//     --env JWT_SECRET=<secret> \
//     --env TARGET_SEAT_ID=<실제 seat_id> \
//     --env CONTENTION_VUS=20 \
//     load-test/scripts/k6/scenarios/scenario14-seat-consistency.js
// ─────────────────────────────────────────────────────────────────────────────

import http      from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import crypto    from 'k6/crypto';
import encoding  from 'k6/encoding';

// ── 환경 변수 ────────────────────────────────────────────────────────────────
const SCG_BASE_URL    = __ENV.SCG_BASE_URL    || 'http://192.168.124.100:8090';
const JWT_SECRET      = __ENV.JWT_SECRET      || 'change-me-in-production-must-be-at-least-32-bytes!!';
const TARGET_SEAT_ID  = parseInt(__ENV.TARGET_SEAT_ID  || '11');   // ticketing_concert.seats.seat_id
const TARGET_EVENT_ID = parseInt(__ENV.TARGET_EVENT_ID || '1');
const CONTENTION_VUS  = parseInt(__ENV.CONTENTION_VUS  || '20');
const RESULT_DIR      = __ENV.RESULT_DIR      || 'results';

const RUN_TAG = (() => {
    const d = new Date();
    const p = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${p(d.getMonth()+1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}${p(d.getSeconds())}`;
})();

// ── JWT 생성 (scg-app JwtAuthenticationFilter 규약) ──────────────────────────
function makeJwt(userId) {
    const header  = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
    const now     = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({ sub: String(userId), roles: ['USER'], iat: now, exp: now + 7200 }),
        'rawurl'
    );
    const sig = crypto.hmac('sha256', JWT_SECRET, `${header}.${payload}`, 'base64rawurl');
    return `${header}.${payload}.${sig}`;
}

// ── Auth-Passport 생성 (SCG JwtAuthenticationFilter가 downstream으로 주입하는 형식) ─
// ADR: booking-app은 Auth-Passport 헤더에서 userId를 추출한다.
//      SCG를 경유하면 SCG가 생성하지만, k6에서 SCG를 경유하면 SCG가 덮어씌운다.
//      따라서 k6에서 직접 생성한 값은 SCG가 재생성하므로 내용이 달라져도 무방.
function makePassport(userId) {
    return encoding.b64encode(
        JSON.stringify({ userId: String(userId), roles: ['USER'], jti: null,
                         issuedAt: Math.floor(Date.now() / 1000), clientIp: '127.0.0.1' }),
        'rawurl'
    );
}

// ── 대기열 토큰 패턴 (사전 주입 방식) ────────────────────────────────────────
// ADR: booking-app의 WaitingRoomInternalClientImpl.validateToken()은 waitingroom-app DB의
//      active_tokens 테이블에서 tokenId 존재 여부와 ACTIVE 상태를 검증한다.
//      따라서 k6 setup() 시점에 API로 발급받거나 DB에 사전 주입이 필요하다.
//
//      이 시나리오는 동시성 제어 격리가 목적이므로 대기열 의존성을 제거하기 위해
//      scenario8과 동일한 사전 주입 패턴을 사용한다:
//        tokenId 패턴: '{userId:08d}-0000-4000-8000-{userId:012d}'
//        예) userId=5 → '00000005-0000-4000-8000-000000000005'
//
//      사전 주입 SQL (테스트 실행 전 ticketing_waitingroom DB에서 실행):
//        INSERT INTO active_tokens (token_id, user_id, event_id, status, issued_at, expired_at)
//        SELECT
//          CONCAT(LPAD(n, 8, '0'), '-0000-4000-8000-', LPAD(n, 12, '0')),
//          n, {TARGET_EVENT_ID}, 'ACTIVE', NOW(), DATE_ADD(NOW(), INTERVAL 3 HOUR)
//        FROM (
//          WITH RECURSIVE nums AS (SELECT 1 AS n UNION ALL SELECT n+1 FROM nums WHERE n < {CONTENTION_VUS})
//          SELECT n FROM nums
//        ) t
//        ON DUPLICATE KEY UPDATE status='ACTIVE', expired_at=DATE_ADD(NOW(), INTERVAL 3 HOUR);
function makeQueueToken(userId) {
    const pad8  = (n) => String(n).padStart(8,  '0');
    const pad12 = (n) => String(n).padStart(12, '0');
    return `${pad8(userId)}-0000-4000-8000-${pad12(userId)}`;
}

// ── 커스텀 메트릭 ─────────────────────────────────────────────────────────────
// 응답 코드 분포 (참고 수치)
const cntSuccess    = new Counter('s14_cnt_success');   // 201 예약 생성 성공
const cntLockFail   = new Counter('s14_cnt_lock_fail'); // 429 분산락 타임아웃
const cntConflict   = new Counter('s14_cnt_conflict');  // 409 낙관적락 충돌
const cntBulkhead   = new Counter('s14_cnt_bulkhead');  // 503 Bulkhead 거절
const cntServerErr  = new Counter('s14_cnt_5xx');       // 5xx 서버 에러
const cntOther      = new Counter('s14_cnt_other');     // 기타

// 레이턴시 (참고 수치)
const durAll        = new Trend('s14_dur_all',     true); // 전체 경합 요청
const durSuccess    = new Trend('s14_dur_success', true); // 성공한 1건
const durFail       = new Trend('s14_dur_fail',    true); // 실패 요청들

// 에러율 (k6 threshold용 — 5xx만 에러로 계산)
const rate5xx       = new Rate('s14_rate_5xx');

// ── 테스트 옵션 ──────────────────────────────────────────────────────────────
export const options = {
    scenarios: {
        contention: {
            executor: 'shared-iterations',
            // ADR: 'shared-iterations'를 선택한 이유:
            //   per-vu-iterations(vus=20, iterations=1)과 달리 모든 VU가
            //   풀에서 iteration을 가져가므로 실제 동시 요청 경합이 더 잘 재현됨.
            //   20VU가 20건을 동시에 시도 → 동일 좌석 분산락 경합 극대화.
            vus:        CONTENTION_VUS,
            iterations: CONTENTION_VUS,
            maxDuration: '30s',
        },
    },
    thresholds: {
        // [판정] 5xx가 1건이라도 발생하면 FAIL — 인프라 레벨 에러
        // ADR: 429/409/503은 정상 보호 동작이므로 threshold에 포함하지 않음.
        //      중복 예약(oversell) 판정은 k6가 아닌 DB SQL로 수행한다.
        's14_rate_5xx': ['rate==0'],
    },
    tags: {
        testid:   'scenario14-seat-consistency',
        run_date: new Date().toISOString().slice(0, 10),
        run_id:   RUN_TAG,
    },
};

// ── setup: 사전 조건 확인 ────────────────────────────────────────────────────
export function setup() {
    console.log('\n' + '='.repeat(60));
    console.log('[Scenario 14] 좌석/예약 정합성 검증');
    console.log(`  TARGET_SEAT_ID  : ${TARGET_SEAT_ID}`);
    console.log(`  TARGET_EVENT_ID : ${TARGET_EVENT_ID}`);
    console.log(`  CONTENTION_VUS  : ${CONTENTION_VUS}`);
    console.log(`  분산락 키        : reservation:lock:seat:${TARGET_SEAT_ID}`);
    console.log(`  분산락 설정      : wait=5s, lease=15s`);
    console.log('='.repeat(60));

    // SCG 연결 확인
    const healthRes = http.get(`${SCG_BASE_URL}/actuator/health`, { timeout: '5s' });
    if (healthRes.status !== 200) {
        console.warn(`[setup] SCG 연결 확인 실패: status=${healthRes.status}`);
    } else {
        console.log(`[setup] SCG 연결 확인 OK`);
    }

    console.log('\n[setup] 전제 조건 확인 사항:');
    console.log(`  1. ticketing_concert.seats WHERE seat_id=${TARGET_SEAT_ID}`);
    console.log(`       → status='AVAILABLE', version=0 인지 확인 (SQL_INIT.sql 실행 필요)`);
    console.log(`  2. ticketing_waitingroom.active_tokens WHERE user_id IN (1..${CONTENTION_VUS})`);
    console.log(`       → status='ACTIVE' 토큰 ${CONTENTION_VUS}건 존재하는지 확인`);
    console.log('');

    return { runTag: RUN_TAG };
}

// ── 메인 시나리오: 동일 좌석 동시 예약 경합 ─────────────────────────────────
export default function contend(setupData) {
    // ADR: __VU는 1부터 시작하는 전역 ID. CONTENTION_VUS 범위 내로 modulo 매핑.
    //      같은 userId로 중복 매핑될 수 있지만, 이 시나리오의 목적은
    //      "동일 좌석"에 대한 동시성 제어 검증이므로 userId 중복은 무관하다.
    const userId     = ((__VU - 1) % CONTENTION_VUS) + 1;
    const jwt        = makeJwt(userId);
    const passport   = makePassport(userId);
    const queueToken = makeQueueToken(userId);

    const res = http.post(
        `${SCG_BASE_URL}/api/v1/reservations`,
        JSON.stringify({ seatId: TARGET_SEAT_ID }),
        {
            headers: {
                'Authorization': `Bearer ${jwt}`,
                'Auth-Passport':  passport,
                'Queue-Token':    queueToken,
                'Content-Type':   'application/json',
            },
            timeout: '20s',  // 분산락 wait(5s) + lease(15s) 커버
            tags: { phase: 'contention', seat: String(TARGET_SEAT_ID) },
        }
    );

    // ── 레이턴시 기록 ───────────────────────────────────────────
    durAll.add(res.timings.duration);

    // ── 응답 분류 (참고 수치 수집, 판정 기준 아님) ──────────────
    if (res.status === 201) {
        cntSuccess.add(1);
        durSuccess.add(res.timings.duration);
        rate5xx.add(0);
        console.log(`[VU${__VU}] 201 SUCCESS  seatId=${TARGET_SEAT_ID}  ${res.timings.duration.toFixed(0)}ms ← 락 획득 성공`);

    } else if (res.status === 429) {
        // 분산락 대기 타임아웃 (RESERVATION_LOCK_CONFLICT R003) — 정상 보호 동작
        cntLockFail.add(1);
        durFail.add(res.timings.duration);
        rate5xx.add(0);

    } else if (res.status === 409) {
        // 낙관적락 충돌 (SEAT_ALREADY_HELD S001) — 분산락 통과 후 DB 레벨 방어
        cntConflict.add(1);
        durFail.add(res.timings.duration);
        rate5xx.add(0);

    } else if (res.status === 503) {
        // SCG Bulkhead 거절 (booking-service maxConcurrentCalls=20) — 정상 보호 동작
        cntBulkhead.add(1);
        durFail.add(res.timings.duration);
        rate5xx.add(0);
        console.warn(`[VU${__VU}] 503 BULKHEAD  ${res.timings.duration.toFixed(0)}ms`);

    } else if (res.status >= 500) {
        // 5xx — 인프라 레벨 에러. FAIL 판정 트리거
        cntServerErr.add(1);
        rate5xx.add(1);
        console.error(`[VU${__VU}] ${res.status} SERVER_ERROR  body=${(res.body || '').substring(0, 200)}`);

    } else {
        // 기타 (400, 401 등)
        cntOther.add(1);
        rate5xx.add(0);
        console.warn(`[VU${__VU}] ${res.status} OTHER  body=${(res.body || '').substring(0, 200)}`);
    }

    // check는 참고용 — threshold와 분리
    check(res, {
        'response received': (r) => r.status > 0,
        'no 5xx':            (r) => r.status < 500,
    });
}

// ── handleSummary ────────────────────────────────────────────────────────────
export function handleSummary(data) {
    const m = (name, key) => data.metrics[name]?.values?.[key] ?? 0;

    // 응답 분포 (참고)
    const successCnt  = m('s14_cnt_success',  'count');
    const lockCnt     = m('s14_cnt_lock_fail','count');
    const conflictCnt = m('s14_cnt_conflict', 'count');
    const bulkheadCnt = m('s14_cnt_bulkhead', 'count');
    const errCnt      = m('s14_cnt_5xx',      'count');
    const otherCnt    = m('s14_cnt_other',     'count');
    const totalCnt    = successCnt + lockCnt + conflictCnt + bulkheadCnt + errCnt + otherCnt;

    // 레이턴시
    const p50All  = m('s14_dur_all',     'p(50)');
    const p95All  = m('s14_dur_all',     'p(95)');
    const p50Succ = m('s14_dur_success', 'p(50)');
    const p50Fail = m('s14_dur_fail',    'p(50)');

    // k6 레벨 판정 (5xx만)
    const k6Pass = errCnt === 0;

    // ── 콘솔 출력 ────────────────────────────────────────────────
    const sep = '─'.repeat(60);
    console.log('\n' + sep);
    console.log('[Scenario 14] 좌석/예약 정합성 검증 — 결과 요약');
    console.log(sep);
    console.log(`  run_id         : ${RUN_TAG}`);
    console.log(`  TARGET_SEAT_ID : ${TARGET_SEAT_ID}`);
    console.log(`  CONTENTION_VUS : ${CONTENTION_VUS}`);
    console.log('');
    console.log('  ▶ 응답 분포 (참고 수치 — PASS/FAIL 판정 기준 아님)');
    console.log(`    201 Success  : ${successCnt}건`);
    console.log(`    429 LockFail : ${lockCnt}건   (분산락 타임아웃, 정상 보호)`);
    console.log(`    409 Conflict : ${conflictCnt}건   (낙관적락 충돌, 정상 보호)`);
    console.log(`    503 Bulkhead : ${bulkheadCnt}건   (Bulkhead 거절, 정상 보호)`);
    console.log(`    5xx Error    : ${errCnt}건   ← 0이어야 PASS`);
    console.log(`    기타         : ${otherCnt}건`);
    console.log(`    합계         : ${totalCnt}건`);
    console.log('');
    console.log('  ▶ 레이턴시 (참고)');
    console.log(`    전체  P50=${p50All.toFixed(1)}ms  P95=${p95All.toFixed(1)}ms`);
    console.log(`    성공  P50=${p50Succ.toFixed(1)}ms`);
    console.log(`    실패  P50=${p50Fail.toFixed(1)}ms`);
    console.log('');
    console.log(`  ▶ k6 판정: ${k6Pass ? '✅ PASS (5xx=0)' : '❌ FAIL (5xx 발생)'}`);
    console.log('');
    console.log('  ★ 핵심 PASS/FAIL 판정: 테스트 종료 후 아래 SQL 실행 필요');
    console.log(sep);
    console.log('  [불변식 1] 예약 row 중복 여부:');
    console.log(`    SELECT count(*), status`);
    console.log(`    FROM ticketing_booking.reservations`);
    console.log(`    WHERE seat_id = ${TARGET_SEAT_ID};`);
    console.log(`    → 기대: count <= 1, status = 'PENDING'`);
    console.log('');
    console.log('  [불변식 2] 좌석 상태/버전:');
    console.log(`    SELECT status, version`);
    console.log(`    FROM ticketing_concert.seats`);
    console.log(`    WHERE seat_id = ${TARGET_SEAT_ID};`);
    console.log(`    → 기대: status = 'HOLD', version = 1`);
    console.log('');
    console.log('  [불변식 3] k6 5xx = 0  →  ' + (k6Pass ? '✅ 이미 확인됨' : '❌ FAIL'));
    console.log(sep);
    console.log('  Observability 수집 포인트:');
    console.log('    [ES]  message: "*좌석 점유 시도*"  → 성공 1건 확인');
    console.log('    [ES]  message: "*동시성 충돌*"     → 낙관적락 충돌 건수');
    console.log('    [ES]  code: "R003"                → 분산락 타임아웃 건수');
    console.log('    [Jaeger] 성공 1건: booking-app → concert-app span 포함');
    console.log('    [Jaeger] 실패 2건: 락 차단 지점 span 확인');
    console.log(sep + '\n');

    // ── JSON 리포트 ──────────────────────────────────────────────
    const report = {
        scenario:  'scenario14-seat-consistency',
        run_id:    RUN_TAG,
        timestamp: new Date().toISOString(),
        config: {
            scgBaseUrl:    SCG_BASE_URL,
            targetSeatId:  TARGET_SEAT_ID,
            targetEventId: TARGET_EVENT_ID,
            contentionVus: CONTENTION_VUS,
            lockKey:       `reservation:lock:seat:${TARGET_SEAT_ID}`,
            lockWait:      '5s',
            lockLease:     '15s',
        },
        // 참고 수치
        response_distribution: {
            total:    totalCnt,
            s201_success:  successCnt,
            s429_lock_fail: lockCnt,
            s409_conflict:  conflictCnt,
            s503_bulkhead:  bulkheadCnt,
            s5xx_error:     errCnt,
            other:          otherCnt,
        },
        latency_ms: {
            all:     { p50: p50All,  p95: p95All },
            success: { p50: p50Succ },
            fail:    { p50: p50Fail },
        },
        // k6 레벨 판정
        k6_verdict: {
            pass: k6Pass,
            reason: k6Pass ? '5xx == 0' : `5xx = ${errCnt}건`,
        },
        // DB 불변식 — 테스트 후 SQL로 채울 것
        db_invariants: {
            note:        '테스트 종료 후 SQL_VERIFY.sql 실행하여 수동으로 채울 것',
            reservations_count: null,  // 기대: <= 1
            seat_status:        null,  // 기대: 'HOLD'
            seat_version:       null,  // 기대: 1
            oversell:           null,  // 기대: false (count <= 1)
            final_pass:         null,
        },
        observability: {
            elasticsearch: {
                success_log:  '[SeatHolder] 좌석 점유 성공 - seatId: N',
                conflict_log: '[SeatHolder] 동시성 충돌 발생 - 다른 사용자가 먼저 점유함. seatId: N',
                lock_fail:    '[Business] code=R003, msg=현재 해당 좌석에 대한 예약 요청이 처리 중입니다.',
                note:         'Filebeat → ES 수집 확인: booking-app / concert-app 로그 경로 포함 여부 확인',
            },
            prometheus: {
                queries: [
                    'http_server_requests_seconds_count{application="booking-service", status="201"}',
                    'http_server_requests_seconds_count{application="booking-service", status="429"}',
                    'http_server_requests_seconds_count{application="booking-service", status="409"}',
                ],
            },
            jaeger: {
                capture: [
                    '성공 1건: booking-app → concert-app 호출 span 포함 trace',
                    '실패 2건: 락/낙관적락 차단 지점 span 확인 trace',
                ],
            },
        },
        sql_files: {
            init:    'SQL_INIT_s14.sql',
            verify:  'SQL_VERIFY_s14.sql',
            cleanup: 'SQL_CLEANUP_s14.sql',
        },
    };

    return {
        [`${RESULT_DIR}/json/scenario14-seat-consistency_${RUN_TAG}_raw-summary.json`]:
            JSON.stringify(report, null, 2),
        'stdout': '',  // 콘솔 출력은 위에서 처리
    };
}
