package com.koesc.ci_cd_test_app.api.response;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 대기열 상태 및 토큰 정보 응답 DTO
 * 상황에 따라 '대기 순번'만 줄 수도 있고, '토큰'을 줄 수도 있음.
 */

@Builder
@Schema(description = "대기열 상태 응답")
public record WaitingRoomResponseDTO(

    @Schema(description = "현재 대기 순번 (null이면 입장 가능)", example = "150")
    Long rank,

    @Schema(description = "예상 대기 시간 (초)", example = "45")
    Long estimatedSeconds,

    @Schema(description = "입장 가능 여부", example = "false")
    boolean isAllowed,

    @Schema(description = "발급된 대기열 토큰 (입장 가능한 경우에만 존재)", example = "550e8400-e29b-41d4-a716-446655440000")
    String tokenId,

    @Schema(description = "토큰 만료 시간", example = "2026-01-08T15:00:00")
    String expiredAt
) {
    /**
     * 정적 팩토리 메서드 : 대기 중일 때 (순번과 예상 시간을 함께 리턴)
     */
    public static WaitingRoomResponseDTO waiting(Long rank, Long estimatedSeconds) {
        return builder()
                .rank(rank)
                .estimatedSeconds(estimatedSeconds)
                .isAllowed(false)
                .build();
    }

    /**
     * 정적 팩토리 메서드 : 입장 가능할 때 (토큰 리턴)
     */
    public static WaitingRoomResponseDTO allowed(WaitingToken token) {
        return builder()
                .rank(0L) // 대기 없음
                .estimatedSeconds(0L)
                .isAllowed(true)
                .tokenId(token.getTokenId())
                .expiredAt(token.getExpiredAt().toString())
                .build();
    }
}
