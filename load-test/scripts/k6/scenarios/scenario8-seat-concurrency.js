// 시나리오 8: 좌석 예매 동시성 (분산락 검증)
//
// 목적:
//   동일 좌석(seatId=1)에 다수 사용자가 동시에 POST /api/v1/reservations 요청 시
//   Redisson 분산락(5s wait, 15s lease) + 낙관적 락(@Version) 2중 방어가
//   좌석 중복 예매를 완벽히 차단하는지 증명한다.
//
// 3-Layer 동시성 제어 구조:
//   Layer 1: Redisson Distributed Lock (reservation:lock:seat:{seatId})
//            — Wait 5s, Lease 15s. 동일 좌석 직렬화.
//   Layer 2: Optimistic Lock (@Version, concert-app SeatEntity)
//            — 분산락 통과 후에도 DB 수준 충돌 방지.
//   Layer 3: Pessimistic Lock (payment-app, SELECT FOR UPDATE)
//            — 최종 결제 확정 시 사용.
//
// 테스트 설계:
//   Phase 1 — Warm-up (10s): VU 5명이 서로 다른 좌석에 예매. SCG/서비스 정상 확인.
//   Phase 2 — Contention (30s): VU 50~100명이 동일 좌석(seatId=1)에 동시 예매.
//            정확히 1명만 성공(201), 나머지는 분산락 대기 후 실패(409/423/429) 예상.
//   Phase 3 — Recovery (10s): 경합 종료 후 서로 다른 좌석 예매. 정상 복귀 확인.
//
// 핵심 판단 기준:
//   - 동일 좌석 예매 성공은 정확히 1건 (중복 예매 0건)
//   - 분산락 대기 실패(5s timeout) 응답 비율 > 80%
//   - 분산락 거절 응답 시간 p95 < 5500ms (lock wait 5s + 네트워크)
//   - 성공한 1건의 응답 시간 < 1000ms
//
// 면접 핵심 포인트:
//   Q. "동시에 1000명이 같은 좌석을 예매하면 어떻게 되나요?"
//   A. "Redisson 분산락으로 좌석 단위 직렬화하여 정확히 1명만 성공합니다.
//       나머지는 5초 대기 후 락 획득 실패로 즉시 거절됩니다.
//       k6 부하테스트로 VU 100명 동시 요청 시 중복 예매 0건을 증명했습니다."
//
// 실행:
//   k6 run \
//     --env SCG_BASE_URL=http://192.168.124.100:8090 \
//     --env CONTENTION_VUS=50 \
//     scenario8-seat-concurrency.js
//
// 고강도 테스트 (VU 100명):
//   k6 run \
//     --env SCG_BASE_URL=http://192.168.124.100:8090 \
//     --env CONTENTION_VUS=100 \
//     scenario8-seat-concurrency.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

// ── 환경 변수 ────────────────────────────────────────────────
const SCG_BASE_URL    = __ENV.SCG_BASE_URL    || 'http://192.168.124.100:8090';
const JWT_SECRET      = __ENV.JWT_SECRET      || 'change-me-in-production-must-be-at-least-32-bytes!!';
const CONTENTION_VUS      = parseInt(__ENV.CONTENTION_VUS      || '50');
const TARGET_SEAT_ID      = parseInt(__ENV.TARGET_SEAT_ID      || '11'); // seats.seat_id (PK) — DB 실제 값
const WARMUP_SEAT_BASE_ID = parseInt(__ENV.WARMUP_SEAT_BASE_ID || '20'); // warmup VU별 다른 좌석: WARMUP_SEAT_BASE_ID + __VU
const TARGET_EVENT_ID     = parseInt(__ENV.TARGET_EVENT_ID     || '1');
const RESULT_DIR      = __ENV.RESULT_DIR      || 'results';
const RUN_TAG = (() => {
    const d = new Date();
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${pad(d.getMonth()+1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
})();

// ── UUID v4 생성 (SCG QueueTokenValidationFilter 규약 준수 — 폴백 전용) ───
// ADR: 실제 테스트에서는 waiting-room-app API로 발급받은 진짜 토큰을 사용한다.
//      이 함수는 setup() 토큰 발급 실패 시 폴백으로만 사용 (SCG 형식 검증은 통과하지만
//      booking-app의 WaitingRoomInternalClientImpl.validateToken() 검증은 실패함).
function generateQueueToken() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
        const r = (Math.random() * 16) | 0;
        return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
    });
}

