package com.koesc.ci_cd_test_app.storage.entity;

import com.koesc.ci_cd_test_app.domain.SeatStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 낙관전 락(Optimistic Lock) : 실제 DB에 락(Pessimistic Lock)을 걸어버리면
 * 다른 사람들은 조회조차 못 하고 줄을 서야 해서 시스템이 매우 느려짐.
 * 낙관적 락은 "설마 동시에 수정하겠어?"라는 낙관적인 태도로 일단 진행하되,
 * 충돌 시에만 에러를 뱉어 성능(Throughput)을 챙기는 방식
 */
@Entity
@Table(
        name = "seats",
        schema = "ticketing_concert",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_schedule_seat", columnNames = {"schedule_id", "seat_no"})
        }
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class SeatEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seat_id")
    private Long seatId;

    @Column(name = "schedule_id", nullable = false)
    private Long scheduleId;

    @Column(name = "seat_no", nullable = false)
    private Integer seatNo;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    /**
     * [핵심] 낙관적 락(Optimistic Lock) 버전 필드
     * JPA가 update 쿼리를 날릴 때 "WHERE version = ?" 조건을 자동으로 붙여준다.
     * 다른 트랙잭션이 먼저 수정했다면 version이 올라가있어 수정에 실패(OptimisticLockException)하게 됨.
     */
    @Version
    private Long version;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "update_at")
    private LocalDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public void changeStatus(SeatStatus status) {
        this.status = status;
    }
}
