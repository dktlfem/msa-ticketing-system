package com.koesc.ci_cd_test_app.implement.client.impl;

import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.implement.client.TossPaymentsClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Component
public class TossPaymentsClientImpl implements TossPaymentsClient {

    private final RestClient restClient;

    public TossPaymentsClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${toss.payments.secret-key}") String secretKey,
            @Value("${toss.payments.base-url:https://api.tosspayments.com/v1}") String baseUrl) {
        // TossPayments 인증: Basic base64(secretKey:)
        // secretKey 뒤에 콜론(:)이 반드시 포함되어야 한다.
        String encoded = Base64.getEncoder()
                .encodeToString((secretKey + ":").getBytes(StandardCharsets.UTF_8));
        this.restClient = restClientBuilder
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Basic " + encoded)
                .build();
    }

    @Override
    public TossConfirmResponse confirmPayment(String paymentKey, String orderId, BigDecimal amount) {
        log.info("TossPayments confirm request - orderId={}, amount={}", orderId, amount);
        try {
            TossConfirmResponse response = restClient.post()
                    .uri("/payments/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "paymentKey", paymentKey,
                            "orderId", orderId,
                            "amount", amount
                    ))
                    .retrieve()
                    .body(TossConfirmResponse.class);
            log.info("TossPayments confirm success - paymentKey={}, method={}", paymentKey,
                    response != null ? response.method() : "unknown");
            return response;
        } catch (HttpClientErrorException e) {
            log.error("TossPayments confirm failed - orderId={}, httpStatus={}, body={}",
                    orderId, e.getStatusCode(), e.getResponseBodyAsString());
            throw new BusinessException(ErrorCode.PAYMENT_PG_ERROR,
                    "PG 승인 실패: " + e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("TossPayments confirm error - orderId={}", orderId, e);
            throw new BusinessException(ErrorCode.PAYMENT_PG_ERROR, "PG 승인 중 오류 발생");
        }
    }

    @Override
    public TossCancelResponse cancelPayment(String paymentKey, String cancelReason) {
        log.info("TossPayments cancel request - paymentKey={}, reason={}", paymentKey, cancelReason);
        try {
            TossCancelResponse response = restClient.post()
                    .uri("/payments/{paymentKey}/cancel", paymentKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("cancelReason", cancelReason))
                    .retrieve()
                    .body(TossCancelResponse.class);
            log.info("TossPayments cancel success - paymentKey={}", paymentKey);
            return response;
        } catch (HttpClientErrorException e) {
            log.error("TossPayments cancel failed - paymentKey={}, body={}",
                    paymentKey, e.getResponseBodyAsString());
            // 취소 실패는 CANCEL_FAILED 상태 처리를 위해 RuntimeException으로 전파
            throw new RuntimeException("PG 취소 실패: " + e.getResponseBodyAsString(), e);
        } catch (Exception e) {
            log.error("TossPayments cancel error - paymentKey={}", paymentKey, e);
            throw new RuntimeException("PG 취소 중 오류 발생", e);
        }
    }
}
