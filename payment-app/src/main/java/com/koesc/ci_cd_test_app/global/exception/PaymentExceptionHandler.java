package com.koesc.ci_cd_test_app.global.exception;

import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * payment-app 전용 예외 핸들러.
 * 공통 예외(BusinessException, MethodArgumentNotValidException 등)는 common-module의
 * GlobalExceptionHandler(global.error)가 처리한다.
 * 이 핸들러는 payment-app에만 존재하는 DB UK 위반 케이스만 담당한다.
 */
@Slf4j
@RestControllerAdvice
public class PaymentExceptionHandler {

    /**
     * DB UK 위반 (reservation_id, order_id 중복).
     * 같은 예약에 대해 결제 레코드가 이미 존재할 때 발생한다.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException e) {
        log.warn("DataIntegrityViolation: {}", e.getMessage());
        ErrorCode ec = ErrorCode.PAYMENT_ALREADY_EXISTS;
        return ResponseEntity
                .status(ec.getHttpStatus())
                .body(ErrorResponse.of(ec));
    }
}