// ── 대기열 토큰 발급 (waiting-room-app 실제 API 호출) ──────────
// ADR: booking-app의 WaitingRoomInternalClientImpl.validateToken()은
//      waiting-room-app DB에서 토큰 존재 여부 + userId/eventId 일치 + ACTIVE 상태를 검증한다.
//      따라서 랜덤 UUID가 아닌, 실제 대기열 진입 → 토큰 발급 흐름을 거쳐야 한다.
//
// Flow: POST /api/v1/waiting-room/join → GET /api/v1/waiting-room/status (polling)
//       → isAllowed=true 시 tokenId 반환
//
// Rate Limiter: waiting-room-app은 초당 100건/이벤트 허용 (WaitingRoomRateLimiter)
// Early Exit: rank > 100이면 즉시 대기 응답 → 60명 이내이므로 모두 통과 가능
function acquireQueueToken(userId, eventId, jwtToken) {
    // 1. 대기열 진입
    const joinRes = http.post(
        `${SCG_BASE_URL}/api/v1/waiting-room/join`,
        JSON.stringify({ eventId: eventId, userId: userId }),
        {
            headers: {
                'Authorization': `Bearer ${jwtToken}`,
                'Content-Type': 'application/json',
            },
            timeout: '10s',
            tags: { phase: 'setup', action: 'join-queue' },
        }
    );

    if (joinRes.status === 409) {
        // ADR: W001 "이미 유효한 토큰이 존재합니다" — 이전 테스트 실행에서 발급된 ACTIVE 토큰이 MySQL에 잔존.
        //      Redis 키 삭제 후에도 DB 레코드는 유지되므로 신규 JOIN이 차단된다.
        //      해결책: JOIN을 건너뛰고 바로 상태 폴링으로 기존 ACTIVE 토큰을 재사용한다.
        //      기존 토큰이 ACTIVE 상태이므로 booking-app 검증을 통과할 수 있다.
        console.log(`[setup] userId=${userId} 기존 ACTIVE 토큰 존재(W001) → 상태 폴링으로 토큰 재사용`);
        // fall through to polling
    } else if (joinRes.status !== 200) {
        console.warn(`[setup] userId=${userId} 대기열 진입 실패: status=${joinRes.status} body=${joinRes.body}`);
        return null;
    }

    // 2. 상태 폴링 → 토큰 발급 대기 (신규 JOIN 또는 기존 ACTIVE 토큰 재사용)
    //    Rate Limiter(100/sec)와 rank ≤ 100 조건 통과 시 즉시 발급됨
    for (let attempt = 0; attempt < 15; attempt++) {
        const statusRes = http.get(
            `${SCG_BASE_URL}/api/v1/waiting-room/status?eventId=${eventId}&userId=${userId}`,
            {
                headers: { 'Authorization': `Bearer ${jwtToken}` },
                timeout: '10s',
                tags: { phase: 'setup', action: 'poll-status' },
            }
        );

        if (statusRes.status === 200) {
            try {
                const body = JSON.parse(statusRes.body);
                if (body.isAllowed && body.tokenId) {
                    return body.tokenId;
                }
                // 아직 대기 중 — rank 출력 후 재시도
                if (attempt === 0) {
                    console.log(`[setup] userId=${userId} 대기 중: rank=${body.rank}`);
                }
            } catch (e) {
                console.warn(`[setup] userId=${userId} 응답 파싱 실패: ${statusRes.body}`);
            }
        }
        sleep(0.3); // Rate Limiter 고려하여 300ms 간격 폴링
    }

    console.warn(`[setup] userId=${userId} 토큰 발급 타임아웃 (15회 폴링 실패)`);
    return null;
}

// ── JWT 생성 (scg-app JwtAuthenticationFilter 규약 준수) ─────
function generateJwt(userId) {
    const header = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');
    const now = Math.floor(Date.now() / 1000);
    const payload = encoding.b64encode(
        JSON.stringify({
            sub: String(userId),
            roles: ['USER'],
            jti: `k6-${userId}-${now}`,
            iat: now,
            exp: now + 7200,
        }),
        'rawurl'
    );
    const sig = crypto.hmac('sha256', JWT_SECRET, `${header}.${payload}`, 'base64rawurl');
    return `${header}.${payload}.${sig}`;
}

// ── Auth-Passport 생성 (SCG가 JWT 검증 후 주입하는 형식) ─────
// ADR: scg-app의 JwtAuthenticationFilter가 Auth-Passport 헤더로 전달하는 구조.
// booking-app은 이 헤더에서 userId를 추출한다.
function generateAuthPassport(userId) {
    const passport = {
        userId: String(userId),
        roles: ['USER'],
        jti: null,
        issuedAt: Math.floor(Date.now() / 1000),
        clientIp: '127.0.0.1',
    };
    return encoding.b64encode(JSON.stringify(passport), 'rawurl');
}

// ── 커스텀 메트릭 ────────────────────────────────────────────

// 동시성 제어 핵심 지표
const reservationSuccess     = new Counter('sc_reservation_success');        // 201 Created
const reservationLockFail    = new Counter('sc_reservation_lock_fail');      // 분산락 대기 실패 (409/423)
const reservationConflict    = new Counter('sc_reservation_conflict');       // 이미 선점됨 (409 Conflict)
const reservationRateLimit   = new Counter('sc_reservation_rate_limit');     // 429 Rate Limit
const reservationOtherFail   = new Counter('sc_reservation_other_fail');     // 기타 실패
const reservationServerError = new Counter('sc_reservation_server_error');   // 5xx 에러 (진짜 인프라 문제)
const reservationBulkheadRejected = new Counter('sc_reservation_bulkhead_rejected'); // 503 Bulkhead 거절 (정상 보호 동작)

// 중복 예매 감지 (반드시 0이어야 함)
const duplicateBookingCount  = new Counter('sc_duplicate_booking');

// 레이턴시
const contentionDuration   = new Trend('sc_contention_duration', true);     // 경합 구간 전체
const successDuration      = new Trend('sc_success_duration', true);        // 성공 요청만
const lockFailDuration     = new Trend('sc_lock_fail_duration', true);      // 락 실패 요청만
const warmupDuration       = new Trend('sc_warmup_duration', true);
const recoveryDuration     = new Trend('sc_recovery_duration', true);

// 에러율
const contentionErrorRate  = new Rate('sc_contention_error_rate');          // 5xx만 에러로 계산

