package com.koesc.ci_cd_test_app.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * RequestSanitizeFilter 단위 테스트
 *
 * 검증 목표 (ADR-0007 Phase 3 — 레거시 제거 완료):
 *   1. 보안 헤더를 제거한다 (Auth-Passport, Auth-User-Id, Internal-Token)
 *   2. 일반 헤더(Authorization, Content-Type 등)는 건드리지 않는다
 *   3. 헤더가 없는 요청도 정상 처리한다 (NPE 없음)
 *   4. 제거 후 chain.filter()는 반드시 1번 호출된다
 *
 * 보안 의미:
 *   이 필터가 제거한 헤더를 JwtAuthenticationFilter(order+4)가
 *   JWT 검증 후 신뢰할 수 있는 값으로 다시 채운다.
 *   Sanitize 없이 JWT 필터만 있으면 클라이언트가 임의의 Auth-User-Id로
 *   다른 사용자를 사칭할 수 있다.
 */
class RequestSanitizeFilterTest {

    private RequestSanitizeFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RequestSanitizeFilter();
        filter.setSanitizeHeaders(List.of(
                "Auth-Passport", "Auth-User-Id", "Internal-Token"
        ));

        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    // ── 위조 헤더 제거 ────────────────────────────────────────

    @Nested
    @DisplayName("위조 인증 헤더 제거")
    class ForgedHeaderStrip {

        @ParameterizedTest(name = "[{index}] 헤더={0}")
        @ValueSource(strings = {
                "Auth-Passport", "Auth-User-Id", "Internal-Token"
        })
        @DisplayName("보안 헤더는 클라이언트 요청에서 제거된다")
        void shouldStripSecurityHeaders(String header) {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header(header, "forged-value")
                            .build()
            );

            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            when(chain.filter(captor.capture())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(captor.getValue().getRequest().getHeaders().containsKey(header)).isFalse();
        }

        @Test
        @DisplayName("Auth-User-Id 위조 시나리오: userId=999 를 담아 전송해도 제거된다")
        void shouldStripForgedUserId() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/reservations")
                            .header("Auth-User-Id", "999") // 공격자가 다른 userId를 설정
                            .build()
            );

            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            when(chain.filter(captor.capture())).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();

            assertThat(captor.getValue().getRequest().getHeaders().getFirst("Auth-User-Id")).isNull();
        }

        @Test
        @DisplayName("여러 보안 헤더가 동시에 있어도 모두 제거된다")
        void shouldStripMultipleForgedHeaders() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Auth-Passport", "forged-passport")
                            .header("Auth-User-Id", "forged-user")
                            .header("Internal-Token", "forged-internal")
                            .build()
            );

            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            when(chain.filter(captor.capture())).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();

            ServerWebExchange mutated = captor.getValue();
            assertThat(mutated.getRequest().getHeaders().containsKey("Auth-Passport")).isFalse();
            assertThat(mutated.getRequest().getHeaders().containsKey("Auth-User-Id")).isFalse();
            assertThat(mutated.getRequest().getHeaders().containsKey("Internal-Token")).isFalse();
        }
    }

    // ── 일반 헤더 보존 ────────────────────────────────────────

    @Nested
    @DisplayName("일반 헤더 보존")
    class NormalHeaderPreservation {

        @Test
        @DisplayName("Authorization 헤더는 제거하지 않는다")
        void shouldPreserveAuthorizationHeader() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Authorization", "Bearer eyJ...")
                            .build()
            );

            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            when(chain.filter(captor.capture())).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();

            assertThat(captor.getValue().getRequest().getHeaders().getFirst("Authorization"))
                    .isEqualTo("Bearer eyJ...");
        }

        @Test
        @DisplayName("X-Correlation-Id, Content-Type 등 일반 헤더는 그대로 전달된다")
        void shouldPreserveNormalHeaders() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/reservations")
                            .header("X-Correlation-Id", "abc-123")
                            .header("Content-Type", "application/json")
                            .header("X-Waiting-Token", "token-uuid")
                            .build()
            );

            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            when(chain.filter(captor.capture())).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();

            ServerWebExchange mutated = captor.getValue();
            assertThat(mutated.getRequest().getHeaders().getFirst("X-Correlation-Id")).isEqualTo("abc-123");
            assertThat(mutated.getRequest().getHeaders().getFirst("X-Waiting-Token")).isEqualTo("token-uuid");
        }
    }

    // ── 엣지 케이스 ───────────────────────────────────────────

    @Nested
    @DisplayName("엣지 케이스")
    class EdgeCases {

        @Test
        @DisplayName("보안 헤더가 없는 정상 요청도 예외 없이 chain으로 위임된다")
        void shouldPassThroughCleanRequests() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Authorization", "Bearer token")
                            .build()
            );

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain, times(1)).filter(any());
        }

        @Test
        @DisplayName("헤더가 전혀 없는 요청도 정상 처리된다")
        void shouldHandleRequestWithNoHeaders() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1").build()
            );

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain, times(1)).filter(any());
        }

        @Test
        @DisplayName("헤더 제거 후에도 chain.filter()는 반드시 호출된다")
        void shouldAlwaysCallChain() {
            // 보안 헤더 있음
            MockServerWebExchange withHeader = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Auth-User-Id", "forged")
                            .build()
            );
            // 보안 헤더 없음
            MockServerWebExchange withoutHeader = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1").build()
            );

            filter.filter(withHeader, chain).block();
            filter.filter(withoutHeader, chain).block();

            // 두 경우 모두 chain 호출
            verify(chain, times(2)).filter(any());
        }
    }

    @Test
    @DisplayName("필터 order는 HIGHEST_PRECEDENCE + 3 여야 한다")
    void shouldHaveCorrectOrder() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE + 3);
    }
}
