package com.koesc.ci_cd_test_app.implement.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koesc.ci_cd_test_app.domain.Payment;
import com.koesc.ci_cd_test_app.domain.PaymentStatus;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.implement.client.BookingInternalClient;
import com.koesc.ci_cd_test_app.implement.client.ConcertSeatInternalClient;
import com.koesc.ci_cd_test_app.implement.client.TossPaymentsClient;
import com.koesc.ci_cd_test_app.implement.client.TossPaymentsClient.TossCancelResponse;
import com.koesc.ci_cd_test_app.implement.client.TossPaymentsClient.TossConfirmResponse;
import com.koesc.ci_cd_test_app.implement.reader.PaymentReader;
import com.koesc.ci_cd_test_app.implement.validator.PaymentValidator;
import com.koesc.ci_cd_test_app.implement.writer.PaymentWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * PaymentManager Saga 패턴 단위 테스트.
 *
 * [면접 포인트]
 * 이 테스트는 분산 트랜잭션에서 발생 가능한 3가지 실패 시나리오를 증명한다:
 *
 * 시나리오 1: TossPayments PG 승인 실패 → READY → FAILED (보상 불필요, 돈이 빠져나가지 않음)
 * 시나리오 2: booking-app 예약 확정 실패 → APPROVED 후 보상 트랜잭션 → REFUNDED (돈 환불)
 * 시나리오 3: 보상 트랜잭션(PG 취소)마저 실패 → CANCEL_FAILED (수동 개입 필요)
 *
 * 각 시나리오가 데이터 정합성을 보장하는 방법:
 * - 시나리오 1: PG에서 돈이 빠져나가기 전이므로 DB 상태만 FAILED로 변경
 * - 시나리오 2: PG에서 돈이 빠져나간 후이므로 반드시 cancelPayment 호출하여 환불
 * - 시나리오 3: 자동 복구 불가 → CRITICAL 로그 + 수동 개입 알림
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentManager Saga 패턴 단위 테스트")
class PaymentManagerTest {

    @Mock private PaymentReader paymentReader;
    @Mock private PaymentWriter paymentWriter;
    @Mock private PaymentValidator paymentValidator;
    @Mock private BookingInternalClient bookingClient;
    @Mock private ConcertSeatInternalClient concertSeatClient;
    @Mock private TossPaymentsClient tossPaymentsClient;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private PaymentManager paymentManager;

    // 테스트 공통 픽스처
    private static final Long USER_ID = 1L;
    private static final Long RESERVATION_ID = 100L;
    private static final Long PAYMENT_ID = 10L;
    private static final Long SEAT_ID = 50L;
    private static final String ORDER_ID = "RES100_1711700000000";
    private static final String PAYMENT_KEY = "toss_pk_test_abc123";
    private static final BigDecimal AMOUNT = new BigDecimal("55000");

    private Payment readyPayment;

    @BeforeEach
    void setUp() throws Exception {
        readyPayment = Payment.builder()
                .paymentId(PAYMENT_ID)
                .reservationId(RESERVATION_ID)
                .userId(USER_ID)
                .orderId(ORDER_ID)
                .amount(AMOUNT)
                .status(PaymentStatus.READY)
                .build();

        // objectMapper mock: serializeSafe()에서 writeValueAsString 호출 시 null 반환 방지
        // lenient: 모든 테스트에서 사용되지는 않으므로 strict stubbing 경고 방지
        lenient().when(objectMapper.writeValueAsString(any())).thenReturn("{}");
    }

    // ─────────────────────────────────────────────────────────
    // Happy Path: 결제 → 예약 확정 성공
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Happy Path: 정상 결제 흐름")
    class HappyPath {

