package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.api.request.SeatRequestDTO;
import com.koesc.ci_cd_test_app.api.response.SeatResponseDTO;
import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.implement.manager.SeatManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SeatService {

    private final SeatManager seatManager;

    /**
     * 특정 스케줄의 예약 가능 좌석 목록 조회
     * readOnly = true : 대규모 조회 시 하이버네이트 세션 플러시를 건너뛰어 성능 향상
     */
    @Transactional(readOnly = true)
    public List<SeatResponseDTO> getAvailableSeats(Long scheduleId) {
        return seatManager.getAvailableSeats(scheduleId).stream()
                .map(SeatResponseDTO::from)
                .toList();
    }

    /**
     * 좌석 점유 (Hold)
     * Holder에서 트랜잭션 단위 처리
     */
    public SeatResponseDTO holdSeat(SeatRequestDTO request) {
        log.info("[SeatService] Attempting to hold seatId: {}", request.seatId());

        // Manager를 통한 비즈니스 로직 조립 수행
        Seat heldSeat = seatManager.holdSeat(request.seatId());

        log.info("[SeatService] Successfully held seatId: {}", heldSeat.getSeatId());

        return SeatResponseDTO.from(heldSeat);
    }
}
