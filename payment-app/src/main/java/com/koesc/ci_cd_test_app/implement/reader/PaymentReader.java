package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.Payment;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.implement.mapper.PaymentMapper;
import com.koesc.ci_cd_test_app.storage.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class PaymentReader {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    @Transactional(readOnly = true)
    public Payment read(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .map(paymentMapper::toDomain)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Payment readByOrderId(String orderId) {
        return paymentRepository.findByOrderId(orderId)
                .map(paymentMapper::toDomain)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public Payment readByPaymentKey(String paymentKey) {
        return paymentRepository.findByPaymentKey(paymentKey)
                .map(paymentMapper::toDomain)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public boolean existsByReservationId(Long reservationId) {
        return paymentRepository.existsByReservationId(reservationId);
    }

    /**
     * 특정 상태의 결제 목록 조회.
     * CANCEL_FAILED 복구 스케줄러에서 사용한다.
     */
    @Transactional(readOnly = true)
    public java.util.List<Payment> readAllByStatus(com.koesc.ci_cd_test_app.domain.PaymentStatus status) {
        return paymentRepository.findAllByStatus(status).stream()
                .map(paymentMapper::toDomain)
                .toList();
    }
}
