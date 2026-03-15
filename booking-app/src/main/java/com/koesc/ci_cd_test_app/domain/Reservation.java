package com.koesc.ci_cd_test_app.domain;

import lombok.*;

import java.time.LocalDateTime;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Reservation {

    private Long reservationId;
    private Long userId;
    private Long seatId;

    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    private LocalDateTime reservedAt;
    private LocalDateTime expiredAt;

    public boolean isPending() {
        return this.status == ReservationStatus.PENDING;
    }

    public boolean isConfirmed() {
        return this.status == ReservationStatus.CONFIRMED;
    }

    public boolean isCancelled() {
        return this.status == ReservationStatus.CANCELLED;
    }

    public boolean isExpired(LocalDateTime now) {
        return !this.expiredAt.isAfter(now); // expiredAt <= now
    }

    public Reservation confirm() {
        return this.toBuilder()
                .status(ReservationStatus.CONFIRMED)
                .build();
    }

    public Reservation cancel() {
        return this.toBuilder()
                .status(ReservationStatus.CANCELLED)
                .build();
    }
}
