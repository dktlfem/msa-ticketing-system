package com.koesc.ci_cd_test_app.implement.scheduler;

import com.koesc.ci_cd_test_app.domain.Payment;
import com.koesc.ci_cd_test_app.domain.PaymentStatus;
import com.koesc.ci_cd_test_app.implement.client.TossPaymentsClient;
import com.koesc.ci_cd_test_app.implement.client.TossPaymentsClient.TossCancelResponse;
import com.koesc.ci_cd_test_app.implement.reader.PaymentReader;
import com.koesc.ci_cd_test_app.implement.writer.PaymentWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * CancelFailedRetryScheduler 단위 테스트.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("CancelFailedRetryScheduler 단위 테스트")
class CancelFailedRetrySchedulerTest {

    @Mock private PaymentReader paymentReader;
    @Mock private PaymentWriter paymentWriter;
    @Mock private TossPaymentsClient tossPaymentsClient;

    @InjectMocks
    private CancelFailedRetryScheduler scheduler;

    @Test
    @DisplayName("CANCEL_FAILED 건이 없으면 PG API를 호출하지 않는다")
    void retryCancelFailed_noFailedPayments_shouldSkip() {
        // given
        given(paymentReader.readAllByStatus(PaymentStatus.CANCEL_FAILED))
                .willReturn(Collections.emptyList());

        // when
        scheduler.retryCancelFailedPayments();

        // then
        verify(tossPaymentsClient, never()).cancelPayment(anyString(), anyString());
    }

    @Test
    @DisplayName("CANCEL_FAILED 건에 대해 PG 취소 재시도 성공 시 REFUNDED로 전이한다")
    void retryCancelFailed_pgRetrySuccess_shouldUpdateToRefunded() {
        // given
        Payment cancelFailedPayment = Payment.builder()
                .paymentId(10L)
                .reservationId(100L)
                .userId(1L)
                .orderId("RES100_1711700000000")
                .paymentKey("toss_pk_test_abc123")
                .amount(new BigDecimal("55000"))
                .status(PaymentStatus.CANCEL_FAILED)
                .build();

        given(paymentReader.readAllByStatus(PaymentStatus.CANCEL_FAILED))
                .willReturn(List.of(cancelFailedPayment));

        TossCancelResponse cancelResponse = new TossCancelResponse(
                "toss_pk_test_abc123", "RES100_1711700000000",
                "CANCELED", OffsetDateTime.now());
        given(tossPaymentsClient.cancelPayment(eq("toss_pk_test_abc123"), anyString()))
                .willReturn(cancelResponse);

        Payment refunded = cancelFailedPayment.toBuilder()
                .status(PaymentStatus.REFUNDED)
                .cancelledAt(LocalDateTime.now())
                .build();
        given(paymentWriter.updateToRefunded(eq(10L), any(LocalDateTime.class)))
                .willReturn(refunded);

        // when
        scheduler.retryCancelFailedPayments();

        // then: PG 취소 API 호출됨
        verify(tossPaymentsClient).cancelPayment(eq("toss_pk_test_abc123"),
                eq("CANCEL_FAILED 자동 복구 재시도"));
        // then: REFUNDED로 상태 전이
        verify(paymentWriter).updateToRefunded(eq(10L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("PG 취소 재시도가 실패해도 다른 건은 계속 처리한다 (단건 실패 격리)")
    void retryCancelFailed_partialFailure_shouldContinueProcessing() {
        // given: 2건의 CANCEL_FAILED
        Payment payment1 = Payment.builder()
                .paymentId(10L).paymentKey("pk_fail").status(PaymentStatus.CANCEL_FAILED).build();
        Payment payment2 = Payment.builder()
                .paymentId(20L).paymentKey("pk_success").status(PaymentStatus.CANCEL_FAILED).build();

        given(paymentReader.readAllByStatus(PaymentStatus.CANCEL_FAILED))
                .willReturn(List.of(payment1, payment2));

        // given: 첫 번째 건은 PG 재시도 실패
        given(tossPaymentsClient.cancelPayment(eq("pk_fail"), anyString()))
                .willThrow(new RuntimeException("PG 장애 지속 중"));

        // given: 두 번째 건은 PG 재시도 성공
        TossCancelResponse cancelResponse = new TossCancelResponse(
                "pk_success", "ORD2", "CANCELED", OffsetDateTime.now());
        given(tossPaymentsClient.cancelPayment(eq("pk_success"), anyString()))
                .willReturn(cancelResponse);
        given(paymentWriter.updateToRefunded(eq(20L), any(LocalDateTime.class)))
                .willReturn(payment2.toBuilder().status(PaymentStatus.REFUNDED).build());

        // when
        scheduler.retryCancelFailedPayments();

        // then: 두 건 모두 PG 취소를 시도함
        verify(tossPaymentsClient).cancelPayment(eq("pk_fail"), anyString());
        verify(tossPaymentsClient).cancelPayment(eq("pk_success"), anyString());

        // then: 두 번째 건만 REFUNDED로 전이
        verify(paymentWriter, never()).updateToRefunded(eq(10L), any(LocalDateTime.class));
        verify(paymentWriter).updateToRefunded(eq(20L), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("paymentKey가 없는 CANCEL_FAILED 건은 건너뛴다 (수동 처리 대상)")
    void retryCancelFailed_noPaymentKey_shouldSkip() {
        // given: paymentKey가 null인 비정상 건
        Payment noKeyPayment = Payment.builder()
                .paymentId(10L).paymentKey(null).status(PaymentStatus.CANCEL_FAILED).build();

        given(paymentReader.readAllByStatus(PaymentStatus.CANCEL_FAILED))
                .willReturn(List.of(noKeyPayment));

        // when
        scheduler.retryCancelFailedPayments();

        // then: PG API를 호출하지 않음 (paymentKey 없이는 취소 불가)
        verify(tossPaymentsClient, never()).cancelPayment(anyString(), anyString());
        verify(paymentWriter, never()).updateToRefunded(anyLong(), any(LocalDateTime.class));
    }
}
