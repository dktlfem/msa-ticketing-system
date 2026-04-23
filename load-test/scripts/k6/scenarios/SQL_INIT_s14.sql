-- ─────────────────────────────────────────────────────────────
-- SQL_INIT_s14.sql — Scenario 14 테스트 전 초기화
--
-- 실행 대상: RDS MySQL (또는 스테이징 MySQL)
-- 실행 타이밍: k6 시나리오 실행 직전
-- 수정 필요: {TARGET_SEAT_ID}, {CONTENTION_VUS}, {TARGET_EVENT_ID}
-- ─────────────────────────────────────────────────────────────

-- ① 좌석 상태 초기화 (AVAILABLE / version=0)
-- 기존 HOLD/SOLD 상태를 초기화하여 반복 실행 가능하게 함
UPDATE ticketing_concert.seats
SET    status     = 'AVAILABLE',
       version    = 0,
       update_at  = NOW()
WHERE  seat_id    = {TARGET_SEAT_ID};

-- 확인: 1 row affected 여야 함
-- SELECT seat_id, status, version FROM ticketing_concert.seats WHERE seat_id = {TARGET_SEAT_ID};

-- ② 이전 테스트 예약 정리 (PENDING 상태 기존 row 삭제)
-- 동일 seat_id의 이전 테스트 데이터가 남아 있으면 불변식 1 판정이 오염됨
DELETE FROM ticketing_booking.reservations
WHERE  seat_id    = {TARGET_SEAT_ID}
  AND  status     = 'PENDING';

-- ③ 대기열 토큰 사전 주입
-- userId 1 ~ {CONTENTION_VUS} 에 대해 ACTIVE 토큰 삽입
-- tokenId 패턴: '{userId:08d}-0000-4000-8000-{userId:012d}'
-- 예) userId=5 → '00000005-0000-4000-8000-000000000005'
--
-- 주의: active_tokens 테이블 스키마가 다를 경우 컬럼명 수정 필요
INSERT INTO ticketing_waitingroom.active_tokens
    (token_id, user_id, event_id, status, issued_at, expired_at)
SELECT
    CONCAT(LPAD(n, 8, '0'), '-0000-4000-8000-', LPAD(n, 12, '0')),
    n,
    {TARGET_EVENT_ID},
    'ACTIVE',
    NOW(),
    DATE_ADD(NOW(), INTERVAL 3 HOUR)
FROM (
    WITH RECURSIVE nums AS (
        SELECT 1 AS n
        UNION ALL
        SELECT n + 1 FROM nums WHERE n < {CONTENTION_VUS}
    )
    SELECT n FROM nums
) t
ON DUPLICATE KEY UPDATE
    status     = 'ACTIVE',
    expired_at = DATE_ADD(NOW(), INTERVAL 3 HOUR);

-- ④ 최종 확인
SELECT
    'seats'         AS tbl,
    seat_id, status, version
FROM ticketing_concert.seats
WHERE seat_id = {TARGET_SEAT_ID}

UNION ALL

SELECT
    'active_tokens' AS tbl,
    COUNT(*)        AS cnt,
    'ACTIVE'        AS status,
    NULL            AS version
FROM ticketing_waitingroom.active_tokens
WHERE user_id  BETWEEN 1 AND {CONTENTION_VUS}
  AND event_id = {TARGET_EVENT_ID}
  AND status   = 'ACTIVE';
-- 기대:
--   seats row:  status='AVAILABLE', version=0
--   tokens cnt: {CONTENTION_VUS}건 (혹은 그 이상)
