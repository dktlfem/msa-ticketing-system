package com.koesc.ci_cd_test_app.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 예약 생성 전용 DTO
 *
 * 예약 생성은 사용자가 seatId 하나만 보내면 됨.
 * userId는 원래 로그인 사용자 정보에서 꺼내야 하므로 Request Body에 넣지 않는 게 맞음.
 */
@Schema(description = "예약 생성 요청")
public record ReservationCreateRequestDTO(

        @NotNull
        @Positive
        @Schema(description = "예약할 좌석 ID", example = "10001")
        Long seatId
) {
}
