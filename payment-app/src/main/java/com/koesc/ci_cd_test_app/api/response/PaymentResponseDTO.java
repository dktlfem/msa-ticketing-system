package com.koesc.ci_cd_test_app.api.response;

import com.koesc.ci_cd_test_app.domain.Payment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentResponseDTO(
        Long paymentId,
        Long reservationId,
        String orderId,
        String paymentKey,
        BigDecimal amount,
        String status,
        String method,
        LocalDateTime approvedAt,
        LocalDateTime cancelledAt
) {
    public static PaymentResponseDTO from(Payment payment) {
        return new PaymentResponseDTO(
                payment.getPaymentId(),
                payment.getReservationId(),
                payment.getOrderId(),
                payment.getPaymentKey(),
                payment.getAmount(),
                payment.getStatus().name(),
                payment.getMethod(),
                payment.getApprovedAt(),
                payment.getCancelledAt()
        );
    }
}
