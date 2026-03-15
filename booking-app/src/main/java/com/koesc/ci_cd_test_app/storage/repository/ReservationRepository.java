package com.koesc.ci_cd_test_app.storage.repository;

import com.koesc.ci_cd_test_app.domain.ReservationStatus;
import com.koesc.ci_cd_test_app.storage.entity.ReservationEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRepository extends JpaRepository<ReservationEntity, Long> {

    // 본인 예약 단건 조회
    Optional<ReservationEntity> findByReservationIdAndUserId(Long reservationId, Long userId);

    // 내 예약 목록 조회
    Page<ReservationEntity> findByUserId(Long userId, Pageable pageable);

    // 내 예약 목록 상태별 조회
    Page<ReservationEntity> findByUserIdAndStatus(Long userId, ReservationStatus status, Pageable pageable);

    // 예약 만료 스케줄러 대상 조회
    List<ReservationEntity> findAllByStatusAndExpiredAtBefore(ReservationStatus status, LocalDateTime now);
}