        @Test
        @DisplayName("PG 승인 → DB APPROVED → booking-app 확정 → 최종 상태 APPROVED")
        void confirmPayment_success_fullSagaFlow() {
            // given
            given(paymentReader.readByOrderId(ORDER_ID)).willReturn(readyPayment);
            // validator는 void이므로 doNothing (기본값)

            TossConfirmResponse pgResponse = new TossConfirmResponse(
                    PAYMENT_KEY, ORDER_ID, "DONE", "카드",
                    AMOUNT, OffsetDateTime.now());
            given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                    .willReturn(pgResponse);

            Payment approvedPayment = readyPayment.approve(
                    PAYMENT_KEY, "카드", LocalDateTime.now(), "{}");
            given(paymentWriter.updateToApproved(eq(PAYMENT_ID), eq(PAYMENT_KEY),
                    eq("카드"), any(LocalDateTime.class), anyString()))
                    .willReturn(approvedPayment);

            // booking-app confirm 성공 (void)
            doNothing().when(bookingClient).confirmReservation(RESERVATION_ID, PAYMENT_ID);

            // when
            Payment result = paymentManager.confirmPayment(ORDER_ID, PAYMENT_KEY, AMOUNT);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.APPROVED);
            verify(tossPaymentsClient).confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);
            verify(paymentWriter).updateToApproved(eq(PAYMENT_ID), eq(PAYMENT_KEY),
                    eq("카드"), any(LocalDateTime.class), anyString());
            verify(bookingClient).confirmReservation(RESERVATION_ID, PAYMENT_ID);
            // 보상 트랜잭션이 호출되지 않았음을 검증
            verify(tossPaymentsClient, never()).cancelPayment(anyString(), anyString());
        }
    }

    // ─────────────────────────────────────────────────────────
    // 시나리오 1: TossPayments PG 승인 실패 → FAILED
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("시나리오 1: TossPayments 승인 실패 → FAILED")
    class Scenario1_PgConfirmFailure {

        @Test
        @DisplayName("PG 승인 거절 시 Payment 상태가 FAILED로 전이되고 BusinessException이 발생한다")
        void confirmPayment_pgReject_shouldUpdateToFailed() {
            // given: PG가 승인을 거절하는 상황 (잔액 부족, 카드 한도 초과 등)
            given(paymentReader.readByOrderId(ORDER_ID)).willReturn(readyPayment);

            BusinessException pgError = new BusinessException(
                    ErrorCode.PAYMENT_PG_ERROR, "카드 잔액이 부족합니다.");
            given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                    .willThrow(pgError);

            // when & then: 예외가 그대로 전파된다
            assertThatThrownBy(() -> paymentManager.confirmPayment(ORDER_ID, PAYMENT_KEY, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> {
                        BusinessException ex = (BusinessException) thrown;
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_PG_ERROR);
                    });

            // then: READY → FAILED 상태 전이 확인
            verify(paymentWriter).updateToFailed(PAYMENT_ID, "카드 잔액이 부족합니다.");

            // then: PG에서 돈이 빠져나가지 않았으므로 보상 트랜잭션(cancelPayment) 불필요
            verify(tossPaymentsClient, never()).cancelPayment(anyString(), anyString());

            // then: booking-app에 confirm 요청도 전달되지 않음
            verify(bookingClient, never()).confirmReservation(anyLong(), anyLong());

            // [면접 포인트]
            // PG 승인 전 실패이므로 고객 돈이 빠져나가지 않는다.
            // Reservation은 여전히 PENDING 상태 → 만료 시 자동 CANCELLED.
            // 즉, 별도 보상 트랜잭션 없이도 데이터 정합성이 유지된다.
        }

        @Test
        @DisplayName("PG 승인 실패 시 DB APPROVED 전이가 발생하지 않는다")
        void confirmPayment_pgReject_shouldNotReachApproved() {
            // given
            given(paymentReader.readByOrderId(ORDER_ID)).willReturn(readyPayment);
            given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                    .willThrow(new BusinessException(ErrorCode.PAYMENT_PG_ERROR));

            // when
            try {
                paymentManager.confirmPayment(ORDER_ID, PAYMENT_KEY, AMOUNT);
            } catch (BusinessException ignored) {
            }

            // then: updateToApproved가 호출되지 않아야 한다
            verify(paymentWriter, never()).updateToApproved(
                    anyLong(), anyString(), anyString(), any(LocalDateTime.class), anyString());
        }
    }

    // ─────────────────────────────────────────────────────────
    // 시나리오 2: booking-app 확정 실패 → 보상 트랜잭션 → REFUNDED
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("시나리오 2: booking-app 확정 실패 → 보상 트랜잭션(PG 환불) → REFUNDED")
    class Scenario2_BookingConfirmFailure {

        private Payment approvedPayment;

        @BeforeEach
        void setUpApprovedPayment() {
            approvedPayment = readyPayment.approve(
                    PAYMENT_KEY, "카드", LocalDateTime.now(), "{}");
        }

        @Test
        @DisplayName("예약 확정 실패 시 PG 취소 API를 호출하고 REFUNDED 상태로 전이한다")
        void confirmPayment_bookingFail_shouldInitiateRefundAndTransitionToRefunded() {
            // given: PG 승인까지는 성공
            given(paymentReader.readByOrderId(ORDER_ID)).willReturn(readyPayment);

            TossConfirmResponse pgResponse = new TossConfirmResponse(
                    PAYMENT_KEY, ORDER_ID, "DONE", "카드", AMOUNT, OffsetDateTime.now());
            given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                    .willReturn(pgResponse);

            given(paymentWriter.updateToApproved(eq(PAYMENT_ID), eq(PAYMENT_KEY),
                    eq("카드"), any(LocalDateTime.class), anyString()))
                    .willReturn(approvedPayment);

            // given: booking-app confirm 실패 (네트워크 오류, 예약 만료 등)
            doThrow(new RuntimeException("Connection refused: booking-app"))
                    .when(bookingClient).confirmReservation(RESERVATION_ID, PAYMENT_ID);

            // given: PG 취소(보상 트랜잭션)는 성공
            TossCancelResponse cancelResponse = new TossCancelResponse(
                    PAYMENT_KEY, ORDER_ID, "CANCELED", OffsetDateTime.now());
            given(tossPaymentsClient.cancelPayment(eq(PAYMENT_KEY), anyString()))
                    .willReturn(cancelResponse);

            Payment refundedPayment = approvedPayment.completeCancel(LocalDateTime.now());
            given(paymentWriter.updateToRefunded(eq(PAYMENT_ID), any(LocalDateTime.class)))
                    .willReturn(refundedPayment);

            // when & then: 최종적으로 RESERVATION_NOT_CONFIRMABLE 예외 발생
            assertThatThrownBy(() -> paymentManager.confirmPayment(ORDER_ID, PAYMENT_KEY, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> {
                        BusinessException ex = (BusinessException) thrown;
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_CONFIRMABLE);
                    });

            // then: Saga 보상 트랜잭션 흐름 검증
            // 1단계: PG 승인 성공
            verify(tossPaymentsClient).confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT);
            // 2단계: DB APPROVED 업데이트
            verify(paymentWriter).updateToApproved(eq(PAYMENT_ID), eq(PAYMENT_KEY),
                    eq("카드"), any(LocalDateTime.class), anyString());
            // 3단계: booking-app 확정 시도 (실패)
            verify(bookingClient).confirmReservation(RESERVATION_ID, PAYMENT_ID);
            // 4단계: 보상 - PG 취소 호출
            verify(tossPaymentsClient).cancelPayment(eq(PAYMENT_KEY),
                    eq("예약 확정 실패로 인한 자동 환불"));
            // 5단계: DB REFUNDED 업데이트
            verify(paymentWriter).updateToRefunded(eq(PAYMENT_ID), any(LocalDateTime.class));

            // [면접 포인트]
            // PG 승인 후 booking-app 확정이 실패하면 "고객 돈이 빠져나간 상태"이므로
            // 반드시 PG cancelPayment를 호출하여 환불해야 한다.
            // 이것이 Saga 패턴의 보상 트랜잭션(Compensating Transaction)이다.
        }

        @Test
        @DisplayName("booking-app이 409 Conflict(예약 만료)를 반환해도 보상 트랜잭션이 실행된다")
        void confirmPayment_bookingConflict_shouldStillRefund() {
            // given: PG 승인 성공
            given(paymentReader.readByOrderId(ORDER_ID)).willReturn(readyPayment);

            TossConfirmResponse pgResponse = new TossConfirmResponse(
                    PAYMENT_KEY, ORDER_ID, "DONE", "카드", AMOUNT, OffsetDateTime.now());
            given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                    .willReturn(pgResponse);
            given(paymentWriter.updateToApproved(eq(PAYMENT_ID), eq(PAYMENT_KEY),
                    eq("카드"), any(LocalDateTime.class), anyString()))
                    .willReturn(approvedPayment);

            // given: booking-app이 비즈니스 예외(예약 만료)를 반환
            doThrow(new BusinessException(ErrorCode.RESERVATION_NOT_CONFIRMABLE,
                    "예약이 만료되었습니다."))
                    .when(bookingClient).confirmReservation(RESERVATION_ID, PAYMENT_ID);

            // given: PG 취소 성공
            TossCancelResponse cancelResponse = new TossCancelResponse(
                    PAYMENT_KEY, ORDER_ID, "CANCELED", OffsetDateTime.now());
            given(tossPaymentsClient.cancelPayment(eq(PAYMENT_KEY), anyString()))
                    .willReturn(cancelResponse);
            given(paymentWriter.updateToRefunded(eq(PAYMENT_ID), any(LocalDateTime.class)))
                    .willReturn(approvedPayment.completeCancel(LocalDateTime.now()));

            // when & then
            assertThatThrownBy(() -> paymentManager.confirmPayment(ORDER_ID, PAYMENT_KEY, AMOUNT))
                    .isInstanceOf(BusinessException.class);

            // then: 어떤 종류의 Exception이든 catch(Exception e)에 걸려 보상이 실행됨
            verify(tossPaymentsClient).cancelPayment(eq(PAYMENT_KEY), anyString());
            verify(paymentWriter).updateToRefunded(eq(PAYMENT_ID), any(LocalDateTime.class));
        }
    }

    // ─────────────────────────────────────────────────────────
    // 시나리오 3: 보상 트랜잭션(PG 취소)마저 실패 → CANCEL_FAILED
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("시나리오 3: CANCEL_FAILED 상태 진입 (보상 트랜잭션 실패)")
    class Scenario3_CancelFailed {

        private Payment approvedPayment;

        @BeforeEach
        void setUpApprovedPayment() {
            approvedPayment = readyPayment.approve(
                    PAYMENT_KEY, "카드", LocalDateTime.now(), "{}");
        }

        @Test
        @DisplayName("booking-app 실패 후 PG 취소까지 실패하면 CANCEL_FAILED 상태로 전이한다")
        void confirmPayment_refundAlsoFails_shouldTransitionToCancelFailed() {
            // given: PG 승인 성공
            given(paymentReader.readByOrderId(ORDER_ID)).willReturn(readyPayment);

            TossConfirmResponse pgResponse = new TossConfirmResponse(
                    PAYMENT_KEY, ORDER_ID, "DONE", "카드", AMOUNT, OffsetDateTime.now());
            given(tossPaymentsClient.confirmPayment(PAYMENT_KEY, ORDER_ID, AMOUNT))
                    .willReturn(pgResponse);
            given(paymentWriter.updateToApproved(eq(PAYMENT_ID), eq(PAYMENT_KEY),
                    eq("카드"), any(LocalDateTime.class), anyString()))
                    .willReturn(approvedPayment);

            // given: booking-app confirm 실패
            doThrow(new RuntimeException("booking-app timeout"))
                    .when(bookingClient).confirmReservation(RESERVATION_ID, PAYMENT_ID);

            // given: PG 취소(보상 트랜잭션)도 실패! → 최악의 시나리오
            given(tossPaymentsClient.cancelPayment(eq(PAYMENT_KEY), anyString()))
                    .willThrow(new RuntimeException("TossPayments API 장애"));

            // when & then: 최종적으로 PAYMENT_PG_ERROR 예외 발생
            assertThatThrownBy(() -> paymentManager.confirmPayment(ORDER_ID, PAYMENT_KEY, AMOUNT))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> {
                        BusinessException ex = (BusinessException) thrown;
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_PG_ERROR);
                        assertThat(ex.getMessage()).contains("환불 처리에 실패");
                    });

            // then: CANCEL_FAILED 상태로 전이됨을 확인
            verify(paymentWriter).updateToCancelFailed(PAYMENT_ID);

            // then: REFUNDED로는 전이되지 않음
            verify(paymentWriter, never()).updateToRefunded(anyLong(), any(LocalDateTime.class));

            // [면접 포인트]
            // 이 상태는 "고객 돈이 PG에 묶인 채 예약은 안 된 상태"이다.
            // 자동 복구가 불가능하므로 [CRITICAL] 로그를 남기고
            // 운영팀이 수동으로 PG 관리자 페이지에서 환불 처리해야 한다.
            // 실제 서비스에서는 AlertManager/PagerDuty 알림을 트리거한다.
        }

        @Test
        @DisplayName("사용자 요청 취소(cancelPayment) 시에도 PG 취소 실패하면 CANCEL_FAILED 진입")
        void cancelPayment_pgCancelFails_shouldTransitionToCancelFailed() {
            // given: APPROVED 상태의 결제를 사용자가 취소 요청
            Payment approvedWithKey = Payment.builder()
                    .paymentId(PAYMENT_ID)
                    .reservationId(RESERVATION_ID)
                    .userId(USER_ID)
                    .orderId(ORDER_ID)
                    .paymentKey(PAYMENT_KEY)
                    .amount(AMOUNT)
                    .status(PaymentStatus.APPROVED)
                    .build();

            given(paymentReader.readByPaymentKey(PAYMENT_KEY)).willReturn(approvedWithKey);
            // validator.validateCancellable은 APPROVED 상태이므로 통과 (void)

            // given: PG 취소 실패
            given(tossPaymentsClient.cancelPayment(eq(PAYMENT_KEY), anyString()))
                    .willThrow(new RuntimeException("TossPayments 점검 중"));

            // when & then
            assertThatThrownBy(() -> paymentManager.cancelPayment(PAYMENT_KEY, "사용자 변심"))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> {
                        BusinessException ex = (BusinessException) thrown;
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_PG_ERROR);
                    });

            // then: CANCEL_FAILED 상태 전이
            verify(paymentWriter).updateToCancelFailed(PAYMENT_ID);
            verify(paymentWriter, never()).updateToRefunded(anyLong(), any(LocalDateTime.class));
        }
    }

    // ─────────────────────────────────────────────────────────
    // 결제 요청 생성 (createPaymentRequest) 테스트
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("결제 요청 생성 (createPaymentRequest)")
    class CreatePaymentRequest {

        @Test
        @DisplayName("PENDING 상태 예약에 대해 READY 상태 결제를 생성한다")
        void createPaymentRequest_success() {
            // given
            BookingInternalClient.ReservationDetail reservation =
                    new BookingInternalClient.ReservationDetail(
                            RESERVATION_ID, USER_ID, SEAT_ID, "PENDING", "2026-03-30T00:00:00");
            given(bookingClient.readReservation(RESERVATION_ID)).willReturn(reservation);
            given(paymentReader.existsByReservationId(RESERVATION_ID)).willReturn(false);

            ConcertSeatInternalClient.SeatDetail seat =
                    new ConcertSeatInternalClient.SeatDetail(
                            SEAT_ID, 1L, 1L, "A-1", AMOUNT, "HELD");
            given(concertSeatClient.readSeat(SEAT_ID)).willReturn(seat);

            Payment savedPayment = Payment.builder()
                    .paymentId(PAYMENT_ID)
                    .reservationId(RESERVATION_ID)
                    .userId(USER_ID)
                    .amount(AMOUNT)
                    .status(PaymentStatus.READY)
                    .build();
            given(paymentWriter.save(any(Payment.class))).willReturn(savedPayment);

            // when
            Payment result = paymentManager.createPaymentRequest(USER_ID, RESERVATION_ID);

            // then
            assertThat(result.getStatus()).isEqualTo(PaymentStatus.READY);
            assertThat(result.getAmount()).isEqualTo(AMOUNT);
            verify(bookingClient).readReservation(RESERVATION_ID);
            verify(concertSeatClient).readSeat(SEAT_ID);
            verify(paymentWriter).save(any(Payment.class));
        }

        @Test
        @DisplayName("PENDING이 아닌 예약에 대해 결제 요청 시 RESERVATION_NOT_CONFIRMABLE 예외")
        void createPaymentRequest_notPending_shouldThrow() {
            // given: 이미 CONFIRMED된 예약
            BookingInternalClient.ReservationDetail reservation =
                    new BookingInternalClient.ReservationDetail(
                            RESERVATION_ID, USER_ID, SEAT_ID, "CONFIRMED", "2026-03-30T00:00:00");
            given(bookingClient.readReservation(RESERVATION_ID)).willReturn(reservation);

            // when & then
            assertThatThrownBy(() -> paymentManager.createPaymentRequest(USER_ID, RESERVATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> {
                        BusinessException ex = (BusinessException) thrown;
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.RESERVATION_NOT_CONFIRMABLE);
                    });

            // then: 결제 레코드가 생성되지 않음
            verify(paymentWriter, never()).save(any(Payment.class));
        }

        @Test
        @DisplayName("이미 결제가 존재하는 예약에 대해 PAYMENT_ALREADY_EXISTS 예외")
        void createPaymentRequest_duplicatePayment_shouldThrow() {
            // given
            BookingInternalClient.ReservationDetail reservation =
                    new BookingInternalClient.ReservationDetail(
                            RESERVATION_ID, USER_ID, SEAT_ID, "PENDING", "2026-03-30T00:00:00");
            given(bookingClient.readReservation(RESERVATION_ID)).willReturn(reservation);
            given(paymentReader.existsByReservationId(RESERVATION_ID)).willReturn(true);

            // when & then
            assertThatThrownBy(() -> paymentManager.createPaymentRequest(USER_ID, RESERVATION_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(thrown -> {
                        BusinessException ex = (BusinessException) thrown;
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.PAYMENT_ALREADY_EXISTS);
                    });
        }
    }

    // ─────────────────────────────────────────────────────────
    // Payment 도메인 상태 전이 검증
    // ─────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Payment 도메인 상태 전이 규칙")
    class PaymentStateMachine {

        @Test
        @DisplayName("READY → APPROVED 전이만 허용된다")
        void approve_onlyFromReady() {
            Payment ready = Payment.builder().status(PaymentStatus.READY).build();
            Payment approved = ready.approve("pk", "카드", LocalDateTime.now(), "{}");
            assertThat(approved.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        }

        @Test
        @DisplayName("APPROVED 상태에서 approve 호출 시 IllegalStateException")
        void approve_fromApproved_shouldThrow() {
            Payment approved = Payment.builder().status(PaymentStatus.APPROVED).build();
            assertThatThrownBy(() -> approved.approve("pk", "카드", LocalDateTime.now(), "{}"))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("READY → FAILED 전이만 허용된다")
        void fail_onlyFromReady() {
            Payment ready = Payment.builder().status(PaymentStatus.READY).build();
            Payment failed = ready.fail("잔액 부족");
            assertThat(failed.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("APPROVED → REFUNDED 전이만 허용된다")
        void completeCancel_onlyFromApproved() {
            Payment approved = Payment.builder().status(PaymentStatus.APPROVED).build();
            Payment refunded = approved.completeCancel(LocalDateTime.now());
            assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        }

        @Test
        @DisplayName("APPROVED → CANCEL_FAILED 전이만 허용된다")
        void failCancel_onlyFromApproved() {
            Payment approved = Payment.builder().status(PaymentStatus.APPROVED).build();
            Payment cancelFailed = approved.failCancel();
            assertThat(cancelFailed.getStatus()).isEqualTo(PaymentStatus.CANCEL_FAILED);
        }

        @Test
        @DisplayName("READY 상태에서 completeCancel 호출 시 IllegalStateException")
        void completeCancel_fromReady_shouldThrow() {
            Payment ready = Payment.builder().status(PaymentStatus.READY).build();
            assertThatThrownBy(() -> ready.completeCancel(LocalDateTime.now()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("FAILED 상태에서 failCancel 호출 시 IllegalStateException")
        void failCancel_fromFailed_shouldThrow() {
            Payment failed = Payment.builder().status(PaymentStatus.FAILED).build();
            assertThatThrownBy(() -> failed.failCancel())
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("CANCEL_FAILED → REFUNDED 전이 허용 (스케줄러 복구 경로)")
        void completeCancel_fromCancelFailed_shouldSucceed() {
            Payment cancelFailed = Payment.builder().status(PaymentStatus.CANCEL_FAILED).build();
            Payment refunded = cancelFailed.completeCancel(LocalDateTime.now());
            assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        }
    }
}
