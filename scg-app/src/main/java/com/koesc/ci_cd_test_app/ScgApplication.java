package com.koesc.ci_cd_test_app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import reactor.core.publisher.Hooks;

@SpringBootApplication
public class ScgApplication {
    public static void main(String[] args) {
        // Reactor Context → ThreadLocal(MDC) 자동 전파 활성화
        // Spring Boot 3.2+ actuator가 Slf4jMdcThreadLocalAccessor를 ContextRegistry에 등록하므로
        // MDC 값이 CircuitBreaker fallback 등 스레드 전환 구간에서도 유지됨
        Hooks.enableAutomaticContextPropagation();
        SpringApplication.run(ScgApplication.class, args);
    }
}
