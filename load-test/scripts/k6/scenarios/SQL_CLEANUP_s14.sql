-- ─────────────────────────────────────────────────────────────
-- SQL_CLEANUP_s14.sql — Scenario 14 테스트 후 데이터 정리
--
-- 실행 타이밍: SQL_VERIFY_s14.sql 실행 후 (결과 기록 완료 후)
-- 수정 필요: {TARGET_SEAT_ID}, {CONTENTION_VUS}, {TARGET_EVENT_ID}
--
-- 주의: VERIFY 전에 실행하면 불변식 검증 불가 → 반드시 VERIFY 후 실행
-- ─────────────────────────────────────────────────────────────

-- ① 테스트 예약 삭제
DELETE FROM ticketing_booking.reservations
WHERE  seat_id = {TARGET_SEAT_ID}
  AND  status  IN ('PENDING', 'CANCELLED');

-- ② 좌석 상태 원복 (AVAILABLE / version=0)
-- 다음 테스트 실행을 위해 초기 상태로 복원
UPDATE ticketing_concert.seats
SET    status    = 'AVAILABLE',
       version   = 0,
       update_at = NOW()
WHERE  seat_id   = {TARGET_SEAT_ID};

-- ③ 테스트용 대기열 토큰 만료 처리 (삭제 대신 expired 처리로 이력 보존)
UPDATE ticketing_waitingroom.active_tokens
SET    status     = 'USED',
       expired_at = NOW()
WHERE  user_id  BETWEEN 1 AND {CONTENTION_VUS}
  AND  event_id = {TARGET_EVENT_ID}
  AND  status   = 'ACTIVE';

-- ④ 정리 결과 확인
SELECT
    'seats'        AS tbl,
    seat_id, status, version
FROM ticketing_concert.seats
WHERE seat_id = {TARGET_SEAT_ID}

UNION ALL

SELECT
    'reservations' AS tbl,
    COUNT(*),
    'remaining'    AS status,
    NULL           AS version
FROM ticketing_booking.reservations
WHERE seat_id = {TARGET_SEAT_ID};
-- 기대:
--   seats: status='AVAILABLE', version=0
--   reservations: count=0
