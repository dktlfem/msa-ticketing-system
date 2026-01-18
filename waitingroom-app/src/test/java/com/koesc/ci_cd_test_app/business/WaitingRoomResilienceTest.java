package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.implement.client.AIModelClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * WaitingRoomService가 장애 상황(무거운 AI 연산 지연 등)에서 어떻게 버티는지를 테스트해야 하므로
 * business 패키지 하위에 해당 테스트 클래스 생성
 */

@SpringBootTest // 서킷브레이커 설정(AOP)을 읽기 위해 통합 테스트로 진행
@DisplayName("WaitingRoom 복구력 테스트 : AI 모델 서버 장애 시 시스템 보호 검증 & Circuit Breaker 동작 검증")
public class WaitingRoomResilienceTest {

    @Autowired
    private WaitingRoomService waitingRoomService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    // AI 모델 서빙 서버 모킹
    @MockitoBean
    private AIModelClient aiModelClient;

    /**
     * circuitBreaker_Opens_OnFailure()
     * 핵심 트리거 : RuntimeException 에러 발생
     * 서버 상태 : AI 모델 서버가 아예 죽거나 네트워크 에러 발생
     * 시스템 위험도 : 즉각적인 에러 응답 (Error Rate 증가)
     * ML 서빙 관점 : 인프라 장애 대응
     *
     */
    @Test
    @DisplayName("AI 모델 서버 에러가 지속되면 회로가 OPEN 되고 이후 요청은 차단된다.")
    void circuitBreaker_Opens_OnFailure() {

        // 1. given : AI 서버가 계속 에러를 던지도록 설정
        given(aiModelClient.predictAbuse(anyLong()))
                .willThrow(new RuntimeException("AI Server Down"));

        // 2. when : 설정된 임계치만큼 호출 (예: 5번)
        for (int i = 0; i < 10; i++) {
            try {
                waitingRoomService.getQueueStatus(1L, 100L + i);
            } catch (Exception ignored) {}
        }

        // 3. then : 회로 상태가 OPEN인지 확인
        var circuitBreaker = circuitBreakerRegistry.circuitBreaker("aiModelPredict");
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 토스뱅크 ML 백엔드는 무거운 모델 서버의 장애가
        // 전체 서비스로 전이(Cascading Failure)되는 것을 막는 역량이 핵심이기 때문임.
    }

    /**
     * circuitBreaker_Protects_Thread_Pool()
     * 핵심 트리거 : slowCallDurationThreshold 시간 초과
     * 서버 상태 : 모델 서버는 살아있으나 연산이 너무 무거워 응답이 느림
     * 시스템 위험도 : 스레드 점유로 인한 서버 전체 마비 (Thread Starvation)
     * ML 서빙 관점 : 무거운 AI 추론(Inference) 시간 관리 핵심
     */
    @Test
    @DisplayName("AI 모델 서버가 임계치보다 느려지면 회로가 OPEN 되어 스레드 고갈을 방지한다.")
    void circuitBreaker_Protects_Thread_Pool() {

        // 1. given : AI 모델 서버가 설정된 임계치(예: 1초)보다 훨씬 느린 2초가 걸린다고 가정 (ML 서빙의 전형적인 장애)
        given(aiModelClient.predictAbuse(anyLong())).willAnswer(invocation -> {
            Thread.sleep(2000); // 2초 지연 시뮬레이션
            return "NORMAL";
        });

        // 2. when : 슬라이딩 윈도우 크기만큼 '느린 호출' 발생 시킴 (예: 10번)
        for (int i = 0; i < 10; i++) {
            try {
                waitingRoomService.getQueueStatus(1L, 100L + i);
            } catch (Exception ignored) {
                // 서킷이 열리면 요청 즉시 차단(CallNotPermittedException)되는지 검증
            }
        }

        // 3. then : 회로 상태가 OPEN인지 확인
        var circuitBreaker = circuitBreakerRegistry.circuitBreaker("aiModelPredict");

        // 상태가 OPEN이라면, 이후의 무거운 요청은 AI 서버를 찌르지도 않고 즉시 차단됨
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Why? : "무거운 AI 연산이 길어질 때 WAS의 Thread를 계속 붙잡고 있지 않도록
        // Slow Call 기준을 설정하여 시스템 전체의 가용성을 확보함.

        // TODO: ex) 10번 연속 지연 발생 시 회로가 OPEN 되는지 확인하는 시나리오 작성
    }

}