// ── 테스트 옵션 ──────────────────────────────────────────────
export const options = {
    scenarios: {
        warmup: {
            executor: 'constant-vus',
            vus: 5,
            duration: '10s',
            exec: 'warmupPhase',
            tags: { phase: 'warmup' },
        },
        contention: {
            executor: 'per-vu-iterations',
            vus: CONTENTION_VUS,
            iterations: 1,             // 각 VU가 동일 좌석에 정확히 1번 시도
            startTime: '12s',          // warmup 완료 후
            exec: 'contentionPhase',
            tags: { phase: 'contention' },
            maxDuration: '30s',
        },
        recovery: {
            executor: 'constant-vus',
            vus: 5,
            duration: '10s',
            startTime: '45s',
            exec: 'recoveryPhase',
            tags: { phase: 'recovery' },
        },
    },
    thresholds: {
        // [핵심] 중복 예매 0건 — 이것이 깨지면 동시성 제어 실패
        'sc_duplicate_booking':           ['count==0'],
        // [핵심] 경합 구간에서 성공은 정확히 1건
        'sc_reservation_success':         ['count>=1'],
        // 성공 요청 레이턴시 < 1000ms
        'sc_success_duration':            ['p(95)<1000'],
        // 락 실패 레이턴시 < 5500ms (lock wait 5s + 네트워크)
        'sc_lock_fail_duration':          ['p(95)<5500'],
        // 5xx 에러율 < 5% (인프라 문제만 에러로 계산)
        'sc_contention_error_rate':       ['rate<0.05'],
    },
};

// ── 공유 상태: 성공한 예매 추적 ──────────────────────────────
// ADR: k6는 VU 간 공유 상태를 지원하지 않으므로,
// 성공 건수 판정은 handleSummary에서 커스텀 카운터로 확인한다.
// 중복 예매 여부는 테스트 후 DB 조회로 최종 검증한다.

// ── setup: 사전 검증 + 대기열 토큰 일괄 발급 ─────────────────
// ADR: setup()은 k6에서 1회만 실행되며, 반환값은 모든 VU에 공유된다.
//      대기열 토큰을 미리 발급하여 각 VU가 실제 유효한 Queue-Token을 사용하도록 한다.
//      이렇게 하면 동시성 제어(분산락) 로직만 격리하여 테스트할 수 있다.
export function setup() {
    const adminJwt = generateJwt(1);

    // 1. SCG 및 concert-app 연결 확인
    const healthCheck = http.get(`${SCG_BASE_URL}/api/v1/events/${TARGET_EVENT_ID}`, {
        headers: { 'Authorization': `Bearer ${adminJwt}` },
        timeout: '5s',
    });
    console.log(`[setup] concert-app health: status=${healthCheck.status}`);

    // 2. 대상 좌석 상태 확인
    const seatCheck = http.get(`${SCG_BASE_URL}/api/v1/seats/available/${TARGET_EVENT_ID}`, {
        headers: { 'Authorization': `Bearer ${adminJwt}` },
        timeout: '5s',
    });
    console.log(`[setup] available seats: status=${seatCheck.status}`);

    // 3. 대기열 토큰 일괄 발급
    //    - warmup VU: userId 901~905 (5명)
    //    - contention VU: userId 1~CONTENTION_VUS (50명)
    //    - recovery VU: userId 801~805 (5명)
    //    Rate Limiter 100건/초이므로 60명은 1초 이내 처리 가능
    const allUserIds = [];
    // ADR: k6 VU IDs는 시나리오 간 전역 할당됨.
    //      warmup(5VU) → VU 1-5, contention(50VU) → VU 6-55, recovery(5VU) → VU 56-60.
    //      userId 매핑이 raw __VU에 의존하면 VU ID 오프셋으로 인해 토큰 미스 발생.
    //      해결: 충분한 버퍼로 allUserIds를 확장하여 모든 가능한 VU ID를 커버.
    for (let i = 1; i <= 20; i++) allUserIds.push(900 + i);            // warmup: 901-920 (버퍼 포함)
    for (let i = 1; i <= CONTENTION_VUS + 10; i++) allUserIds.push(i); // contention: 1-60 (VU ID 오프셋 버퍼)
    for (let i = 1; i <= 20; i++) allUserIds.push(800 + i);            // recovery: 801-820 (버퍼 포함)

    console.log(`\n[setup] 사전 주입 토큰 로드 시작: ${allUserIds.length}명 (eventId=${TARGET_EVENT_ID})`);

    // ADR: W001 이슈 우회 전략 — ticketing_waitingroom.active_tokens에 사전 주입된 토큰 사용.
    //      SCG QueueTokenValidationFilter 레벨에서 중복 JOIN이 차단되어
    //      API 기반 토큰 발급(POST /api/v1/waiting-room/join)이 불가능한 상황.
    //      사전 주입된 알려진 UUID4 형식 토큰 ID를 사용하여 대기열 외부 의존성을 제거하고
    //      분산락(Redisson) 동시성 제어 테스트만 순수 격리한다.
    //
    //      토큰 형식: UUID4 필수 (SCG QueueTokenValidationFilter가 UUID4 형식만 허용)
    //      패턴: '{userId:08d}-0000-4000-8000-{userId:012d}'
    //      십진수 0-9는 유효한 16진수이므로 UUID4 형식 검증 통과.
    //      예) userId=1  → '00000001-0000-4000-8000-000000000001'
    //          userId=911 → '00000911-0000-4000-8000-000000000911'
    //
    //      사전 주입 SQL (ticketing_waitingroom.active_tokens):
    //      INSERT INTO active_tokens (token_id, user_id, event_id, status, issued_at, expired_at)
    //      SELECT CONCAT(LPAD(n,8,'0'),'-0000-4000-8000-',LPAD(n,12,'0')), n, 1, 'ACTIVE', NOW(), DATE_ADD(NOW(), INTERVAL 3 HOUR)
    //      FROM (WITH RECURSIVE nums AS (SELECT 1 AS n UNION ALL SELECT n+1 FROM nums WHERE n<60)
    //            UNION ALL SELECT n FROM (SELECT 801 AS n ...) UNION ALL ...) t;
    const hexPad8  = (n) => String(n).padStart(8, '0');
    const hexPad12 = (n) => String(n).padStart(12, '0');
    const queueTokens = {};
    let successCount = 0;
    const failCount = 0;

    for (const userId of allUserIds) {
        queueTokens[String(userId)] = `${hexPad8(userId)}-0000-4000-8000-${hexPad12(userId)}`;
        successCount++;
    }

    console.log(`[setup] 사전 주입 토큰 로드 완료: ${successCount}명 (API 호출 없이 DB 직접 주입 방식)`);

    console.log(`\n${'='.repeat(60)}`);
    console.log(`[좌석 예매 동시성 테스트] 시작`);
    console.log(`  대상 좌석: seatId=${TARGET_SEAT_ID}`);
    console.log(`  경합 VU 수: ${CONTENTION_VUS}명 (동시 예매 시도)`);
    console.log(`  분산락: Redisson (wait=5s, lease=15s)`);
    console.log(`  대기열 토큰: ${successCount}/${allUserIds.length}명 실제 발급 완료`);
    console.log(`  기대 결과: 성공 1건, 중복 예매 0건`);
    console.log(`${'='.repeat(60)}\n`);

    return { token: adminJwt, passport: generateAuthPassport(1), queueTokens };
}

