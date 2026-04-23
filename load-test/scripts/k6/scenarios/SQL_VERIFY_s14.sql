-- ─────────────────────────────────────────────────────────────
-- SQL_VERIFY_s14.sql — Scenario 14 테스트 후 불변식 검증
--
-- 실행 타이밍: k6 종료 직후 (handleSummary 출력 확인 후)
-- 수정 필요: {TARGET_SEAT_ID}
-- 판정: 아래 3개 쿼리 결과가 모두 "기대값"과 일치해야 PASS
-- ─────────────────────────────────────────────────────────────

-- ──────────────────────────────────────────
-- [불변식 1] 예약 row 중복 여부 (oversell 검증)
-- 기대: count <= 1, status = 'PENDING'
-- ──────────────────────────────────────────
SELECT
    COUNT(*)          AS reservation_count,
    GROUP_CONCAT(status ORDER BY reservation_id) AS statuses,
    CASE
        WHEN COUNT(*) = 0 THEN '⚠️  예약 없음 (성공 0건 - 토큰/락 확인 필요)'
        WHEN COUNT(*) = 1 THEN '✅ PASS — 정확히 1건'
        ELSE                   '❌ FAIL — oversell 발생!'
    END AS invariant_1_verdict
FROM ticketing_booking.reservations
WHERE seat_id = {TARGET_SEAT_ID};

-- ──────────────────────────────────────────
-- [불변식 2] 좌석 상태/버전 단일 전이 검증
-- 기대: status = 'HOLD', version = 1
-- ──────────────────────────────────────────
SELECT
    seat_id,
    status,
    version,
    CASE
        WHEN status = 'HOLD' AND version = 1 THEN '✅ PASS — HOLD / version=1'
        WHEN status = 'HOLD' AND version > 1 THEN '❌ FAIL — 버전 2회 이상 전이 (version=' || version || ')'
        WHEN status = 'AVAILABLE'            THEN '⚠️  AVAILABLE — 성공 0건 (초기 상태 유지)'
        WHEN status = 'SOLD'                 THEN '⚠️  SOLD — 결제까지 진행됨 (예상 외)'
        ELSE                                      '❌ FAIL — 예상치 못한 상태: ' || status
    END AS invariant_2_verdict
FROM ticketing_concert.seats
WHERE seat_id = {TARGET_SEAT_ID};

-- ──────────────────────────────────────────
-- [참고] 예약 생성 userId 확인
-- 어떤 userId가 경합에서 이겼는지 확인
-- ──────────────────────────────────────────
SELECT
    reservation_id,
    user_id,
    seat_id,
    status,
    reserved_at,
    expired_at
FROM ticketing_booking.reservations
WHERE seat_id = {TARGET_SEAT_ID}
ORDER BY reservation_id DESC
LIMIT 5;

-- ──────────────────────────────────────────
-- [종합 판정 요약]
-- ──────────────────────────────────────────
SELECT
    (SELECT COUNT(*) FROM ticketing_booking.reservations WHERE seat_id = {TARGET_SEAT_ID}) AS inv1_count,
    (SELECT status   FROM ticketing_concert.seats          WHERE seat_id = {TARGET_SEAT_ID}) AS inv2_status,
    (SELECT version  FROM ticketing_concert.seats          WHERE seat_id = {TARGET_SEAT_ID}) AS inv2_version,
    CASE
        WHEN (SELECT COUNT(*) FROM ticketing_booking.reservations WHERE seat_id = {TARGET_SEAT_ID}) > 1
            THEN '❌ FAIL — [불변식1] oversell 발생'
        WHEN (SELECT status FROM ticketing_concert.seats WHERE seat_id = {TARGET_SEAT_ID}) != 'HOLD'
            THEN '⚠️  WARN — [불변식2] 좌석이 HOLD 상태 아님'
        WHEN (SELECT version FROM ticketing_concert.seats WHERE seat_id = {TARGET_SEAT_ID}) != 1
            THEN '❌ FAIL — [불변식2] version != 1 (중복 전이 의심)'
        ELSE '✅ PASS — 모든 DB 불변식 충족'
    END AS final_verdict;
