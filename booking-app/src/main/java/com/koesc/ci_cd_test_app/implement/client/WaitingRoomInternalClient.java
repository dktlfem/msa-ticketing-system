package com.koesc.ci_cd_test_app.implement.client;

import java.time.LocalDateTime;

/**
 * booking-app에서 waitingroom-app을 부를 때 쓰는 인터페이스
 *
 * 아직 Feign/WebClient/RestClient 구현체를 안 만들었다면, 먼저 인터페이스부터 만들어둬야함.
 */
public interface WaitingRoomInternalClient {

    WaitingTokenValidationResult validateToken(String tokenId, Long userId, Long eventId);

    WaitingTokenConsumeResult consumeToken(String tokenId, String usedBy);

    record WaitingTokenValidationResult(
            Boolean valid,
            String tokenId,
            String status,
            LocalDateTime expiredAt
    ) {
    }

    record WaitingTokenConsumeResult(
            String tokenId,
            String status
    ) {
    }

    /**
     * 대기열 활성 토큰 검증
     * - 유효하지 않으면 예외 발생
     */
    //void validateActiveToken(String tokenId, Long userId);

    /**
     * 예약 생성 성공 후 토큰 사용 처리
     * - 이미 사용된 토큰 재사용 방지
     */
    //void consumeActiveToken(String tokenId);
}
