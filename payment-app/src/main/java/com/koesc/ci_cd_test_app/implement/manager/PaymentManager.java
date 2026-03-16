package com.koesc.ci_cd_test_app.implement.manager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koesc.ci_cd_test_app.domain.Payment;
import com.koesc.ci_cd_test_app.domain.PaymentStatus;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.implement.client.BookingInternalClient;
import com.koesc.ci_cd_test_app.implement.client.ConcertSeatInternalClient;
import com.koesc.ci_cd_test_app.implement.client.TossPaymentsClient;
import com.koesc.ci_cd_test_app.implement.reader.PaymentReader;
import com.koesc.ci_cd_test_app.implement.validator.PaymentValidator;
import com.koesc.ci_cd_test_app.implement.writer.PaymentWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 결제 흐름을 오케스트레이션하는 핵심 컴포넌트.
 *
 * 트랜잭션 경계:
 * - createPaymentRequest: @Transactional (DB 저장 포함)
 * - confirmPayment / cancelPayment: 트랜잭션 없음.
 *   외부 PG 호출 사이사이에 PaymentWriter의 개별 @Transactional 메서드를 호출한다.
 *   이유: PG 응답을 기다리는 동안 DB 커넥션을 점유하지 않기 위함.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentManager {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PaymentReader paymentReader;
    private final PaymentWriter paymentWriter;
    private final PaymentValidator paymentValidator;
    private final BookingInternalClient bookingClient;
    private final ConcertSeatInternalClient concertSeatClient;
    private final TossPaymentsClient tossPaymentsClient;
    private final ObjectMapper objectMapper;

    /**
     * 결제 요청 생성.
     *
     * 1. 예약 상태 검증 (booking-app 호출)
     * 2. 중복 결제 여부 확인
     * 3. 좌석 가격 조회 (concert-app 호출) - 결제 시점 금액 스냅샷
     * 4. READY 상태 결제 레코드 생성
     */
    @Transactional
    public Payment createPaymentRequest(Long userId, Long reservationId) {
        // 1. 예약 상태 검증
        BookingInternalClient.ReservationDetail reservation = bookingClient.readReservation(reservationId);
        if (!userId.equals(reservation.userId())) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND, "접근 권한이 없는 예약입니다.");
        }
        if (!"PENDING".equals(reservation.status())) {
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CONFIRMABLE,
                    "결제 대기 중인 예약이 아닙니다. 현재 상태: " + reservation.status());
        }

        // 2. 중복 결제 방지 (DB UK 위반 전 선행 검증)
        if (paymentReader.existsByReservationId(reservationId)) {
            throw new BusinessException(ErrorCode.PAYMENT_ALREADY_EXISTS);
        }

        // 3. 좌석 가격 조회 - concert-app이 source of truth
        ConcertSeatInternalClient.SeatDetail seat = concertSeatClient.readSeat(reservation.seatId());

        // 4. orderId 생성: RES{reservationId}_{epochMilli}
        //    TossPayments orderId 규격: 영문, 숫자, -, _ / 최대 64자
        String orderId = "RES" + reservationId + "_" + Instant.now().toEpochMilli();

        Payment payment = Payment.builder()
                .reservationId(reservationId)
                .userId(userId)
                .orderId(orderId)
                .amount(seat.price())
                .status(PaymentStatus.READY)
                .build();

        Payment saved = paymentWriter.save(payment);
        log.info("Payment created - paymentId={}, reservationId={}, orderId={}, amount={}",
                saved.getPaymentId(), reservationId, orderId, seat.price());
        return saved;
    }

    /**
     * 결제 승인 (TossPayments 2단계 결제의 서버 confirm 단계).
     *
     * 1. READY 상태 + 금액 검증
     * 2. TossPayments confirmPayment API 호출
     * 3. DB APPROVED 업데이트
     * 4. booking-app confirm 호출
     * 5. confirm 실패 시 보상 트랜잭션 (PG 취소)
     *
     * 각 단계는 독립 트랜잭션. PG 호출 중 DB 커넥션을 점유하지 않는다.
     */
    public Payment confirmPayment(String orderId, String paymentKey, BigDecimal amount) {
        // 1. 검증 (readOnly)
        Payment payment = paymentReader.readByOrderId(orderId);
        paymentValidator.validateConfirmable(payment);
        paymentValidator.validateAmount(payment.getAmount(), amount);

        // 2. TossPayments 승인 (외부 API - 트랜잭션 외부)
        TossPaymentsClient.TossConfirmResponse pgResponse;
        try {
            pgResponse = tossPaymentsClient.confirmPayment(paymentKey, orderId, amount);
        } catch (BusinessException e) {
            paymentWriter.updateToFailed(payment.getPaymentId(), e.getMessage());
            log.error("Payment failed after PG rejection - orderId={}", orderId);
            throw e;
        }

        // 3. DB APPROVED 업데이트
        LocalDateTime approvedAt = pgResponse.approvedAt() != null
                ? pgResponse.approvedAt().atZoneSameInstant(KST).toLocalDateTime()
                : LocalDateTime.now();
        Payment approved = paymentWriter.updateToApproved(
                payment.getPaymentId(), paymentKey, pgResponse.method(), approvedAt,
                serializeSafe(pgResponse));

        log.info("Payment approved - paymentId={}, orderId={}", approved.getPaymentId(), orderId);

        // 4. booking-app confirm 호출
        try {
            bookingClient.confirmReservation(payment.getReservationId(), approved.getPaymentId());
            log.info("Reservation confirmed - reservationId={}, paymentId={}",
                    payment.getReservationId(), approved.getPaymentId());
        } catch (Exception e) {
            // 5. 보상: 결제 취소
            log.error("Reservation confirm failed - reservationId={}, initiating refund",
                    payment.getReservationId(), e);
            initiateRefund(approved.getPaymentId(), paymentKey, "예약 확정 실패로 인한 자동 환불");
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CONFIRMABLE,
                    "예약 확정 실패로 결제가 취소되었습니다.");
        }

        return approved;
    }

    /**
     * 결제 취소 (사용자 요청).
     * APPROVED 상태의 결제만 취소 가능하다.
     */
    public Payment cancelPayment(String paymentKey, String cancelReason) {
        Payment payment = paymentReader.readByPaymentKey(paymentKey);
        paymentValidator.validateCancellable(payment);
        return initiateRefund(payment.getPaymentId(), paymentKey, cancelReason);
    }

    /**
     * PG 취소 + DB 상태 업데이트.
     * 취소 실패 시 CANCEL_FAILED 상태로 전이하고 예외를 던진다.
     * CANCEL_FAILED는 수동 개입이 필요하다.
     */
    private Payment initiateRefund(Long paymentId, String paymentKey, String cancelReason) {
        try {
            TossPaymentsClient.TossCancelResponse cancelResponse =
                    tossPaymentsClient.cancelPayment(paymentKey, cancelReason);
            LocalDateTime cancelledAt = cancelResponse.requestedAt() != null
                    ? cancelResponse.requestedAt().atZoneSameInstant(KST).toLocalDateTime()
                    : LocalDateTime.now();
            Payment refunded = paymentWriter.updateToRefunded(paymentId, cancelledAt);
            log.info("Payment refunded - paymentId={}, paymentKey={}", paymentId, paymentKey);
            return refunded;
        } catch (Exception e) {
            paymentWriter.updateToCancelFailed(paymentId);
            // CANCEL_FAILED: 고객 돈이 묶인 상태. 로그로 명시적 기록 후 예외 전파.
            log.error("[CRITICAL] Payment cancel failed - paymentId={}, paymentKey={} - MANUAL INTERVENTION REQUIRED",
                    paymentId, paymentKey, e);
            throw new BusinessException(ErrorCode.PAYMENT_PG_ERROR,
                    "환불 처리에 실패했습니다. 고객센터로 문의 바랍니다.");
        }
    }

    private String serializeSafe(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