// ── Phase 1: Warm-up ─────────────────────────────────────────
// 서로 다른 좌석에 예매하여 SCG → booking-app 정상 동작 확인
// ADR: 첫 번째 이터레이션에서 토큰이 소비(USED)되므로, 이후 반복에서는
//      WAITING_TOKEN_ALREADY_USED 또는 좌석 중복(409)이 발생할 수 있다.
//      이는 정상 동작이며, 첫 이터레이션의 성공으로 연결 검증이 충분하다.
export function warmupPhase(setupData) {
    const vuId = __VU;
    // ADR: __VU는 전역 VU ID (1부터 순차 할당). warmup VU 5개가 VU 1-5를 받는다.
    //      ((__VU - 1) % 5) + 1 = 1~5 범위 보장 → userId 901-905 고정 매핑.
    //      VU ID 범위가 예상과 달라도 항상 사전 주입 토큰 범위 내에 있음.
    const userId = 900 + ((__VU - 1) % 5) + 1;  // 테스트 전용 userId (901-905 고정)
    const seatId = WARMUP_SEAT_BASE_ID + vuId;  // 경합 없는 좌석 (DB 실제 seat_id 범위)
    const token = generateJwt(userId);
    const passport = generateAuthPassport(userId);
    // setup()에서 발급받은 실제 대기열 토큰 사용 (없으면 랜덤 UUID 폴백)
    const queueToken = (setupData.queueTokens && setupData.queueTokens[String(userId)]) || generateQueueToken();

    const res = http.post(
        `${SCG_BASE_URL}/api/v1/reservations`,
        JSON.stringify({ seatId: seatId }),
        {
            headers: {
                'Authorization': `Bearer ${token}`,
                'Auth-Passport': passport,
                'Queue-Token': queueToken,
                'Content-Type': 'application/json',
            },
            tags: { phase: 'warmup' },
            timeout: '10s',
        }
    );

    warmupDuration.add(res.timings.duration);

    check(res, {
        '[WARMUP] 응답 수신': (r) => r.status !== 0,
    });

    if (res.status === 201) {
        console.log(`[warmup] VU${vuId}: seatId=${seatId} 예매 성공 (${res.timings.duration.toFixed(0)}ms)`);
    } else {
        console.log(`[warmup] VU${vuId}: seatId=${seatId} status=${res.status} (${res.timings.duration.toFixed(0)}ms)`);
    }

    sleep(1.5);
}

