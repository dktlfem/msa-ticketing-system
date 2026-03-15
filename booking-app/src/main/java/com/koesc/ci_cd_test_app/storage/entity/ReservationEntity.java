package com.koesc.ci_cd_test_app.storage.entity;

import com.koesc.ci_cd_test_app.domain.ReservationStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "reservations",
        schema = "ticketing_booking",
        indexes = {
                @Index(name = "idx_user_id", columnList = "user_id"),
                @Index(name = "idx_status_expired", columnList = "status, expired_at")
        }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class ReservationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_id")
    private Long reservationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "seat_id", nullable = false)
    private Long seatId;

    // DDL 기본 값이 PENDING 이라 @Builder.Default + @PrePersist 둘 다 반영
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ReservationStatus status = ReservationStatus.PENDING;

    @Column(name = "reserved_at", updatable = false)
    private LocalDateTime reservedAt;

    @Column(name = "expired_at", nullable = false)
    private LocalDateTime expiredAt;

    // DDL 기본 값이 PENDING 이라 @Builder.Default + @PrePersist 둘 다 반영
    @PrePersist
    public void prePersist() {
        if (this.status == null) {
            this.status = ReservationStatus.PENDING;
        }

        if (this.reservedAt == null) {
            this.reservedAt = LocalDateTime.now();
        }
    }

    public void changeStatus(ReservationStatus status) {
        this.status = status;
    }
}
