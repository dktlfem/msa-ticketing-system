package com.koesc.ci_cd_test_app.global.error;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice // 모든 Controller에서 발생하는 예외를 여기서 가로챔. (공통 예외 처리기)
public class GlobalExceptionHandler {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> handleAllException(Exception e) {

        // 에러의 원인과 Stack Trace를 로그에 빨갛게 찍는다.
        // log.error의 마지막 인자로 e를 전달하여 Stack Trace를 정교하게 로깅함.
        // e.printStackTrace()는 멀티스레드 환경에서 성능 저하를 일으킬 수 있어 로거 활용을 권장.
        log.error("🔴 [GlobalException] 원인: {}, 메시지: {}",
                e.getClass().getSimpleName(),
                e.getMessage(),
                e);

        return ResponseEntity.status(500).body("Internal Server Error: " + e.getMessage());
    }
}
