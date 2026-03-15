package com.koesc.ci_cd_test_app.api.response;

import com.koesc.ci_cd_test_app.domain.Reservation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 내 예약 목록 조회 전용 DTO
 */
@Schema(description = "내 예약 목록 조회 응답")
public record ReservationSummaryResponseDTO(

        @Schema(description = "예약 ID")
        Long reservationId,

        @Schema(description = "좌석 ID")
        Long seatId,

        @Schema(description = "예약 상태", example = "PENDING")
        String status,

        @Schema(description = "예약 생성 시각")
        LocalDateTime reservedAt,

        @Schema(description = "예약 만료 시각")
        LocalDateTime expiredAt
) {
    public static ReservationSummaryResponseDTO from(Reservation reservation) {
        return new ReservationSummaryResponseDTO(
                reservation.getReservationId(),
                reservation.getSeatId(),
                reservation.getStatus().name(),
                reservation.getReservedAt(),
                reservation.getExpiredAt()
        );
    }
}
