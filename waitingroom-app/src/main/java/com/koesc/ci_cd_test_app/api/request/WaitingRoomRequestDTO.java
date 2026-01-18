package com.koesc.ci_cd_test_app.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * 대기열 진입 및 상태 조회 요청 DTO
 * Record 타입을 사용하여 불변성 확보 및 Getter 자동 생성
 */

@Schema(description = "대기열 진입 요청")
public record WaitingRoomRequestDTO(

    @Schema(description = "공연 ID", example = "1")
    @NotNull(message = "공연 ID는 필수입니다.")
    Long eventId,

    @Schema(description = "사용자 ID", example = "100")
    @NotNull(message = "사용자 ID는 필수입니다.")
    Long userId

) {}
