package com.koesc.ci_cd_test_app.business;

/**
 * - validate 성공
 * - owner/event 불일치 → INVALID
 * - consume 성공 → USED
 * - 이미 USED → ALREADY_USED
 * - expired → EXPIRED
 *
 * tokenId / userId / eventId 검증
 * ACTIVE / USED / EXPIRED 분기
 * consumeIfActive() 성공/실패 해석
 * 동시성 보강 이후의 상태 해석을 위해 테스트를 진행해야함.
 */
public class WaitingRoomInternalServiceTest {
}
