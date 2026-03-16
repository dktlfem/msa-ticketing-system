package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.domain.Payment;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

@Component
public class PaymentValidator {

    public void validateConfirmable(Payment payment) {
        if (!payment.isReady()) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS,
                    "결제 승인 가능 상태가 아닙니다. 현재 상태: " + payment.getStatus());
        }
    }

    public void validateCancellable(Payment payment) {
        if (!payment.isApproved()) {
            throw new BusinessException(ErrorCode.PAYMENT_INVALID_STATUS,
                    "결제 취소 가능 상태가 아닙니다. 현재 상태: " + payment.getStatus());
        }
    }

    /**
     * 클라이언트가 전달한 금액이 저장된 금액과 일치하는지 검증한다.
     * TossPayments는 amount가 다르면 승인을 거부하므로, PG 호출 전에 검증한다.
     */
    public void validateAmount(BigDecimal stored, BigDecimal requested) {
        if (stored.compareTo(requested) != 0) {
            throw new BusinessException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "결제 금액 불일치. 저장된 금액: " + stored + ", 요청 금액: " + requested);
        }
    }
}
