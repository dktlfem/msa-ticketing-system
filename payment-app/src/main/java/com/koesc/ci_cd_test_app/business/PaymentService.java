package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.domain.Payment;
import com.koesc.ci_cd_test_app.implement.manager.PaymentManager;
import com.koesc.ci_cd_test_app.implement.reader.PaymentReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentManager paymentManager;
    private final PaymentReader paymentReader;

    public Payment requestPayment(Long userId, Long reservationId) {
        return paymentManager.createPaymentRequest(userId, reservationId);
    }

    public Payment confirmPayment(String orderId, String paymentKey, BigDecimal amount) {
        return paymentManager.confirmPayment(orderId, paymentKey, amount);
    }

    public Payment cancelPayment(String paymentKey, String cancelReason) {
        return paymentManager.cancelPayment(paymentKey, cancelReason);
    }

    public Payment getPayment(Long paymentId) {
        return paymentReader.read(paymentId);
    }
}
