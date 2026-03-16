package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.Payment;
import com.koesc.ci_cd_test_app.domain.PaymentStatus;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.implement.mapper.PaymentMapper;
import com.koesc.ci_cd_test_app.storage.entity.PaymentEntity;
import com.koesc.ci_cd_test_app.storage.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 결제 상태 변경은 모두 이 클래스를 통해 수행된다.
 * 각 메서드는 독립 트랜잭션으로 동작한다.
 * PaymentManager는 외부 API 호출(PG, booking-app) 사이사이에 이 메서드를 호출한다.
 */
@Component
@RequiredArgsConstructor
public class PaymentWriter {

    private final PaymentRepository paymentRepository;
    private final PaymentMapper paymentMapper;

    /**
     * 신규 결제 레코드 저장. saveAndFlush로 DB 오류를 즉시 감지한다.
     */
    @Transactional
    public Payment save(Payment payment) {
        PaymentEntity entity = paymentMapper.toEntity(payment);
        PaymentEntity saved = paymentRepository.saveAndFlush(entity);
        return paymentMapper.toDomain(saved);
    }

    /**
     * READY → APPROVED
     * PG 승인 성공 후 호출한다.
     */
    @Transactional
    public Payment updateToApproved(Long paymentId, String paymentKey, String method,
                                    LocalDateTime approvedAt, String pgResponseRaw) {
        PaymentEntity entity = findOrThrow(paymentId);
        entity.applyApproval(paymentKey, method, approvedAt, pgResponseRaw);
        paymentRepository.flush();
        return paymentMapper.toDomain(entity);
    }

    /**
     * READY → FAILED
     * PG 승인 실패 시 호출한다.
     */
    @Transactional
    public void updateToFailed(Long paymentId, String failReason) {
        PaymentEntity entity = findOrThrow(paymentId);
        entity.applyFail(failReason);
        paymentRepository.flush();
    }

    /**
     * APPROVED → REFUNDED
     * PG 취소 성공 시 호출한다.
     */
    @Transactional
    public Payment updateToRefunded(Long paymentId, LocalDateTime cancelledAt) {
        PaymentEntity entity = findOrThrow(paymentId);
        entity.applyCancel(cancelledAt);
        paymentRepository.flush();
        return paymentMapper.toDomain(entity);
    }

    /**
     * APPROVED → CANCEL_FAILED
     * PG 취소 실패 시 호출한다. 수동 처리가 필요한 상태.
     */
    @Transactional
    public void updateToCancelFailed(Long paymentId) {
        PaymentEntity entity = findOrThrow(paymentId);
        entity.changeStatus(PaymentStatus.CANCEL_FAILED);
        paymentRepository.flush();
    }

    private PaymentEntity findOrThrow(Long paymentId) {
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.PAYMENT_NOT_FOUND));
    }
}
