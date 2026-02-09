package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.implement.mapper.SeatMapper;
import com.koesc.ci_cd_test_app.storage.entity.SeatEntity;
import com.koesc.ci_cd_test_app.storage.repository.SeatRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SeatWriter {

    private final SeatRepository seatRepository;
    private final SeatMapper seatMapper;

    /**
     * 단순 업데이트 (Dirty Checking 활용 가능)
     */
    public void update(Seat seat, SeatEntity entity) {
        seatMapper.updateEntityFromDomain(seat, entity);
        seatRepository.save(entity);
    }

    /**
     * 낙관적 락 조기 검출을 위한 Flush 포함 업데이트
     */
    public Seat updateWithFlush(Seat seat, SeatEntity entity) {

        // Domain -> Entity
        seatMapper.updateEntityFromDomain(seat, entity);

        // 실제 저장 및 Flush (여기서 충돌 시 Exception 발생)
        SeatEntity savedEntity = seatRepository.saveAndFlush(entity);

        return seatMapper.toDomain(savedEntity);
    }
}
