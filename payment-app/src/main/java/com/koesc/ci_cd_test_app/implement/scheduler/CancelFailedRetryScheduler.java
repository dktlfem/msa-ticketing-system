package com.koesc.ci_cd_test_app.implement.scheduler;

import com.koesc.ci_cd_test_app.domain.Payment;
import com.koesc.ci_cd_test_app.domain.PaymentStatus;
import com.koesc.ci_cd_test_app.implement.client.TossPaymentsClient;
import com.koesc.ci_cd_test_app.implement.reader.PaymentReader;
import com.koesc.ci_cd_test_app.implement.writer.PaymentWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * ADR: CANCEL_FAILED 자동 복구 스케줄러
 *
 * [문제 상황]
 * Saga 보상 트랜잭션 중 PG 취소 API 호출이 실패하면 Payment가 CANCEL_FAILED 상태에 머문다.
 * 이 상태는 "고객 돈이 PG에 묶인 채 예약은 확정되지 않은 상태"로, 수동 개입 없이 방치할 수 없다.
 *
 * [해결 전략]
 * 1. 주기적으로(5분 간격) CANCEL_FAILED 상태 결제를 조회
 * 2. 각 건에 대해 PG 취소 API를 재시도
 * 3. 성공 시 REFUNDED 상태로 전이
 * 4. 재시도도 실패 시 로그를 남기고 다음 주기에서 다시 시도
 *
 * [설계 근거]
 * - 최대 재시도 횟수를 두지 않음: PG 장애가 복구되면 언제든 성공 가능
 * - 5분 간격: PG 장애 복구 시간(통상 수십 분) 대비 적절한 재시도 빈도
 * - 단건 실패 시 나머지 건은 계속 처리: 한 건의 실패가 전체를 막지 않음
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CancelFailedRetryScheduler {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PaymentReader paymentReader;
    private final PaymentWriter paymentWriter;
    private final TossPaymentsClient tossPaymentsClient;

    /**
     * 5분 간격으로 CANCEL_FAILED 결제를 재시도한다.
     * initialDelay: 애플리케이션 기동 후 1분 뒤 첫 실행 (DB 커넥션 안정화 대기)
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void retryCancelFailedPayments() {
        List<Payment> cancelFailedPayments = paymentReader.readAllByStatus(PaymentStatus.CANCEL_FAILED);

        if (cancelFailedPayments.isEmpty()) {
            return;
        }

        log.warn("[CancelFailedRetryScheduler] CANCEL_FAILED 건 {}개 발견, 재시도 시작",
                cancelFailedPayments.size());

        int successCount = 0;
        int failCount = 0;

        for (Payment payment : cancelFailedPayments) {
            try {
                retryCancel(payment);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("[CancelFailedRetryScheduler] 재시도 실패 - paymentId={}, paymentKey={}",
                        payment.getPaymentId(), payment.getPaymentKey(), e);
            }
        }

        log.info("[CancelFailedRetryScheduler] 재시도 완료 - 성공={}, 실패={}, 전체={}",
                successCount, failCount, cancelFailedPayments.size());
    }

    /**
     * 단건 PG 취소 재시도.
     * 성공 시 CANCEL_FAILED → REFUNDED로 전이한다.
     */
    private void retryCancel(Payment payment) {
        String paymentKey = payment.getPaymentKey();
        if (paymentKey == null || paymentKey.isBlank()) {
            log.error("[CancelFailedRetryScheduler] paymentKey 없음 - paymentId={}, 수동 처리 필요",
                    payment.getPaymentId());
            return;
        }

        TossPaymentsClient.TossCancelResponse response =
                tossPaymentsClient.cancelPayment(paymentKey, "CANCEL_FAILED 자동 복구 재시도");

        LocalDateTime cancelledAt = response.requestedAt() != null
                ? response.requestedAt().atZoneSameInstant(KST).toLocalDateTime()
                : LocalDateTime.now();

        paymentWriter.updateToRefunded(payment.getPaymentId(), cancelledAt);

        log.info("[CancelFailedRetryScheduler] 복구 성공 - paymentId={}, paymentKey={} → REFUNDED",
                payment.getPaymentId(), paymentKey);
    }
}