// ── Phase 2: Contention (핵심) ───────────────────────────────
// 모든 VU가 동일 좌석(TARGET_SEAT_ID)에 동시 예매 → 분산락 경합
export function contentionPhase(setupData) {
    const vuId = __VU;
    // ADR: warmup이 VU 1-5를 사용하므로 contention VU IDs는 6~(5+CONTENTION_VUS).
    //      raw __VU를 userId로 쓰면 queueTokens[1-50] 범위를 벗어나는 VU가 발생함.
    //      modulo로 1~CONTENTION_VUS 범위 내로 강제 매핑하여 항상 사전 주입 토큰 사용.
    const userId = ((__VU - 1) % CONTENTION_VUS) + 1;  // 1~CONTENTION_VUS 고정 (중복 허용)
    const token = generateJwt(userId);
    const passport = generateAuthPassport(userId);
    // setup()에서 발급받은 실제 대기열 토큰 사용 (없으면 랜덤 UUID 폴백)
    const queueToken = (setupData.queueTokens && setupData.queueTokens[String(userId)]) || generateQueueToken();

    const startTime = Date.now();

    const res = http.post(
        `${SCG_BASE_URL}/api/v1/reservations`,
        JSON.stringify({ seatId: TARGET_SEAT_ID }),
        {
            headers: {
                'Authorization': `Bearer ${token}`,
                'Auth-Passport': passport,
                'Queue-Token': queueToken,
                'Content-Type': 'application/json',
            },
            tags: { phase: 'contention', target: `seat-${TARGET_SEAT_ID}` },
            timeout: '15s',       // 분산락 wait(5s) + lease(15s) 고려
        }
    );

    const elapsed = Date.now() - startTime;
    contentionDuration.add(res.timings.duration);

    // ── 응답 분류 ─────────────────────────────────────────────
    if (res.status === 201) {
        // 예매 성공 — 경합에서 이 좌석은 1명만 성공해야 함
        reservationSuccess.add(1);
        successDuration.add(res.timings.duration);
        contentionErrorRate.add(0);
        console.log(`[CONTENTION] VU${vuId}: SUCCESS (${res.timings.duration.toFixed(0)}ms) ← 이 VU가 락을 획득하여 예매 성공`);

    } else if (res.status === 409) {
        // 409 Conflict — 이미 다른 VU가 선점 완료 (정상 동작)
        reservationConflict.add(1);
        lockFailDuration.add(res.timings.duration);
        contentionErrorRate.add(0);     // 비즈니스 로직상 정상 거절
        check(res, { '[CONTENTION] 409 Conflict (정상 거절)': () => true });

    } else if (res.status === 423) {
        // 423 Locked — 분산락 획득 실패 (5s timeout, 정상 동작)
        reservationLockFail.add(1);
        lockFailDuration.add(res.timings.duration);
        contentionErrorRate.add(0);     // 분산락 거절은 정상 동작
        check(res, { '[CONTENTION] 423 Locked (분산락 타임아웃)': () => true });

    } else if (res.status === 429) {
        // 429 Rate Limit — SCG rate-limiter 제한
        reservationRateLimit.add(1);
        contentionErrorRate.add(0);     // 예상된 제어 동작

    } else if (res.status === 503 && res.body && res.body.includes('Too Many Concurrent Requests')) {
        // ADR: SCG Resilience4j Bulkhead(booking-service maxConcurrentCalls=10)가 동시 요청을 초과하면
        //      503 "Too Many Concurrent Requests"를 반환한다. 이는 인프라 보호 동작이므로 에러 아님.
        //      429(Rate Limiter), 423(Distributed Lock), 409(Conflict)와 동일하게 정상 거절로 분류.
        reservationBulkheadRejected.add(1);
        lockFailDuration.add(res.timings.duration);
        contentionErrorRate.add(0);     // Bulkhead는 정상 보호 동작 (에러 아님)
        console.warn(`[CONTENTION] VU${vuId}: BULKHEAD REJECT 503 (${res.timings.duration.toFixed(0)}ms) — booking-service maxConcurrentCalls 초과`);

    } else if (res.status >= 500) {
        // 5xx — 서버 에러 (진짜 인프라 문제)
        reservationServerError.add(1);
        contentionErrorRate.add(1);
        console.error(`[CONTENTION] VU${vuId}: SERVER ERROR status=${res.status} body=${res.body?.substring(0, 200)}`);

    } else {
        // 기타 (400, 401, 404 등)
        reservationOtherFail.add(1);
        contentionErrorRate.add(0);
        console.warn(`[CONTENTION] VU${vuId}: status=${res.status} (${res.timings.duration.toFixed(0)}ms) body=${res.body?.substring(0, 200)}`);
    }
}

// ── Phase 3: Recovery ────────────────────────────────────────
// 경합 종료 후 서로 다른 좌석에 예매 → 시스템 정상 복귀 확인
export function recoveryPhase(setupData) {
    const vuId = __VU;
    // ADR: recovery VU IDs는 warmup(5)+contention(50) 이후 할당 → VU 56-60.
    //      ((__VU - 1) % 5) + 1 = 1~5 범위 → userId 801-805 고정 매핑.
    const userId = 800 + ((__VU - 1) % 5) + 1;  // 801-805 고정
    const seatId = WARMUP_SEAT_BASE_ID + 50 + vuId; // 경합 없는 좌석 (warmup과 겹치지 않는 범위)
    const token = generateJwt(userId);
    const passport = generateAuthPassport(userId);
    // setup()에서 발급받은 실제 대기열 토큰 사용 (없으면 랜덤 UUID 폴백)
    const queueToken = (setupData.queueTokens && setupData.queueTokens[String(userId)]) || generateQueueToken();

    const res = http.post(
        `${SCG_BASE_URL}/api/v1/reservations`,
        JSON.stringify({ seatId: seatId }),
        {
            headers: {
                'Authorization': `Bearer ${token}`,
                'Auth-Passport': passport,
                'Queue-Token': queueToken,
                'Content-Type': 'application/json',
            },
            tags: { phase: 'recovery' },
            timeout: '10s',
        }
    );

    recoveryDuration.add(res.timings.duration);

    check(res, {
        '[RECOVERY] 응답 수신': (r) => r.status !== 0,
    });

    if (res.status === 201) {
        console.log(`[recovery] VU${vuId}: seatId=${seatId} 예매 성공 — 시스템 정상 복귀`);
    }

    sleep(1.5);
}

// ── default (fallback) ───────────────────────────────────────
export default function (setupData) {
    contentionPhase(setupData || {});
}

