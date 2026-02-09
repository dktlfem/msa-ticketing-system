package com.koesc.ci_cd_test_app.api.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 좌석 점유 요청 DTO
 * Record를 사용하여 불변성 확보 및 전송 효율 최적화
 *
 * @NotBlank : String에 쓰임
 * @NotNull : Long에 쓰임
 * @Positive (0, 음수 차단)
 */
public record SeatRequestDTO(
    // @NotNull(message = "스케줄 ID는 필수입니다.") @Positive
    // Long scheduleId,

    @NotNull(message = "좌석 ID는 필수입니다.") @Positive
    Long seatId
) {}
