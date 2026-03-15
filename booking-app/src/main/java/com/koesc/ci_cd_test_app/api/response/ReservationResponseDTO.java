package com.koesc.ci_cd_test_app.api.response;

import com.koesc.ci_cd_test_app.domain.Reservation;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 아래 케이스에 공용으로 쓰면 되는 DTO
 *
 * - 예약 생성 응답
 * - 예약 상세 조회 응답
 * - 내부 예약 상세 조회 응답
 */
public record ReservationResponseDTO(

        @Schema(description = "예약 ID")
        Long reservationId,

        @Schema(description = "사용자 ID")
        Long userId,

        @Schema(description = "좌석 ID")
        Long seatId,

        @Schema(description = "예약 상태", example = "PENDING")
        String status,

        @Schema(description = "예약 생성 시각")
        LocalDateTime reservedAt,

        @Schema(description = "예약 만료 시각")
        LocalDateTime expiredAt
) {
    public static ReservationResponseDTO from(Reservation reservation) {
        return new ReservationResponseDTO(
                reservation.getReservationId(),
                reservation.getUserId(),
                reservation.getSeatId(),
                reservation.getStatus().name(),
                reservation.getReservedAt(),
                reservation.getExpiredAt()
        );
    }
}
