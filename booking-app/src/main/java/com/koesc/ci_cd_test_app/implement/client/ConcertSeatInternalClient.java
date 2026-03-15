package com.koesc.ci_cd_test_app.implement.client;

import java.math.BigDecimal;

/**
 * booking-app에서 concert-app의 좌석 내부 API를 호출할 때 쓰는 인터페이스
 */
public interface ConcertSeatInternalClient {

    ConcertSeatDetail readSeat(Long seatId);

    SeatCommandResult holdSeat(Long seatId);

    SeatCommandResult releaseSeat(Long seatId);

    SeatCommandResult confirmSeat(Long seatId);

    record ConcertSeatDetail(
            Long seatId,
            Long scheduleId,
            Long eventId,
            Integer seatNo,
            BigDecimal price,
            String status,
            Long version
    ) {
    }

    record SeatCommandResult(
            Long seatId,
            Long scheduleId,
            String status,
            Long version
    ) {
    }

    /**
     * 좌석 임시 점유
     * AVAILABLE -> HOLD
     */
    //void holdSeat(Long seatId);

    /**
     * 좌석 점유 해제
     * HOLD -> AVAILABLE
     */
    //void releaseSeat(Long seatId);

    /**
     * 좌석 판매 확정
     * HOLD -> SOLD
     */
    //void confirmSeat(Long seatId);
}
