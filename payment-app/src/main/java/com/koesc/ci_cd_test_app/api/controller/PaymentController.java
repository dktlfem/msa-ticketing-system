package com.koesc.ci_cd_test_app.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koesc.ci_cd_test_app.api.request.PaymentCancelRequestDTO;
import com.koesc.ci_cd_test_app.api.request.PaymentConfirmRequestDTO;
import com.koesc.ci_cd_test_app.api.request.PaymentRequestDTO;
import com.koesc.ci_cd_test_app.api.response.PaymentResponseDTO;
import com.koesc.ci_cd_test_app.business.PaymentService;
import com.koesc.ci_cd_test_app.domain.Payment;
import com.koesc.ci_cd_test_app.implement.idempotency.IdempotencyManager;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;

@Slf4j
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyManager idempotencyManager;
    private final ObjectMapper objectMapper;

    /**
     * 결제 요청 생성.
     * 응답의 orderId, amount를 클라이언트가 TossPayments SDK에 전달해야 한다.
     * X-User-Id: SCG에서 전달하는 인증된 사용자 ID
     * Idempotency-Key: 클라이언트가 생성하는 고유 키 (UUID 권장)
     */
    @PostMapping("/request")
    public ResponseEntity<PaymentResponseDTO> requestPayment(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequestDTO request) {

        Optional<String> cached = idempotencyManager.startProcessing(idempotencyKey);
        if (cached.isPresent()) {
            return ResponseEntity.ok(deserialize(cached.get()));
        }

        try {
            Payment payment = paymentService.requestPayment(userId, request.reservationId());
            PaymentResponseDTO response = PaymentResponseDTO.from(payment);
            idempotencyManager.complete(idempotencyKey, serialize(response));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            idempotencyManager.remove(idempotencyKey);
            throw e;
        }
    }

    /**
     * 결제 승인.
     * 클라이언트가 TossPayments SDK에서 받은 paymentKey, orderId, amount를 전달한다.
     */
    @PostMapping("/confirm")
    public ResponseEntity<PaymentResponseDTO> confirmPayment(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentConfirmRequestDTO request) {

        Optional<String> cached = idempotencyManager.startProcessing(idempotencyKey);
        if (cached.isPresent()) {
            return ResponseEntity.ok(deserialize(cached.get()));
        }

        try {
            Payment payment = paymentService.confirmPayment(
                    request.orderId(), request.paymentKey(), request.amount());
            PaymentResponseDTO response = PaymentResponseDTO.from(payment);
            idempotencyManager.complete(idempotencyKey, serialize(response));
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            idempotencyManager.remove(idempotencyKey);
            throw e;
        }
    }

    /**
     * 결제 취소.
     * APPROVED 상태의 결제만 취소 가능하다.
     */
    @PostMapping("/{paymentKey}/cancel")
    public ResponseEntity<PaymentResponseDTO> cancelPayment(
            @PathVariable String paymentKey,
            @Valid @RequestBody PaymentCancelRequestDTO request) {
        Payment payment = paymentService.cancelPayment(paymentKey, request.cancelReason());
        return ResponseEntity.ok(PaymentResponseDTO.from(payment));
    }

    /**
     * 결제 상태 조회.
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<PaymentResponseDTO> getPayment(@PathVariable Long paymentId) {
        Payment payment = paymentService.getPayment(paymentId);
        return ResponseEntity.ok(PaymentResponseDTO.from(payment));
    }

    private String serialize(PaymentResponseDTO response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("Failed to serialize idempotency response", e);
            return "{}";
        }
    }

    private PaymentResponseDTO deserialize(String json) {
        try {
            return objectMapper.readValue(json, PaymentResponseDTO.class);
        } catch (Exception e) {
            log.warn("Failed to deserialize idempotency cache, proceeding without cache", e);
            return null;
        }
    }
}
