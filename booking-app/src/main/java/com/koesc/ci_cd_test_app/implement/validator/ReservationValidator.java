package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.domain.Reservation;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 1. 예약 상태 전이 정책
 *
 * 생성 시 userId, seatId 필수
 * 예약은 기본 5분 후 만료
 * 취소/확정은 PENDING 이고 아직 안 만료된 예약만 가능
 * 만료 처리는 PENDING 이고 이미 만료된 예약만 가능
 * 본인 예약만 조회/취소 가능
 *
 *
 * 2. 지금은 컴파일 우선으로 IllegalArgumentException, IllegalStateException 으로 작성함
 * 추후에 common-module 예외 스펙에 맞춰서 아래와 같은 식으로 치환하면 됨.
 * - "ReservationNotFoundException"
 * - "ReservationExpiredException"
 * - "ReservationNotOwnedException"
 */
@Component
public class ReservationValidator {

    private final Clock clock;

    public ReservationValidator(Clock clock) {
        this.clock = clock;
    }

    public void validateCreateRequest(Long userId, Long seatId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("유효한 userId가 필요합니다.");
        }

        if (seatId == null || seatId <= 0) {
            throw new IllegalArgumentException("유효한 seatId가 필요합니다.");
        }
    }

    public LocalDateTime calculateExpiredAt(long holdMinutes) {
        return LocalDateTime.now(clock).plusMinutes(holdMinutes);
    }

    public void validateOwner(Reservation reservation, Long userId) {
        if (!reservation.getUserId().equals(userId)) {
            throw new IllegalStateException("본인 예약만 접근할 수 있습니다. reservationId = " + reservation.getReservationId());
        }
    }

    public void validateCancellable(Reservation reservation) {
        validatePending(reservation);
        validateNotExpired(reservation);
    }

    public void validateConfirmable(Reservation reservation) {
        validatePending(reservation);
        validateNotExpired(reservation);
    }

    public void validateExpirable(Reservation reservation) {
        validatePending(reservation);

        LocalDateTime now = LocalDateTime.now(clock);
        if (!reservation.isExpired(now)) {
            throw new IllegalStateException("아직 만료되지 않은 예약입니다. reservationId = " + reservation.getReservationId());
        }
    }

    private void validatePending(Reservation reservation) {
        if (!reservation.isPending()) {
            throw new IllegalStateException(
                    "PENDING 상태 예약만 처리할 수 있습니다. currentStatus = " + reservation.getStatus()
            );
        }
    }

    private void validateNotExpired(Reservation reservation) {
        LocalDateTime now = LocalDateTime.now(clock);

        if (reservation.isExpired(now)) {
            throw new IllegalStateException("이미 만료된 예약입니다. reservationId = " + reservation.getReservationId());
        }
    }
}
