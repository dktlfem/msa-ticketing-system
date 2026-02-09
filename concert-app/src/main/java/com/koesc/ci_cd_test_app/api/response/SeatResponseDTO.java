package com.koesc.ci_cd_test_app.api.response;

import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.domain.SeatStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "좌석 정보 응답")
public record SeatResponseDTO(
    @Schema(description = "좌석 공유 ID")
    Long seatId,

    @Schema(description = "공연 스케줄 ID")
    Long scheduleId,

    @Schema(description = "좌석 번호")
    Integer seatNo,

    @Schema(description = "좌석 가격")
    BigDecimal price,

    @Schema(description = "좌석 상태 (AVAILABLE, HOLD, SOLD)")
    SeatStatus status
) {
    public static SeatResponseDTO from(Seat seat) {
        return new SeatResponseDTO(
                seat.getSeatId(),
                seat.getScheduleId(),
                seat.getSeatNo(),
                seat.getPrice(),
                seat.getStatus()
        );
    }
}
