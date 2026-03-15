package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.Reservation;
import com.koesc.ci_cd_test_app.domain.ReservationStatus;
import com.koesc.ci_cd_test_app.implement.mapper.ReservationMapper;
import com.koesc.ci_cd_test_app.storage.entity.ReservationEntity;
import com.koesc.ci_cd_test_app.storage.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 저장소 접근 + Domain 변환 + Not Found 처리 담당
 */
@Component
@RequiredArgsConstructor
public class ReservationReader {

    private final ReservationRepository reservationRepository;
    private final ReservationMapper reservationMapper;

    /**
     * 예약 단건 조회 (Domain)
     */
    public Reservation read(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .map(reservationMapper::toDomain)
                .orElseThrow(() -> new IllegalArgumentException("예약을 찾을 수 없습니다. reservationId = " + reservationId));
    }

    /**
     * 예약 단건 조회 (Entity)
     * - Writer에서 Dirty Checking / saveAndFlush 용도로 사용
     */
    public ReservationEntity readEntity(Long reservationId) {
        return reservationRepository.findById(reservationId)
                .orElseThrow(() -> new IllegalArgumentException("예약 Entity를 찾을 수 없습니다. reservationId = " + reservationId));
    }

    /**
     * 내 예약 목록 조회
     */
    public Page<Reservation> readByUserId(Long userId, Pageable pageable) {
        return reservationRepository.findByUserId(userId, pageable)
                .map(reservationMapper::toDomain);
    }

    /**
     * 내 예약 목록 상태별 조회
     */
    public Page<Reservation> readByUserIdAndStatus(Long userId, ReservationStatus status, Pageable pageable) {
        return reservationRepository.findByUserIdAndStatus(userId, status, pageable)
                .map(reservationMapper::toDomain);
    }

    /**
     * 만료 대상 예약 조회
     */
    public List<Reservation> readExpiredPendingReservations(LocalDateTime now) {
        return reservationRepository.findAllByStatusAndExpiredAtBefore(ReservationStatus.PENDING, now)
                .stream()
                .map(reservationMapper::toDomain)
                .toList();
    }
}
