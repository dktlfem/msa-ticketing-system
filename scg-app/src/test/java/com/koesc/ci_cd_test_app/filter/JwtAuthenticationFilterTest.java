package com.koesc.ci_cd_test_app.filter;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * JwtAuthenticationFilter 단위 테스트
 *
 * 검증 목표 (ADR-0007 Phase 3 — 레거시 제거 완료):
 *   1. 유효한 JWT → Auth-Passport, Auth-User-Id 헤더 추가 후 chain 위임
 *   2. Authorization 헤더 없음 → 401
 *   3. Bearer prefix 없음 → 401
 *   4. 서명 오류 토큰 → 401
 *   5. 만료 토큰 → 401
 *   6. subject(userId) 없는 토큰 → 401
 *   7. 제외 경로(/actuator/**, /fallback/**) → JWT 없어도 chain 위임
 *   8. 401 응답 형식: RFC 7807 ProblemDetail (Content-Type: application/problem+json)
 *   9. 레거시 X-Auth-User-Id 헤더는 더 이상 주입되지 않는다
 */
class JwtAuthenticationFilterTest {

    // 최소 32바이트 (256bit) 이상 — HMAC-SHA256 요구사항
    private static final String SECRET = "test-secret-must-be-at-least-32-bytes-long!!";
    private static final String WRONG_SECRET = "wrong-secret-must-be-at-least-32-bytes-long!";

    private JwtAuthenticationFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthenticationFilter();
        filter.setJwtSecret(SECRET);
        filter.setExcludedPaths(List.of("/actuator/**", "/fallback/**"));

        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    // ── JWT 생성 헬퍼 ─────────────────────────────────────────

    private String makeValidToken(String userId, List<String> roles) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .claim("roles", roles)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key)
                .compact();
    }

    private String makeExpiredToken(String userId) {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .issuedAt(new Date(System.currentTimeMillis() - 10_000))
                .expiration(new Date(System.currentTimeMillis() - 5_000)) // 이미 만료
                .signWith(key)
                .compact();
    }

    private String makeTokenWithWrongSignature(String userId) {
        SecretKey wrongKey = Keys.hmacShaKeyFor(WRONG_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId)
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(wrongKey)
                .compact();
    }

    private String makeTokenWithoutSubject() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .claim("roles", List.of("USER"))
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(key)
                .compact();
    }

    // ── 정상 인증 ─────────────────────────────────────────────

    @Nested
    @DisplayName("유효한 JWT — 인증 성공")
    class ValidToken {

        @Test
        @DisplayName("Auth-Passport, Auth-User-Id 헤더가 downstream 요청에 추가된다")
        void shouldAddAuthHeadersOnSuccess() {
            String token = makeValidToken("100", List.of("USER", "ADMIN"));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Authorization", "Bearer " + token)
                            .build()
            );

            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            when(chain.filter(captor.capture())).thenReturn(Mono.empty());

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            ServerWebExchange mutated = captor.getValue();
            assertThat(mutated.getRequest().getHeaders().getFirst("Auth-User-Id")).isEqualTo("100");
            assertThat(mutated.getRequest().getHeaders().getFirst("Auth-Passport")).isNotBlank();
            // Phase 3: 레거시 헤더는 더 이상 주입되지 않는다
            assertThat(mutated.getRequest().getHeaders().containsKey("X-Auth-User-Id")).isFalse();
        }

        @Test
        @DisplayName("roles 클레임이 없어도 인증은 성공한다")
        void shouldSucceedWithoutRolesClaim() {
            SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
            String token = Jwts.builder()
                    .subject("42")
                    .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                    .signWith(key)
                    .compact();

            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Authorization", "Bearer " + token)
                            .build()
            );

            ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
            when(chain.filter(captor.capture())).thenReturn(Mono.empty());

            filter.filter(exchange, chain).block();

            assertThat(captor.getValue().getRequest().getHeaders().getFirst("Auth-User-Id")).isEqualTo("42");
            assertThat(captor.getValue().getRequest().getHeaders().getFirst("Auth-Passport")).isNotBlank();
        }

        @Test
        @DisplayName("인증 성공 시 filter chain이 정확히 1번 호출된다")
        void shouldCallChainExactlyOnce() {
            String token = makeValidToken("1", List.of("USER"));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Authorization", "Bearer " + token)
                            .build()
            );

            filter.filter(exchange, chain).block();

            verify(chain, times(1)).filter(any());
        }
    }

    // ── 인증 실패 — 401 ───────────────────────────────────────

    @Nested
    @DisplayName("인증 실패 — 401 반환")
    class AuthFailure {

        @Test
        @DisplayName("Authorization 헤더가 없으면 401을 반환한다")
        void shouldReturn401WhenNoAuthHeader() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1").build()
            );

            StepVerifier.create(filter.filter(exchange, chain))
                    .verifyComplete();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("Bearer prefix 없이 토큰만 전달하면 401을 반환한다")
        void shouldReturn401WhenNoBearerPrefix() {
            String token = makeValidToken("1", List.of("USER"));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Authorization", token) // Bearer 없음
                            .build()
            );

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("잘못된 서명의 토큰은 401을 반환한다")
        void shouldReturn401ForWrongSignature() {
            String token = makeTokenWithWrongSignature("1");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Authorization", "Bearer " + token)
                            .build()
            );

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("만료된 토큰은 401을 반환한다")
        void shouldReturn401ForExpiredToken() {
            String token = makeExpiredToken("1");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Authorization", "Bearer " + token)
                            .build()
            );

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("subject(userId)가 없는 토큰은 401을 반환한다")
        void shouldReturn401WhenSubjectMissing() {
            String token = makeTokenWithoutSubject();
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Authorization", "Bearer " + token)
                            .build()
            );

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            verify(chain, never()).filter(any());
        }

        @Test
        @DisplayName("완전히 깨진 토큰(random string)은 401을 반환한다")
        void shouldReturn401ForMalformedToken() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1")
                            .header("Authorization", "Bearer not.a.jwt")
                            .build()
            );

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("401 응답의 Content-Type은 application/problem+json 이다")
        void shouldReturnProblemJsonContentType() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/events/1").build()
            );

            filter.filter(exchange, chain).block();

            String contentType = exchange.getResponse().getHeaders().getFirst("Content-Type");
            assertThat(contentType).contains("application/problem+json");
        }
    }

    // ── 제외 경로 ─────────────────────────────────────────────

    @Nested
    @DisplayName("인증 제외 경로 — JWT 없이 통과")
    class ExcludedPaths {

        @Test
        @DisplayName("/actuator/health 는 JWT 없이 chain으로 위임한다")
        void shouldSkipAuthForActuator() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/actuator/health").build()
            );

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            verify(chain, times(1)).filter(any());
        }

        @Test
        @DisplayName("/fallback/service-unavailable 는 JWT 없이 통과한다")
        void shouldSkipAuthForFallback() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/fallback/service-unavailable").build()
            );

            filter.filter(exchange, chain).block();

            assertThat(exchange.getResponse().getStatusCode()).isNull();
            verify(chain, times(1)).filter(any());
        }

        @Test
        @DisplayName("제외 경로가 아닌 /api/v1/actuator 는 JWT 검증을 수행한다")
        void shouldNotExcludeNonActuatorPath() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/actuator").build()
            );

            filter.filter(exchange, chain).block();

            // JWT 없으므로 401
            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Test
    @DisplayName("필터 order는 HIGHEST_PRECEDENCE + 4 여야 한다")
    void shouldHaveCorrectOrder() {
        assertThat(filter.getOrder()).isEqualTo(Integer.MIN_VALUE + 4);
    }
}
