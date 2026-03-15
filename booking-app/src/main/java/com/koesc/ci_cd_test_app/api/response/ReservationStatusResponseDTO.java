package com.koesc.ci_cd_test_app.api.response;

import com.koesc.ci_cd_test_app.domain.Reservation;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 상태만 바뀌는 API에서 공통으로 쓰임
 *
 * - 예약 취소
 * - 예약 확정
 * - 예약 만료
 */
@Schema(description = "예약 상태 변경 응답")
public record ReservationStatusResponseDTO(

        @Schema(description = "예약 ID")
        Long reservationId,

        @Schema(description = "예약 상태", example = "CONFIRMED")
        String status
) {
    public static ReservationStatusResponseDTO from(Reservation reservation) {
        return new ReservationStatusResponseDTO(
                reservation.getReservationId(),
                reservation.getStatus().name()
        );
    }
}
