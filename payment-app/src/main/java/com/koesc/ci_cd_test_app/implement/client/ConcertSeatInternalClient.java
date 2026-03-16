package com.koesc.ci_cd_test_app.implement.client;

import java.math.BigDecimal;

public interface ConcertSeatInternalClient {

    SeatDetail readSeat(Long seatId);

    record SeatDetail(
            Long seatId,
            Long scheduleId,
            Long eventId,
            String seatNo,
            BigDecimal price,
            String status
    ) {}
}
