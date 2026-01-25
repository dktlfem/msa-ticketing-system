package com.koesc.ci_cd_test_app.business;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;

@Service
public class AiModelService {

    @CircuitBreaker(name = "aiService", fallbackMethod = "fallback")
    public String callAiModel() {

        // 테스트 시나리오를 위해 5초 이상 강제 지연 시뮬레이션 가능
        // Thread.sleep(5000);
        return "AI Response";
    }

    public String fallback() {
        return "Fallback Response (Circuit is OPEN)";
    }
}
