package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.implement.reader.SeatReader;
import com.koesc.ci_cd_test_app.implement.validator.SeatValidator;
import com.koesc.ci_cd_test_app.implement.writer.SeatWriter;
import com.koesc.ci_cd_test_app.storage.entity.SeatEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SeatManager {

    private final SeatReader seatReader;
    private final SeatWriter seatWriter;
    private final SeatValidator seatValidator;
    private final SeatHolder seatHolder; // 동시성 제어 전용 매니저

    /**
     * 예약 가능한 좌석 조회
     */
    public List<Seat> getAvailableSeats(Long scheduleId) {
        return seatReader.readAvailable(scheduleId);
    }

    /**
     * 좌석 점유 (hold)
     * 동시성 제어가 핵심이므로 전용 객체인 SeatHolder에게 위임
     */
    public Seat holdSeat(Long seatId) {
        return seatHolder.hold(seatId);
    }

    /**
     * 좌석 점유 해제 (release)
     */
    public Seat releaseSeat(Long seatId) {
        return seatHolder.release(seatId);
    }
}
