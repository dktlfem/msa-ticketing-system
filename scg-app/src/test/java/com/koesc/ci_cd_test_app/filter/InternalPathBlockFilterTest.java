package com.koesc.ci_cd_test_app.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * InternalPathBlockFilter 단위 테스트
 *
 * 검증 목표:
 *   1. /internal/** 경로 → 403 반환, filter chain 미호출
 *   2. /api/v1/** 경로  → chain 통과, 응답 상태 변경 없음
 *   3. 커스텀 패턴 설정 시 적용 여부
 *   4. 차단 시 응답에 body를 쓰지 않고 setComplete() 만 호출
 *
 * 테스트 전략:
 *   - @SpringBootTest 없이 MockServerWebExchange 직접 생성
 *   - GatewayFilterChain을 Mockito mock으로 대체
 *   - filter() 결과를 StepVerifier로 검증 (Reactive)
 */
class InternalPathBlockFilterTest {

    private InternalPathBlockFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new InternalPathBlockFilter();
        // 기본값("/internal/**")으로 @PostConstruct init() 수동 실행
        ReflectionTestUtils.setField(filter, "blockedPatternsRaw", "/internal/**");
        filter.init();

        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Nested
    @DisplayName("차단 대상 경로 — 403 반환")
    class BlockedPaths {

        @ParameterizedTest(name = "[{index}] path={0}")
        @ValueSource(strings = {
                "/internal/v1/reservations/1",
                "/internal/v1/seats/1/hold",
                "/internal/v1/waiting-room/tokens/validate",
                "/internal/v1/reservations/1/confirm",
                "/internal",              // 정확히 /internal
                "/internal/deep/nested/path",
        })
        @DisplayName("internal 경로는 403을 반환한다")
        void shouldReturn403ForInternalPaths(String path) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(path).build()
            );

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("차단 시 filter chain을 호출하지 않는다")
        void shouldNotCallChainWhenBlocked() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/internal/v1/seats/1").build()
            );

            filter.filter(exchange, chain).block();

            verify(chain, never()).filter(any());
        }
    }

    @Nested
    @DisplayName("통과 대상 경로 — chain 위임")
    class AllowedPaths {

        @ParameterizedTest(name = "[{index}] path={0}")
        @ValueSource(strings = {
                "/api/v1/events/1",
                "/api/v1/reservations",
                "/api/v1/payments/confirm",
                "/api/v1/waiting-room/status",
                "/actuator/health",
                "/fallback/service-unavailable",
                "/",
        })
        @DisplayName("외부 API 경로는 filter chain으로 위임한다")
        void shouldDelegateToChainForExternalPaths(String path) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get(path).build()
            );

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // 상태 코드 변경 없음 (기본 200)
            assertThat(exchange.getResponse().getStatusCode()).isNull();
            verify(chain, times(1)).filter(any());
        }

        @Test
        @DisplayName("internals 처럼 internal로 시작하지 않는 경로는 통과한다")
        void shouldNotBlockPathsWithSimilarPrefix() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/internals/status").build()
            );

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            verify(chain, times(1)).filter(any());
        }
    }

    @Nested
    @DisplayName("커스텀 패턴 설정")
    class CustomPatterns {

        @Test
        @DisplayName("쉼표로 구분된 다중 패턴이 모두 적용된다")
        void shouldBlockAllCustomPatterns() {
            ReflectionTestUtils.setField(filter, "blockedPatternsRaw", "/internal/**,/admin/**");
            filter.init();

            MockServerWebExchange adminExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/admin/users").build()
            );
            MockServerWebExchange internalExchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/internal/v1/seats/1").build()
            );

            filter.filter(adminExchange, chain).block();
            filter.filter(internalExchange, chain).block();

            assertThat(adminExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(internalExchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("패턴 앞뒤 공백은 무시된다")
        void shouldTrimPatternsBeforeMatching() {
            ReflectionTestUtils.setField(filter, "blockedPatternsRaw", "  /internal/**  ,  /admin/**  ");
            filter.init();

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/internal/v1/seats/1").build()
            );

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    @DisplayName("필터 order는 HIGHEST_PRECEDENCE + 2 여야 한다")
    void shouldHaveCorrectOrder() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE + 2);
    }
}
