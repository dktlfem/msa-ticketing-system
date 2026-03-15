package com.koesc.ci_cd_test_app.business;

/**
 * - hold() 파싱
 * - 잘못된 expectedStatus면 IllegalArgumentsException
 * - 현재 상태가 기대와 다르면 IllegalStateException
 * - OptimisticLockingFailureException이면 SEAT_CONCURRENT_CONFLICT
 *
 * expectedStatus 파싱
 * 상태 전이
 * optimistic lock conflict 처리
 * AVAILABLE -> HOLD, HOLD -> AVAILABLE, HOLD -> SOLD
 * 이 로직은 실제 비즈니스 규칙이라서 테스트를 진행해야함.
 */
public class SeatInternalServiceTest {
}
