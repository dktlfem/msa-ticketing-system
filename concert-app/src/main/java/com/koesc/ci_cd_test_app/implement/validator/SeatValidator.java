package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.domain.SeatStatus;
import com.koesc.ci_cd_test_app.storage.repository.SeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SeatValidator {

    private final SeatRepository seatRepository;

    /**
     * 좌석이 점유 가능한 상태(AVAILABLE)인지 검증
     */
    public void validateAvailable(Seat seat) {
        if (seat == null) {
            throw new IllegalArgumentException("검증할 좌석 정보가 없습니다.");
        }

        if (seat.getStatus() != SeatStatus.AVAILABLE) {
            throw new IllegalArgumentException("이미 선택되었거나 판매 완료된 좌석입니다. (좌석번호: " + seat.getSeatNo() + ")");
        }
    }

    /**
     * TODO: 특정 유저가 해당 좌석을 점유할 권한이 있는지
     */
}
