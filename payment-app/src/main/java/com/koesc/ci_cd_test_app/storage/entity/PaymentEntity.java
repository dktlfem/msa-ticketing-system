package com.koesc.ci_cd_test_app.storage.entity;

import com.koesc.ci_cd_test_app.domain.PaymentStatus;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "payments",
        schema = "ticketing_payment",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_reservation_id", columnNames = "reservation_id"),
                @UniqueConstraint(name = "uk_order_id", columnNames = "order_id")
        },
        indexes = {
                @Index(name = "idx_user_id", columnList = "user_id"),
                @Index(name = "idx_status", columnList = "status")
        }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long paymentId;

    @Column(name = "reservation_id", nullable = false)
    private Long reservationId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "order_id", nullable = false, length = 64)
    private String orderId;

    // PG 승인 전까지 null. UK는 null 중복 허용
    @Column(name = "payment_key", length = 200)
    private String paymentKey;

    @Column(name = "amount", nullable = false, precision = 10, scale = 0)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "method", length = 20)
    private String method;

    @Column(name = "fail_reason", length = 500)
    private String failReason;

    // PG 응답 원문 보존 - 감사(audit) 및 PG 분쟁 대응용
    @Column(name = "pg_response_raw", columnDefinition = "TEXT")
    private String pgResponseRaw;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public void applyApproval(String paymentKey, String method, LocalDateTime approvedAt, String pgResponseRaw) {
        this.paymentKey = paymentKey;
        this.method = method;
        this.approvedAt = approvedAt;
        this.pgResponseRaw = pgResponseRaw;
        this.status = PaymentStatus.APPROVED;
    }

    public void applyFail(String failReason) {
        this.failReason = failReason;
        this.status = PaymentStatus.FAILED;
    }

    public void applyCancel(LocalDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
        this.status = PaymentStatus.REFUNDED;
    }

    public void changeStatus(PaymentStatus status) {
        this.status = status;
    }
}
