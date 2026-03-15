package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.domain.SeatStatus;
import com.koesc.ci_cd_test_app.implement.mapper.SeatMapper;
import com.koesc.ci_cd_test_app.storage.entity.SeatEntity;
import com.koesc.ci_cd_test_app.storage.repository.SeatRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class SeatReader {

    private final SeatRepository seatRepository;
    private final SeatMapper seatMapper;

    /**
     * 도메인 모델로 조회 (비즈니스용)
     */
    public Seat read(Long seatId) {
        return seatRepository.findById(seatId)
                .map(seatMapper::toDomain)
                .orElseThrow(() -> new EntityNotFoundException("해당 좌석을 찾을 수 없습니다. ID: " + seatId));
    }

    /**
     * 특정 스케줄의 예약 가능한 좌석 목록 조회
     */
    public List<Seat> readAvailable(Long scheduleId) {
        return seatRepository.findAllByScheduleIdAndStatus(scheduleId, SeatStatus.AVAILABLE)
                .stream()
                .map(seatMapper::toDomain)
                .toList();
    }
}
