package com.koesc.ci_cd_test_app.api.controller;

/**
 * - validate 성공 → 200
 * - expired → 410
 * - already used → 409
 *
 * 이건 다른 서비스가 booking에서 직접 부르는 내부 HTTP 계약이기 때문에 테스트를 진행해야함.
 */
public class InternalWaitingRoomTokenControllerTest {
}
