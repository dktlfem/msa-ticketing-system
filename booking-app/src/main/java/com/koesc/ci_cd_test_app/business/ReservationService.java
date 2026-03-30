package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.domain.Reservation;
import com.koesc.ci_cd_test_app.domain.ReservationStatus;
import com.koesc.ci_cd_test_app.implement.manager.ReservationManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final ReservationManager reservationManager;

    /**
     * 예약 생성
     */
    @Transactional
    public Reservation createReservation(Long userId, String waitingToken, Long seatId) {
        return reservationManager.createReservation(userId, waitingToken, seatId);
    }

    /**
     * 예약 상세 조회 (외부 - 본인)
     */
    @Transactional(readOnly = true)
    public Reservation getReservation(Long reservationId, Long userId) {
        return reservationManager.getReservation(reservationId, userId);
    }

    /**
     * 예약 상세 조회 (내부)
     */
    @Transactional(readOnly = true)
    public Reservation getReservationInternal(Long reservationId) {
        return reservationManager.getReservation(reservationId);
    }

    /**
     * 내 예약 목록 조회
     * - status가 null이면 전체 조회
     * - status가 있으면 상태별 조회
     */
    @Transactional(readOnly = true)
    public Page<Reservation> getMyReservations(Long userId, ReservationStatus status, Pageable pageable) {
        if (status == null) {
            return reservationManager.getReservationsByUserId(userId, pageable);
        }
        return reservationManager.getReservationsByUserIdAndStatus(userId, status, pageable);
    }

    /**
     * 예약 취소
     */
    @Transactional
    public Reservation cancelReservation(Long reservationId, Long userId) {
        return reservationManager.cancelReservation(reservationId, userId);
    }

    /**
     * 예약 확정 (내부)
     */
    @Transactional
    public Reservation confirmReservation(Long reservationId, Long paymentId) {
        return reservationManager.confirmReservation(reservationId, paymentId);
    }

    /**
     * 예약 만료 처리 (내부 단건)
     */
    @Transactional
    public Reservation expireReservation(Long reservationId) {
        return reservationManager.expireReservation(reservationId);
    }

    /**
     * 예약 만료 배치 처리 (스케줄러용)
     * - 만료 대상이 여러 건일 수 있으므로 루프 처리
     * - 한 건 실패로 전체 중단되지 않도록 try-catch 구조를 둘 수도 있음
     */
    @Transactional
    public int expiredPendingReservations() {
        List<Reservation> expiredReservations =
                reservationManager.getExpiredPendingReservations(LocalDateTime.now());

        int processedCount = 0;

        for (Reservation reservation : expiredReservations) {
            try {
                reservationManager.expireReservation(reservation.getReservationId());
                processedCount++;
            } catch (RuntimeException e) {
                log.warn("[Reservation] 만료 처리 실패 — reservationId={}, reason={}",
                        reservation.getReservationId(), e.getMessage(), e);
            }
        }

        return processedCount;
    }
}
