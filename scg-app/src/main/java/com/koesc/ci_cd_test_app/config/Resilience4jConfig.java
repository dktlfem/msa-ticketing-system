package com.koesc.ci_cd_test_app.config;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * 서비스별 독립 CircuitBreaker / Bulkhead 인스턴스 설정.
 *
 * CircuitBreaker:
 *   공통(default): sliding window 10, failure rate 50%, waitDuration 10s
 *   payment-service-cb: waitDuration 30s (결제 복구에 더 긴 안정화 시간 필요)
 *   CB 이름은 application.yml route filter args의 name과 반드시 일치해야 함.
 *
 * Bulkhead:
 *   공통(default): maxConcurrentCalls 20
 *   payment-service: maxConcurrentCalls 10 (결제는 동시 처리 부담이 큼)
 *   BulkheadFilter가 BulkheadRegistry에서 route ID로 Bulkhead 인스턴스를 획득.
 */
@Configuration
public class Resilience4jConfig {

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        BulkheadConfig defaultConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(20)
                .build();

        BulkheadConfig paymentConfig = BulkheadConfig.custom()
                .maxConcurrentCalls(10)
                .build();

        BulkheadRegistry registry = BulkheadRegistry.of(defaultConfig);
        // payment-service 전용 Bulkhead를 미리 등록해 BulkheadFilter에서 사용
        registry.bulkhead("payment-service", paymentConfig);
        return registry;
    }

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> circuitBreakerCustomizer() {
        CircuitBreakerConfig defaultCbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(3)
                .build();

        // payment-service는 결제 플로우 특성상 open 상태 유지 시간을 더 길게 설정
        CircuitBreakerConfig paymentCbConfig = CircuitBreakerConfig.from(defaultCbConfig)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .build();

        return factory -> {
            factory.configureDefault(id -> new Resilience4JConfigBuilder(id)
                    .circuitBreakerConfig(defaultCbConfig)
                    .build());

            factory.configure(
                    builder -> builder.circuitBreakerConfig(paymentCbConfig),
                    "payment-service-cb"
            );
        };
    }
}
