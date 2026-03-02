package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.global.calculator.WaitingRoomCalculator;
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomManager;
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomRateLimiter;
import com.koesc.ci_cd_test_app.implement.reader.WaitingRoomReader;
import com.koesc.ci_cd_test_app.implement.validator.WaitingRoomValidator;
import com.koesc.ci_cd_test_app.implement.writer.WaitingRoomWriter;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * 현재 단위 테스트
 * 목적(딱 2개만): "스프링 부트 컨텍스트" 없이,
 * 1. joinQueue()에서 writer 에러 -> joinQueueFallback() -> -1 반환
 * 2. 실패 2번 누적 -> CircuitBreaker OPEN
 *
 * 스프링 부트 컨텍스트는 절대 안 띄움.
 * 대신 AspectJProxyFactory로 "AOP 프록시"만 만들어서 @CircuitBreaker 느낌만 냄.
 *
 * 추후 고도화 테스트 (WaitingRoom 복구력 단위 테스트 (AOP Proxy + CircuitBreaker Fallback))
 * 목적: "스프링 부트 컨텍스트" 없이,
 * - Resilience4j @CircuitBreaker AOP가 적용되는지
 * - fallbackMethod가 호출되는지
 * - 실패 누적 시 CircuitBreaker가 OPEN 되는지
 * 만 확인하는 단위 테스트
 */

@DisplayName("WaitingRoom 단위 테스트 - CircuitBreaker + fallback (No Spring Context)")
public class WaitingRoomResilienceTest {

    // proxy : AOP(Aspect)를 붙인 프록시 객체
    // 우리가 실제 테스트에서 호출되는 것은 proxy.joinQueue(...)
    private WaitingRoomService proxy;

    // target = 진짜 서비스 객체(프록시 적용 안 된 순수 객체) private WaitingRoomService target;
    private WaitingRoomService target;

    private CircuitBreakerRegistry circuitBreakerRegistry;

    // 스프링 없이 테스트하므로 직접 mock으로 만들어줘서 넣어줘야 함.
    private WaitingRoomValidator waitingRoomValidator;
    private WaitingRoomManager waitingRoomManager;
    private WaitingRoomReader waitingRoomReader;
    private WaitingRoomWriter waitingRoomWriter;
    private WaitingRoomRateLimiter waitingRoomRateLimiter;
    private WaitingRoomCalculator waitingRoomCalculator;

