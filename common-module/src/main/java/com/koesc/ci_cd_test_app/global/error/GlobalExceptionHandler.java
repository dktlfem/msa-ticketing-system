package com.koesc.ci_cd_test_app.global.error;

import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.global.gateway.PassportCodecException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * GlobalExceptionHandler: Controller 실행 중 던져진 예외를 가로채서(intercept) HTTP 응답(JSON)으로 변환해줌.
 *
 * e.printStackTrace()는 멀티스레드 환경에서 성능 저하를 일으킬 수 있어 로거 활용을 권장.
 */
@Slf4j
@RestControllerAdvice // 모든 Controller에서 발생하는 예외를 여기서 가로챔. (공통 예외 처리기)
public class GlobalExceptionHandler {

    @ExceptionHandler(PassportCodecException.class)
    public ResponseEntity<ErrorResponse> handlePassportCodec(PassportCodecException e) {
        log.warn("[AuthPassport] Failed to decode Auth-Passport header: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("A001", "인증 컨텍스트가 유효하지 않습니다.", HttpStatus.UNAUTHORIZED.value()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException e) {
        log.warn("[MissingHeader] {}", e.getMessage());
        if ("Auth-Passport".equals(e.getHeaderName())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new ErrorResponse("A001", "인증 헤더가 누락되었습니다.", HttpStatus.UNAUTHORIZED.value()));
        }
        ErrorCode ec = ErrorCode.INVALID_INPUT_VALUE;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ec, "필수 헤더가 누락되었습니다: " + e.getHeaderName()));
    }

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        ErrorCode ec = ErrorCode.INVALID_INPUT_VALUE;
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse(ec.getMessage());
        log.warn("[Validation] {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ErrorResponse.of(ec, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAll(Exception e) {
        log.error("[InternalError] {}", e.getMessage(), e);
        ErrorCode ec = ErrorCode.INTERNAL_SERVER_ERROR;
        return ResponseEntity.status(ec.getHttpStatus()).body(ErrorResponse.of(ec));
    }

    // EventNotFoundException, SeatNotFoundException 등등 이러한 에러가 던져지면
    // GlobalExceptionHandler가 errorCode를 꺼내서 도메인 맞춤 응답을 내려줌.
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode ec = e.getErrorCode();
        // 404,400 같은 예상 가능한 오류는 트래픽이 많으면 스택이 로그를 폭격함
        // -> 디스크/로그 비용 증가 + 검색성 저하 + 성능 영향
        // BusinessException은 warn 로그는 남기되 스택트레이스는 기본으로 안 남김
        // 진짜 원인 분석이 필요할 때만 debug 레벨에서 스택
        log.warn("[Business] code={}, msg={}", ec.getCode(), e.getMessage());
        log.debug("[BusinessStack]", e);
        return ResponseEntity.status(ec.getHttpStatus())
                .body(ErrorResponse.of(ec, e.getMessage()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException e) {
        HttpStatusCode status = e.getStatusCode();
        String reason = (e.getReason() != null) ? e.getReason() : status.toString();

        log.warn("[ResponseStatus] status={}, reason={}", status.value(), reason);

        return ResponseEntity.status(status)
                .body(new ErrorResponse("HTTP_" + status.value(), reason, status.value()));
    }
}
