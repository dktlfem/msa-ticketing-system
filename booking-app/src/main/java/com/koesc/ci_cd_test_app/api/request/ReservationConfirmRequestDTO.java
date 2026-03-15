package com.koesc.ci_cd_test_app.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * payment-app -> booking-app 내부 호출에서 쓰임.
 *
 * 결제 성공 후, 어떤 결제(paymentId) 때문에 예약을 확정하는지 넘겨줌.
 */
@Schema(description = "예약 확정 요청 (내부 API)")
public record ReservationConfirmRequestDTO(
        @NotNull
        @Positive
        @Schema(description = "예약을 확정시킨 결제 ID", example = "50001")
        Long paymentId
) {
}
