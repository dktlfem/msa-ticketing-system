package com.koesc.ci_cd_test_app.global.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * e.printStackTrace()는 멀티스레드 환경에서 성능 저하를 일으킬 수 있어 로거 활용을 권장.
 */
@Slf4j
@RestControllerAdvice // 모든 Controller에서 발생하는 예외를 여기서 가로챔. (공통 예외 처리기)
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(IllegalArgumentException e) {
        log.warn("[BadRequest] {}", e.getMessage(), e);
        ErrorCode ec = ErrorCode.INVALID_INPUT_VALUE;
        return ResponseEntity.status(ec.getHttpStatus()).body(ErrorResponse.of(ec));
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(ObjectOptimisticLockingFailureException e) {
        log.warn("[OptimisticLock] {}", e.getMessage(), e);
        ErrorCode ec = ErrorCode.SEAT_ALREADY_HELD;
        return ResponseEntity.status(ec.getHttpStatus()).body(ErrorResponse.of(ec));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAll(Exception e) {
        log.error("[InternalError] {}", e.getMessage(), e);
        ErrorCode ec = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(ec.getHttpStatus()).body(ErrorResponse.of(ec));
    }
}
