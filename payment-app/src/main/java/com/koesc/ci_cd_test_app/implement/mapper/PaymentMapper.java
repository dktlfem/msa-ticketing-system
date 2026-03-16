package com.koesc.ci_cd_test_app.implement.mapper;

import com.koesc.ci_cd_test_app.domain.Payment;
import com.koesc.ci_cd_test_app.storage.entity.PaymentEntity;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {

    public Payment toDomain(PaymentEntity entity) {
        return Payment.builder()
                .paymentId(entity.getPaymentId())
                .reservationId(entity.getReservationId())
                .userId(entity.getUserId())
                .orderId(entity.getOrderId())
                .paymentKey(entity.getPaymentKey())
                .amount(entity.getAmount())
                .status(entity.getStatus())
                .method(entity.getMethod())
                .failReason(entity.getFailReason())
                .pgResponseRaw(entity.getPgResponseRaw())
                .approvedAt(entity.getApprovedAt())
                .cancelledAt(entity.getCancelledAt())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public PaymentEntity toEntity(Payment domain) {
        return PaymentEntity.builder()
                .reservationId(domain.getReservationId())
                .userId(domain.getUserId())
                .orderId(domain.getOrderId())
                .paymentKey(domain.getPaymentKey())
                .amount(domain.getAmount())
                .status(domain.getStatus())
                .method(domain.getMethod())
                .failReason(domain.getFailReason())
                .pgResponseRaw(domain.getPgResponseRaw())
                .approvedAt(domain.getApprovedAt())
                .cancelledAt(domain.getCancelledAt())
                .build();
    }
}
