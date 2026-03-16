package com.koesc.ci_cd_test_app.implement.client;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public interface TossPaymentsClient {

    TossConfirmResponse confirmPayment(String paymentKey, String orderId, BigDecimal amount);

    TossCancelResponse cancelPayment(String paymentKey, String cancelReason);

    record TossConfirmResponse(
            String paymentKey,
            String orderId,
            String status,
            String method,
            BigDecimal totalAmount,
            OffsetDateTime approvedAt
    ) {}

    record TossCancelResponse(
            String paymentKey,
            String orderId,
            String status,
            OffsetDateTime requestedAt
    ) {}
}
