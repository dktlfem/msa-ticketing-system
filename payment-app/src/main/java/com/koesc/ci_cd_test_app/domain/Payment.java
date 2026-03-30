package com.koesc.ci_cd_test_app.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Payment {

    private Long paymentId;
    private Long reservationId;
    private Long userId;
    private String orderId;
    private String paymentKey;
    private BigDecimal amount;

    @Builder.Default
    private PaymentStatus status = PaymentStatus.READY;

    private String method;
    private String failReason;
    private String pgResponseRaw;
    private LocalDateTime approvedAt;
    private LocalDateTime cancelledAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public boolean isReady() {
        return status == PaymentStatus.READY;
    }

    public boolean isApproved() {
        return status == PaymentStatus.APPROVED;
    }

    /**
     * READY → APPROVED
     * PG 승인 성공 시 호출한다.
     */
    public Payment approve(String paymentKey, String method, LocalDateTime approvedAt, String pgResponseRaw) {
        if (!isReady()) {
            throw new IllegalStateException("Cannot approve payment in status: " + status);
        }
        return toBuilder()
                .status(PaymentStatus.APPROVED)
                .paymentKey(paymentKey)
                .method(method)
                .approvedAt(approvedAt)
                .pgResponseRaw(pgResponseRaw)
                .build();
    }

    /**
     * READY → FAILED
     * PG 승인 실패 시 호출한다.
     */
    public Payment fail(String reason) {
        if (!isReady()) {
            throw new IllegalStateException("Cannot fail payment in status: " + status);
        }
        return toBuilder()
                .status(PaymentStatus.FAILED)
                .failReason(reason)
                .build();
    }

    /**
     * APPROVED 또는 CANCEL_FAILED → REFUNDED
     * PG 취소 성공 시 호출한다.
     * CANCEL_FAILED 상태에서도 스케줄러 재시도로 복구 가능하다.
     */
    public Payment completeCancel(LocalDateTime cancelledAt) {
        if (!isApproved() && !isCancelFailed()) {
            throw new IllegalStateException("Cannot cancel payment in status: " + status);
        }
        return toBuilder()
                .status(PaymentStatus.REFUNDED)
                .cancelledAt(cancelledAt)
                .build();
    }

    public boolean isCancelFailed() {
        return status == PaymentStatus.CANCEL_FAILED;
    }

    /**
     * APPROVED → CANCEL_FAILED
     * PG 취소 실패 시 호출한다. 수동 개입이 필요한 상태.
     */
    public Payment failCancel() {
        if (!isApproved()) {
            throw new IllegalStateException("Cannot fail cancel in status: " + status);
        }
        return toBuilder()
                .status(PaymentStatus.CANCEL_FAILED)
                .build();
    }
}
