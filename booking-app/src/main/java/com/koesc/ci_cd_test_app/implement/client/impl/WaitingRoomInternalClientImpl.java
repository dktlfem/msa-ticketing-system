package com.koesc.ci_cd_test_app.implement.client.impl;

import com.koesc.ci_cd_test_app.implement.client.WaitingRoomInternalClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.LocalDateTime;

/**
 * booking-app -> waitingroom-app 내부 호출용 구현체
 *
 * 역할:
 * 1. 대기열 통과 토큰 검증
 * 2. 예약 생성 성공 후 토큰 소비 처리
 *
 * 전제:
 * - waitingroom-app 내부 API가 plain DTO(JSON 객체)로 응답한다고 가정
 */
@Component
public class WaitingRoomInternalClientImpl implements WaitingRoomInternalClient {

    private final RestClient restClient;

    public WaitingRoomInternalClientImpl(
            RestClient.Builder builder,
            @Value("${internal.clients.waitingroom.base-url:http://nginx_proxy}") String baseUrl
    ) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    public WaitingTokenValidationResult validateToken(String tokenId, Long userId, Long eventId) {
        try {
            ValidateTokenResponse response = restClient.post()
                    .uri("/internal/v1/waiting-room/tokens/validate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ValidateTokenRequest(tokenId, userId, eventId))
                    .retrieve()
                    .body(ValidateTokenResponse.class);

            if (response == null) {
                throw new IllegalStateException("waitingroom-app 토큰 검증 응답이 null 입니다. tokenId = " + tokenId);
            }

            return new WaitingTokenValidationResult(
                    response.valid(),
                    response.tokenId(),
                    response.status(),
                    response.expiredAt()
            );
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(
                    "waitingroom-app 토큰 검증 실패: status=%s, body=%s"
                            .formatted(e.getStatusCode(), e.getResponseBodyAsString()),
                    e
            );
        }
    }

    @Override
    public WaitingTokenConsumeResult consumeToken(String tokenId, String usedBy) {
        try {
            ConsumeTokenResponse response = restClient.post()
                    .uri("/internal/v1/waiting-room/tokens/{tokenId}/consume", tokenId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ConsumeTokenRequest(usedBy))
                    .retrieve()
                    .body(ConsumeTokenResponse.class);

            if (response == null) {
                throw new IllegalStateException("waitingroom-app 토큰 소비 응답이 null 입니다. tokenId = " + tokenId);
            }

            return new WaitingTokenConsumeResult(
                    response.tokenId(),
                    response.status()
            );
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(
                    "waitingroom-app 토큰 소비 실패: status=%s, body=%s"
                            .formatted(e.getStatusCode(), e.getResponseBodyAsString()),
                    e
            );
        }
    }

    private record ValidateTokenRequest(
            String tokenId,
            Long userId,
            Long eventId
    ) {
    }

    private record ConsumeTokenRequest(
            String usedBy
    ) {
    }

    /**
     * waitingroom-app 내부 validate 응답 DTO
     */
    private record ValidateTokenResponse(
            Boolean valid,
            String tokenId,
            String status,
            LocalDateTime expiredAt
    ) {
    }

    /**
     * waitingroom-app 내부 consume 응답 DTO
     */
    private record ConsumeTokenResponse(
            String tokenId,
            String status
    ) {
    }
}