    @BeforeEach
    void setUp() {

        // 1. 의존성 mock
        waitingRoomValidator = Mockito.mock(WaitingRoomValidator.class);
        waitingRoomManager = Mockito.mock(WaitingRoomManager.class);
        waitingRoomReader = Mockito.mock(WaitingRoomReader.class);
        waitingRoomWriter = Mockito.mock(WaitingRoomWriter.class);
        waitingRoomRateLimiter = Mockito.mock(WaitingRoomRateLimiter.class);
        waitingRoomCalculator = Mockito.mock(WaitingRoomCalculator.class);

        /**
         * 2. "기본 stub" (Mockito 기본값 null 때문에 Mono.then(null) NPE 나는 걸 방지)
         * joinQueue는 writer.addToToken().then(reader.getRank()) 구조라서,
         * stub 안 하면 reader.getRank가 null이 되어 NPE가 터질 수 있음.
         */
        given(waitingRoomWriter.addToToken(anyLong(), anyLong())).willReturn(Mono.empty());
        given(waitingRoomReader.getRank(anyLong(), anyLong())).willReturn(Mono.empty());

        // 3. 실제 서비스 객체 생성 (스프링 없이 new)
        target = new WaitingRoomService(
                waitingRoomValidator,
                waitingRoomManager,
                waitingRoomReader,
                waitingRoomWriter,
                waitingRoomRateLimiter,
                waitingRoomCalculator
        );

        /**
         * 4.  CircuitBreaker 설정 만들기
         *
         * 기존에 @SpringBootTest properties로 넣던 것을
         * 여기서는 코드로 직접 생성함.
         *
         * - COUNT_BASED: 호출 횟수로 윈도우를 계산
         * - slidingWindowSize=2: 최근 2번 호출 기준
         * - minimumNumberOfCalls=2: 최소 2번은 호출돼야 판단 시작
         * - failureRateThreshold=50: 실패율이 50% 이상이면 OPEN
         * - waitDurationInOpenState=10s: OPEN 상태 10초 유지
         */
        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(2)
                .minimumNumberOfCalls(2)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build();

        // 레지스트리(관리소)에 이 설정을 기반으로 CircuitBreaker를 만들게 함.
        circuitBreakerRegistry = CircuitBreakerRegistry.of(cbConfig);

        /**
         * 5. "joinQueue 전용" 초미니 Aspect 붙여서 프록시 생성
         */
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new JoinQueueCircuitBreakerAspect(circuitBreakerRegistry));
        proxy = factory.getProxy();
    }

    @Test
    @DisplayName("Redis 장애(Writer error) 발생 시 joinQueueFallback으로 -1을 반환한다")
    void joinQueue_Fallback_OnRedisError() {

        // 1. given : writer가 터진다고 가정
        given(waitingRoomWriter.addToToken(1L, 100L))
                .willReturn(Mono.error(new RuntimeException("Redis down")));

        // 2. when & then : fallback이 -1을 반환해야 함
        StepVerifier.create(proxy.joinQueue(1L, 100L))
                .expectNext(-1L)
                .verifyComplete();

        // 그리고 CircuitBreaker 실패 카운트가 증가했는지 확인
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("waitingRoomService");
        assertThat(cb.getMetrics().getNumberOfFailedCalls()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("연속 실패가 2번 누적되면 CircuitBreaker가 OPEN으로 전이된다(환경 설정 기반)")
    void circuitBreaker_Opens_AfterFailures() {

        // 1. given
        given(waitingRoomWriter.addToToken(1L, 100L))
                .willReturn(Mono.error(new RuntimeException("Redis down")));

        // 2. when : 2번 호출(설정상 최소 2콜)
        StepVerifier.create(proxy.joinQueue(1L, 100L)).expectNext(-1L).verifyComplete();
        StepVerifier.create(proxy.joinQueue(1L, 100L)).expectNext(-1L).verifyComplete();

        // 3. then
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("waitingRoomService");
        assertThat(cb.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    /**
     * 핵심 "joinQueue 하나만" 처리하는 초미니 Aspect
     *
     * - @CircuitBreaker 어노테이션에서 name을 읽어 레지스트리(circuitBreakerRegistry)에서 CB를 가져오고
     * - Reactor 체인에 CircuitBreakerOperator를 적용
     * - 에러 나면 WaitingRoomService.joinQueueFallback(...)을 직접 호출(리플렉션 X)
     */
    @Aspect
    static class JoinQueueCircuitBreakerAspect {

        private final CircuitBreakerRegistry circuitBreakerRegistry;

        JoinQueueCircuitBreakerAspect(CircuitBreakerRegistry circuitBreakerRegistry) {
            this.circuitBreakerRegistry = circuitBreakerRegistry;
        }

        // joinQueue 메서드 + @CircuitBreaker가 붙은 경우에만 가로챔 (범위 좁혀서 단순화)
        @Around("execution(reactor.core.publisher.Mono com.koesc.ci_cd_test_app.business.WaitingRoomService.joinQueue(..)) && @annotation(cbAnn)")
        public Object aroundJoinQueue(
                ProceedingJoinPoint pjp,
                io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker cbAnn
        ) throws Throwable {

            // joinQueue는 Mono<Long>을 리턴하므로 타입을 고정해야 onErrorResume에서 fallback(Mono<Long>)이 컴파일됨
            @SuppressWarnings("unchecked")
            Mono<Long> mono = (Mono<Long>) pjp.proceed();

            // 코어 CircuitBreaker(클래스) 꺼내기
            io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker =
                    circuitBreakerRegistry.circuitBreaker(cbAnn.name());

            // CircuitBreaker 적용
            Mono<Long> guarded = mono.transformDeferred(CircuitBreakerOperator.of(circuitBreaker));

            // fallback: joinQueueFallback(eventId, userId, throwable)
            return guarded.onErrorResume(ex -> {
                Object[] args = pjp.getArgs();
                Long eventId = (Long) args[0];
                Long userId = (Long) args[1];

                WaitingRoomService target = (WaitingRoomService) pjp.getTarget();
                return target.joinQueueFallback(eventId, userId, ex);
            });
        }
    }
}
