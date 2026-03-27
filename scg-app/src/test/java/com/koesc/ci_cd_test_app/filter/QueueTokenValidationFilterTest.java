package com.koesc.ci_cd_test_app.filter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * QueueTokenValidationFilter 단위 테스트
 *
 * ADR-0008 결정에 따라 검증하는 항목:
 *   1. Queue-Token 헤더 누락 → 403 Forbidden
 *   2. Queue-Token 형식 오류(UUID 아님) → 403 Forbidden
 *   3. 유효한 UUID Queue-Token → Auth-Queue-Token 헤더 추가 후 chain 위임
 *   4. GET 요청 → Queue-Token 없어도 통과 (조회는 대기열 불필요)
 *   5. 보호 대상 외 경로(POST /api/v1/payments) → Queue-Token 없어도 통과
 *   6. 403 응답 형식: RFC 7807 ProblemDetail (Content-Type: application/problem+json)
 *   7. Auth-Queue-Token 외부 위조 시도 → SCG에서 제거 후 검증된 값으로 교체
 *   8. enabled=false → 모든 요청 통과 (로컬 개발 모드)
 */
@DisplayName("QueueTokenValidationFilter")
class QueueTokenValidationFilterTest {

    private QueueTokenValidationFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new QueueTokenValidationFilter();
        // 기본 설정: enabled=true, protectedPaths=[/api/v1/reservations/**]

        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    // ───────────────────────────────────────────────
    //  [1] 보호 경로 + POST: Queue-Token 없음 → 403
    // ───────────────────────────────────────────────
    @Nested
    @DisplayName("보호 경로 POST 요청 — Queue-Token 헤더 검증")
    class ProtectedPostRequest {

        @Test
        @DisplayName("Queue-Token 헤더 없으면 403 Forbidden 반환")
        void missingQueueToken_returns403() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/reservations")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(chain);
        }

        @Test
        @DisplayName("Queue-Token 값이 빈 문자열이면 403 Forbidden 반환")
        void blankQueueToken_returns403() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/reservations")
                    .header("Queue-Token", "   ")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(chain);
        }

        @Test
        @DisplayName("Queue-Token이 UUID 형식이 아니면 403 Forbidden 반환")
        void invalidUuidFormat_returns403() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/reservations")
                    .header("Queue-Token", "not-a-valid-uuid-format")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(chain);
        }

        @Test
        @DisplayName("403 응답은 RFC 7807 ProblemDetail 형식 (application/problem+json)")
        void forbidden_response_isProblemDetail() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/reservations")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getHeaders().getContentType())
                    .hasToString("application/problem+json");
        }

        @Test
        @DisplayName("유효한 UUID Queue-Token → Auth-Queue-Token 헤더 추가 후 chain 위임")
        void validUuid_addsForwardedHeaderAndDelegatesToChain() {
            String tokenId = UUID.randomUUID().toString();
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/reservations")
                    .header("Queue-Token", tokenId)
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNull(); // 403 아님
            verify(chain).filter(argThat(ex -> {
                String forwardedToken = ex.getRequest().getHeaders()
                        .getFirst(QueueTokenValidationFilter.FORWARDED_TOKEN_HEADER);
                return tokenId.equals(forwardedToken);
            }));
        }

        @Test
        @DisplayName("Queue-Token 값에 공백이 있으면 trim 후 검증한다")
        void trimmedUuidIsAccepted() {
            String tokenId = UUID.randomUUID().toString();
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/reservations")
                    .header("Queue-Token", "  " + tokenId + "  ")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(argThat(ex ->
                    tokenId.equals(ex.getRequest().getHeaders()
                            .getFirst(QueueTokenValidationFilter.FORWARDED_TOKEN_HEADER))
            ));
        }
    }

    // ───────────────────────────────────────────────
    //  [2] GET 요청은 Queue-Token 없어도 통과
    // ───────────────────────────────────────────────
    @Nested
    @DisplayName("보호 경로 GET 요청 — Queue-Token 불필요 (조회)")
    class GetRequestPassThrough {

        @Test
        @DisplayName("GET /api/v1/reservations/{id} — Queue-Token 없어도 chain 위임")
        void getRequest_noQueueToken_passesThrough() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .get("/api/v1/reservations/123")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            verify(chain).filter(any());
        }
    }

    // ───────────────────────────────────────────────
    //  [3] 보호 대상 외 경로는 무조건 통과
    // ───────────────────────────────────────────────
    @Nested
    @DisplayName("비보호 경로 — Queue-Token 검증 대상 아님")
    class NonProtectedPath {

        @Test
        @DisplayName("POST /api/v1/payments — Queue-Token 없어도 chain 위임")
        void postToPayments_passesThrough() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/payments/confirm")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            verify(chain).filter(any());
        }

        @Test
        @DisplayName("POST /api/v1/waiting-room/join — Queue-Token 없어도 chain 위임")
        void postToWaitingRoom_passesThrough() {
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/waiting-room/join")
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            verify(chain).filter(any());
        }
    }

    // ───────────────────────────────────────────────
    //  [4] Auth-Queue-Token 외부 위조 방어
    // ───────────────────────────────────────────────
    @Nested
    @DisplayName("Auth-Queue-Token 외부 위조 방어")
    class ForgedHeaderDefense {

        @Test
        @DisplayName("Queue-Token 없이 Auth-Queue-Token만 주입 → 403 (위조 시도 차단)")
        void forgedForwardedHeaderWithoutQueueToken_returns403() {
            // 공격자가 Queue-Token 없이 직접 Auth-Queue-Token를 주입하는 시도
            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/reservations")
                    .header(QueueTokenValidationFilter.FORWARDED_TOKEN_HEADER,
                            UUID.randomUUID().toString())
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // Queue-Token 헤더가 없으므로 403 반환, 위조된 Auth-Queue-Token 무력화
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(chain);
        }

        @Test
        @DisplayName("유효한 Queue-Token + 위조된 Auth-Queue-Token → SCG가 덮어써서 신뢰된 값 전파")
        void validQueueToken_withForgedForwardedHeader_overwritesForgery() {
            String realTokenId = UUID.randomUUID().toString();
            String forgedTokenId = UUID.randomUUID().toString();

            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/reservations")
                    .header("Queue-Token", realTokenId)
                    .header(QueueTokenValidationFilter.FORWARDED_TOKEN_HEADER, forgedTokenId)
                    .build();
            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            // 위조된 forgedTokenId가 아닌 Queue-Token에서 추출한 realTokenId가 전파되어야 한다
            verify(chain).filter(argThat(ex -> {
                String forwardedToken = ex.getRequest().getHeaders()
                        .getFirst(QueueTokenValidationFilter.FORWARDED_TOKEN_HEADER);
                return realTokenId.equals(forwardedToken) && !forgedTokenId.equals(forwardedToken);
            }));
        }
    }

    // ───────────────────────────────────────────────
    //  [5] enabled=false → 필터 비활성화
    // ───────────────────────────────────────────────
    @Nested
    @DisplayName("enabled=false 모드 — 모든 요청 통과 (로컬 개발)")
    class DisabledMode {

        @Test
        @DisplayName("enabled=false 시 Queue-Token 없는 POST 예약 요청도 chain 위임")
        void disabledFilter_skipsValidation() {
            filter.setEnabled(false);

            MockServerHttpRequest request = MockServerHttpRequest
                    .post("/api/v1/reservations")
                    .build(); // Queue-Token 헤더 없음

            ServerWebExchange exchange = MockServerWebExchange.from(request);

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            verify(chain).filter(any());
        }
    }
}