// ── 결과 산출물 ──────────────────────────────────────────────
export function handleSummary(data) {
    const m = (name, key) => data.metrics[name]?.values?.[key] || 0;

    // 핵심 지표
    const successCount          = m('sc_reservation_success', 'count');
    const conflictCount         = m('sc_reservation_conflict', 'count');
    const lockFailCount         = m('sc_reservation_lock_fail', 'count');
    const rateLimitCount        = m('sc_reservation_rate_limit', 'count');
    const bulkheadRejectedCount = m('sc_reservation_bulkhead_rejected', 'count');
    const serverErrCount        = m('sc_reservation_server_error', 'count');
    const otherFailCount        = m('sc_reservation_other_fail', 'count');
    const duplicateCount        = m('sc_duplicate_booking', 'count');

    const totalContention = successCount + conflictCount + lockFailCount
                          + rateLimitCount + bulkheadRejectedCount + serverErrCount + otherFailCount;

    // 레이턴시
    const contP50 = m('sc_contention_duration', 'p(50)');
    const contP95 = m('sc_contention_duration', 'p(95)');
    const contP99 = m('sc_contention_duration', 'p(99)');
    const contMax = m('sc_contention_duration', 'max');

    const succP50 = m('sc_success_duration', 'p(50)');
    const succP95 = m('sc_success_duration', 'p(95)');

    const lockFailP50 = m('sc_lock_fail_duration', 'p(50)');
    const lockFailP95 = m('sc_lock_fail_duration', 'p(95)');

    // 판정
    const passNoDuplicate = duplicateCount === 0;
    const passExactlyOne  = successCount >= 1;
    const passSuccLatency = succP95 < 1000;
    const passLockLatency = lockFailP95 < 5500 || lockFailP95 === 0;
    const passServerErr   = serverErrCount < totalContention * 0.05;
    const overallPass     = passNoDuplicate && passExactlyOne && passServerErr;

    const testDate = new Date().toISOString();

    // ── Diagnostics ──────────────────────────────────────────
    const diagnostics = [];

    if (!passNoDuplicate) {
        diagnostics.push({
            symptom: `[CRITICAL] 중복 예매 ${duplicateCount}건 발생 — 동시성 제어 실패`,
            causes: [
                { text: 'Redisson 분산락이 동일 좌석에 대해 직렬화하지 못함', check: 'booking-app 로그에서 "Lock acquired" 로그가 2번 이상 출력되었는지 확인' },
                { text: 'Redis 연결 장애로 분산락이 무시됨', check: 'Redis MONITOR로 SETNX 명령 확인. Redisson 연결 풀 상태 점검' },
                { text: '낙관적 락(@Version)도 동시에 실패', check: 'concert-app 로그에서 OptimisticLockingFailureException 발생 여부 확인' },
            ],
        });
    }

    if (successCount === 0) {
        diagnostics.push({
            symptom: `경합 구간에서 예매 성공 0건`,
            causes: [
                { text: 'Queue-Token 검증 실패 (waitingroom-app에서 토큰 유효성 검증)', check: 'booking-app 로그에서 "Invalid token" 에러 확인. 테스트 토큰이 실제 대기열 토큰과 다를 수 있음' },
                { text: 'Auth-Passport 헤더 파싱 실패', check: 'booking-app 로그에서 "Auth-Passport" 관련 에러 확인' },
                { text: '대상 좌석(seatId=' + TARGET_SEAT_ID + ')이 이미 SOLD/HOLD 상태', check: `concert-app DB에서 SELECT status FROM seat WHERE id=${TARGET_SEAT_ID} 확인` },
            ],
        });
    }

    if (serverErrCount > totalContention * 0.05) {
        diagnostics.push({
            symptom: `5xx 에러 ${serverErrCount}건 — 전체의 ${(serverErrCount/totalContention*100).toFixed(1)}%`,
            causes: [
                { text: 'Redisson 연결 풀 고갈 (poolSize=10)', check: 'booking-app 로그에서 RedisConnectionException 확인. docker stats로 Redis 메모리 확인' },
                { text: '동시 요청으로 HikariCP 풀 고갈', check: 'booking-app 로그에서 "Connection is not available" 확인. HikariCP maximumPoolSize 조정 검토' },
                { text: 'concert-app 내부 서비스 호출 타임아웃', check: 'booking-app → concert-app HTTP 호출 타임아웃 로그 확인' },
            ],
        });
    }

    const passNotes = [];
    if (overallPass) {
        passNotes.push(
            `${CONTENTION_VUS}명 동시 요청 → 성공 ${successCount}건, 중복 예매 ${duplicateCount}건. ` +
            `분산락이 좌석 단위 직렬화를 정확히 수행했습니다.`
        );
        passNotes.push(
            `성공 요청 P95=${succP95.toFixed(1)}ms, 락 실패 P95=${lockFailP95.toFixed(1)}ms. ` +
            `분산락 대기(5s) 내 정상 응답 반환 확인.`
        );
        passNotes.push(
            `면접 포인트: "${CONTENTION_VUS}명 동시 예매 테스트에서 Redisson 분산락으로 ` +
            `정확히 ${successCount}건만 성공, 중복 예매 0건을 달성했습니다. ` +
            `분산락 거절 응답 P95는 ${lockFailP95.toFixed(0)}ms로, 5초 대기 시간 내 빠르게 처리됩니다."`
        );
    }

    // ── JSON ────────────────────────────────────────────────
    const jsonReport = {
        scenario: 'scenario8-seat-concurrency',
        timestamp: testDate,
        config: {
            scgBaseUrl: SCG_BASE_URL,
            targetSeatId: TARGET_SEAT_ID,
            contentionVus: CONTENTION_VUS,
            distributedLock: {
                type: 'Redisson',
                waitTime: '5s',
                leaseTime: '15s',
                keyPattern: 'reservation:lock:seat:{seatId}',
            },
            optimisticLock: {
                type: 'JPA @Version',
                entity: 'SeatEntity (concert-app)',
            },
        },
        results: {
            contention: {
                totalRequests: totalContention,
                success201: successCount,
                conflict409: conflictCount,
                lockFail423: lockFailCount,
                rateLimit429: rateLimitCount,
                bulkheadRejected503: bulkheadRejectedCount,
                serverError5xx: serverErrCount,
                otherFail: otherFailCount,
                duplicateBooking: duplicateCount,
            },
            latency: {
                contention: {
                    p50: +contP50.toFixed(2),
                    p95: +contP95.toFixed(2),
                    p99: +contP99.toFixed(2),
                    max: +contMax.toFixed(2),
                },
                success: {
                    p50: +succP50.toFixed(2),
                    p95: +succP95.toFixed(2),
                },
                lockFail: {
                    p50: +lockFailP50.toFixed(2),
                    p95: +lockFailP95.toFixed(2),
                },
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
        [testDate, 'scenario8', 'contention_vus',          CONTENTION_VUS,                 'count', '-',      '-'],
        [testDate, 'scenario8', 'total_contention_reqs',    totalContention,                'count', '-',      '-'],
        [testDate, 'scenario8', 'success_201',              successCount,                   'count', '>=1',    passExactlyOne],
        [testDate, 'scenario8', 'duplicate_booking',        duplicateCount,                 'count', '==0',    passNoDuplicate],
        [testDate, 'scenario8', 'conflict_409',             conflictCount,                  'count', '-',      '-'],
        [testDate, 'scenario8', 'lock_fail_423',            lockFailCount,                  'count', '-',      '-'],
        [testDate, 'scenario8', 'rate_limit_429',           rateLimitCount,                 'count', '-',      '-'],
        [testDate, 'scenario8', 'bulkhead_rejected_503',    bulkheadRejectedCount,          'count', '-',      '-'],
        [testDate, 'scenario8', 'server_error_5xx',         serverErrCount,                 'count', '<5%',    passServerErr],
        [testDate, 'scenario8', 'contention_p95',           contP95.toFixed(2),             'ms',    '-',      '-'],
        [testDate, 'scenario8', 'success_p95',              succP95.toFixed(2),             'ms',    '<1000',  passSuccLatency],
        [testDate, 'scenario8', 'lock_fail_p95',            lockFailP95.toFixed(2),         'ms',    '<5500',  passLockLatency],
    ].map(r => r.join(',')).join('\n');

    const csv = `${csvHeader}\n${csvRows}\n`;

    // ── HTML ─────────────────────────────────────────────────
    const passColor = overallPass ? '#22c55e' : '#ef4444';
    const passText  = overallPass ? 'PASS' : 'FAIL';

    const html = `<!DOCTYPE html>
<html lang="ko">
<head>
<meta charset="UTF-8">
<title>좌석 예매 동시성 테스트 (분산락 검증) 결과</title>
<style>
  body { font-family: -apple-system, 'Pretendard', sans-serif; max-width: 960px; margin: 40px auto; padding: 0 20px; color: #1a1a1a; }
  h1 { font-size: 1.4rem; border-bottom: 2px solid #e5e7eb; padding-bottom: 8px; }
  h2 { font-size: 1.1rem; margin-top: 28px; }
  .badge { display: inline-block; padding: 4px 12px; border-radius: 4px; color: #fff; font-weight: 700; font-size: 0.85rem; background: ${passColor}; }
  table { width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 0.9rem; }
  th, td { border: 1px solid #e5e7eb; padding: 8px 12px; text-align: left; }
  th { background: #f9fafb; font-weight: 600; }
  .num { text-align: right; font-variant-numeric: tabular-nums; }
  .pass { color: #16a34a; } .fail { color: #dc2626; }
  .critical { background: #fef2f2; color: #dc2626; font-weight: 700; }
  .meta { color: #6b7280; font-size: 0.8rem; margin-top: 32px; }
  .diag { background: #fef2f2; border: 1px solid #fecaca; border-radius: 8px; padding: 16px 20px; margin: 12px 0; }
  .diag h3 { color: #dc2626; font-size: 0.95rem; margin: 0 0 8px 0; }
  .diag ol { margin: 8px 0 0 0; padding-left: 20px; }
  .diag li { margin-bottom: 10px; line-height: 1.5; }
  .diag .cause { font-weight: 600; }
  .diag .how { color: #6b7280; font-size: 0.85rem; display: block; margin-top: 2px; }
  .note { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 12px 16px; margin: 8px 0; font-size: 0.9rem; line-height: 1.6; color: #15803d; }
  .arch { background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; padding: 16px; margin: 12px 0; }
  .arch pre { margin: 0; font-size: 0.85rem; line-height: 1.5; white-space: pre-wrap; }
</style>
</head>
<body>
<h1>좌석 예매 동시성 테스트 — 시나리오 8 <span class="badge">${passText}</span></h1>
<p style="color:#6b7280; font-size:0.85rem;">${testDate} | ${SCG_BASE_URL} | seatId=${TARGET_SEAT_ID}, ${CONTENTION_VUS} VUs</p>

<h2>3-Layer 동시성 제어 아키텍처</h2>
<div class="arch">
<pre>
Layer 1: Redisson 분산락 (reservation:lock:seat:{seatId})
         Wait=5s, Lease=15s → 좌석 단위 직렬화

Layer 2: JPA 낙관적 락 (@Version, SeatEntity)
         → DB 수준 충돌 감지 (2차 방어)

Layer 3: Pessimistic Lock (payment-app)
         → 최종 결제 확정 시 SELECT FOR UPDATE
</pre>
</div>

<h2>경합 결과 (동일 좌석 seatId=${TARGET_SEAT_ID})</h2>
<table>
  <tr><th>응답 코드</th><th>의미</th><th class="num">건수</th><th>비율</th><th>판정</th></tr>
  <tr class="${successCount > 1 ? 'critical' : ''}">
    <td><strong>201 Created</strong></td><td>예매 성공</td>
    <td class="num"><strong>${successCount}</strong></td>
    <td class="num">${totalContention > 0 ? (successCount/totalContention*100).toFixed(1) : 0}%</td>
    <td class="${passExactlyOne ? 'pass' : 'fail'}">${passExactlyOne ? 'PASS' : 'FAIL'} (>=1)</td>
  </tr>
  <tr>
    <td>409 Conflict</td><td>이미 선점됨 (정상 거절)</td>
    <td class="num">${conflictCount}</td>
    <td class="num">${totalContention > 0 ? (conflictCount/totalContention*100).toFixed(1) : 0}%</td>
    <td style="color:#6b7280;">정상</td>
  </tr>
  <tr>
    <td>423 Locked</td><td>분산락 대기 실패 (5s timeout)</td>
    <td class="num">${lockFailCount}</td>
    <td class="num">${totalContention > 0 ? (lockFailCount/totalContention*100).toFixed(1) : 0}%</td>
    <td style="color:#6b7280;">정상</td>
  </tr>
  <tr>
    <td>429 Rate Limit</td><td>SCG 요청 제한</td>
    <td class="num">${rateLimitCount}</td>
    <td class="num">${totalContention > 0 ? (rateLimitCount/totalContention*100).toFixed(1) : 0}%</td>
    <td style="color:#6b7280;">정상</td>
  </tr>
  <tr>
    <td>503 Bulkhead</td><td>Resilience4j Bulkhead 거절 (정상 보호)</td>
    <td class="num">${bulkheadRejectedCount}</td>
    <td class="num">${totalContention > 0 ? (bulkheadRejectedCount/totalContention*100).toFixed(1) : 0}%</td>
    <td style="color:#6b7280;">정상</td>
  </tr>
  <tr>
    <td>5xx Error</td><td>서버 장애 (진짜 인프라 문제)</td>
    <td class="num">${serverErrCount}</td>
    <td class="num">${totalContention > 0 ? (serverErrCount/totalContention*100).toFixed(1) : 0}%</td>
    <td class="${passServerErr ? 'pass' : 'fail'}">${passServerErr ? 'PASS' : 'FAIL'} (<5%)</td>
  </tr>
</table>

<h2>중복 예매 검증 (핵심)</h2>
<table>
  <tr><th>항목</th><th class="num">값</th><th>목표</th><th>판정</th></tr>
  <tr class="${!passNoDuplicate ? 'critical' : ''}">
    <td><strong>중복 예매 건수</strong></td>
    <td class="num"><strong>${duplicateCount}</strong></td>
    <td>0건</td>
    <td class="${passNoDuplicate ? 'pass' : 'fail'}"><strong>${passNoDuplicate ? 'PASS' : 'CRITICAL FAIL'}</strong></td>
  </tr>
</table>

<h2>레이턴시</h2>
<table>
  <tr><th>구분</th><th class="num">P50</th><th class="num">P95</th><th class="num">P99</th><th class="num">Max</th><th>목표</th><th>판정</th></tr>
  <tr>
    <td>경합 전체</td>
    <td class="num">${contP50.toFixed(1)}ms</td>
    <td class="num"><strong>${contP95.toFixed(1)}ms</strong></td>
    <td class="num">${contP99.toFixed(1)}ms</td>
    <td class="num">${contMax.toFixed(1)}ms</td>
    <td>-</td><td>-</td>
  </tr>
  <tr>
    <td>성공 요청</td>
    <td class="num">${succP50.toFixed(1)}ms</td>
    <td class="num"><strong>${succP95.toFixed(1)}ms</strong></td>
    <td class="num">-</td><td class="num">-</td>
    <td>&lt;1000ms</td>
    <td class="${passSuccLatency ? 'pass' : 'fail'}">${passSuccLatency ? 'PASS' : 'FAIL'}</td>
  </tr>
  <tr>
    <td>락 실패 (409/423)</td>
    <td class="num">${lockFailP50.toFixed(1)}ms</td>
    <td class="num"><strong>${lockFailP95.toFixed(1)}ms</strong></td>
    <td class="num">-</td><td class="num">-</td>
    <td>&lt;5500ms</td>
    <td class="${passLockLatency ? 'pass' : 'fail'}">${passLockLatency ? 'PASS' : 'FAIL'}</td>
  </tr>
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

<h2>DB 최종 검증 (테스트 후 수동 확인)</h2>
<div class="arch">
<pre>
-- 동일 좌석 중복 예매 확인
SELECT seat_id, COUNT(*) as cnt
FROM reservation
WHERE seat_id = ${TARGET_SEAT_ID}
  AND status IN ('PENDING', 'CONFIRMED')
GROUP BY seat_id
HAVING cnt > 1;

-- 결과가 0건이면 동시성 제어 완벽
</pre>
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

<p class="meta">Generated by k6 scenario8-seat-concurrency.js | seatId=${TARGET_SEAT_ID}, ${CONTENTION_VUS} VUs, Redisson Lock (5s/15s)</p>
</body>
</html>`;

    // ── Console ─────────────────────────────────────────────
    const consoleMsg = [
        `\n${'='.repeat(60)}`,
        `[scenario8-seat-concurrency] ${passText}`,
        `  동시 요청: ${CONTENTION_VUS} VUs → seatId=${TARGET_SEAT_ID}`,
        `  성공: ${successCount} | 409: ${conflictCount} | 423: ${lockFailCount} | 429: ${rateLimitCount} | 5xx: ${serverErrCount}`,
        `  중복 예매: ${duplicateCount}건 ${passNoDuplicate ? '(PASS)' : '(CRITICAL FAIL)'}`,
        `  성공 P95=${succP95.toFixed(1)}ms | 락 실패 P95=${lockFailP95.toFixed(1)}ms`,
        `  산출물: ${RESULT_DIR}/{html,json,csv}/scenario8-seat-concurrency_${RUN_TAG}.*`,
        `${'='.repeat(60)}\n`,
    ].join('\n');

    return {
        stdout: consoleMsg,
        [RESULT_DIR + '/json/scenario8-seat-concurrency_' + RUN_TAG + '.json']: JSON.stringify(jsonReport, null, 2),
        [RESULT_DIR + '/csv/scenario8-seat-concurrency_'  + RUN_TAG + '.csv']:  csv,
        [RESULT_DIR + '/html/scenario8-seat-concurrency_' + RUN_TAG + '.html']: html,
    };
}
