package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.implement.mapper.SeatMapper;
import com.koesc.ci_cd_test_app.storage.entity.SeatEntity;
import com.koesc.ci_cd_test_app.storage.repository.SeatRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SeatWriter {

    private final SeatRepository seatRepository;
    private final SeatMapper seatMapper;

    /**
     * 단순 업데이트 (Dirty Checking 활용 가능)
     * SeatService는 seatId + expectedVersion만 넘김.
     */
    public Seat update(Seat seat, Long expectedVersion) {
        SeatEntity entity = findManagedEntity(seat.getSeatId(), expectedVersion);
        seatMapper.updateEntityFromDomain(seat, entity);

        SeatEntity savedEntity = seatRepository.save(entity);
        return seatMapper.toDomain(savedEntity);
    }

    /**
     * 낙관적 락 조기 검출을 위한 Flush 포함 업데이트
     */
    public Seat updateWithFlush(Seat seat, Long expectedVersion) {

        SeatEntity entity = findManagedEntity(seat.getSeatId(), expectedVersion);

        // Domain -> Entity
        seatMapper.updateEntityFromDomain(seat, entity);

        // 실제 저장 및 Flush (여기서 충돌 시 Exception 발생)
        SeatEntity savedEntity = seatRepository.saveAndFlush(entity);

        return seatMapper.toDomain(savedEntity);
    }

    private SeatEntity findManagedEntity(Long seatId, Long expectedVersion) {
        return seatRepository.findBySeatIdAndVersion(seatId, expectedVersion)
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "좌석 상태가 이미 다른 트랜잭션에서 변경되었습니다. seatId = " + seatId
                        + ", expectedVersion = " + expectedVersion
                ));
    }
}
