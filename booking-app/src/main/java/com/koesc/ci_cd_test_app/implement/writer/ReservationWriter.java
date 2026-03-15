package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.Reservation;
import com.koesc.ci_cd_test_app.implement.mapper.ReservationMapper;
import com.koesc.ci_cd_test_app.storage.entity.ReservationEntity;
import com.koesc.ci_cd_test_app.storage.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ReservationWriter {

    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;

    /**
     * 신규 저장
     */
    public Reservation save(Reservation reservation) {
        ReservationEntity entity = reservationMapper.toEntity(reservation);
        ReservationEntity savedEntity = reservationRepository.save(entity);
        return reservationMapper.toDomain(savedEntity);
    }

    /**
     * 신규 저장 + Flush
     * - 예약 생성 시 DB 제약 문제를 최대한 빨리 터뜨리기 위해 사용
     */
    public Reservation saveWithFlush(Reservation reservation) {
        ReservationEntity entity = reservationMapper.toEntity(reservation);
        ReservationEntity savedEntity = reservationRepository.saveAndFlush(entity);
        return reservationMapper.toDomain(savedEntity);
    }

    /**
     * 단순 업데이트
     */
    public void update(Reservation reservation, ReservationEntity entity) {
        reservationMapper.updateEntityFromDomain(reservation, entity);
        reservationRepository.save(entity);
    }

    /**
     * 상태 전이 즉시 반영 (saveAndFlush)
     */
    public Reservation updateWithFlush(Reservation reservation, ReservationEntity entity) {
        reservationMapper.updateEntityFromDomain(reservation, entity);
        ReservationEntity savedEntity = reservationRepository.saveAndFlush(entity);
        return reservationMapper.toDomain(savedEntity);
    }
}
