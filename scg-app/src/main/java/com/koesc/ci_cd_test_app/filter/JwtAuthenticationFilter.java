package com.koesc.ci_cd_test_app.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import javax.crypto.SecretKey;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JWT 서명 검증 및 인증 컨텍스트 전파 GlobalFilter.
 *
 * 동작 순서:
 * 1. RequestSanitizeFilter가 위조 헤더 제거 (order +3)
 * 2. 이 필터가 JWT 검증 후 헤더 추가 (order +4)
 *
 * 검증 성공 시 주입하는 헤더 (ADR-0007 Phase 3 — 레거시 제거 완료):
 *   - Auth-Passport: Base64url(JSON)  — 인증 컨텍스트 전체
 *   - Auth-User-Id: {userId}          — 인증 주체 식별
 *
 * roles 정보는 Auth-Passport에 포함된다.
 *
 * 검증 실패 시: 401 RFC 7807 ProblemDetail (GlobalErrorHandler와 동일 형식)
 *
 * 인증 제외 경로 (yml: gateway.security.excluded-paths):
 *   - /actuator/** : 내부 헬스/메트릭 — 외부에서는 InternalPathBlockFilter가 먼저 차단
 *   - /fallback/** : CB fallback 경로
 *
 * JWT Claims 규약:
 *   - sub (subject): userId
 *   - roles (custom): 역할 목록 (List<String> 또는 쉼표 구분 String)
 *   - jti (optional): 토큰 ID
 *   - iat: 토큰 발급 시각
 *
 * filter order: HIGHEST_PRECEDENCE + 4
 */
@Component
@ConfigurationProperties(prefix = "gateway.security")
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder BASE64_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String BEARER_PREFIX = "Bearer ";

    // ADR-0007 신규 헤더 (scg-app은 common-module 미의존이므로 자체 상수)
    private static final String HEADER_AUTH_PASSPORT = "Auth-Passport";
    private static final String HEADER_AUTH_USER_ID = "Auth-User-Id";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** yml: gateway.security.jwt.secret — 최소 32바이트(256bit) 이상 권장 */
    private String jwtSecret;

    /** yml: gateway.security.excluded-paths */
    private List<String> excludedPaths = List.of("/actuator/**", "/fallback/**");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();

        if (isExcluded(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            return writeUnauthorized(exchange, "Missing or invalid Authorization header", path);
        }

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims = parseToken(token);
            String userId = claims.getSubject();

            if (userId == null || userId.isBlank()) {
                return writeUnauthorized(exchange, "JWT subject (userId) is missing", path);
            }

            List<String> roles = extractRolesList(claims);
            String jti = claims.getId(); // nullable
            long issuedAt = claims.getIssuedAt() != null
                    ? claims.getIssuedAt().getTime() / 1000
                    : 0L;
            String clientIp = resolveClientIp(exchange);
            String passport = buildPassport(userId, roles, jti, issuedAt, clientIp);

            log.debug("[JWT_AUTH] userId={} path={}", userId, path);

            ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                    .header(HEADER_AUTH_PASSPORT, passport)
                    .header(HEADER_AUTH_USER_ID, userId)
                    .build();

            return chain.filter(exchange.mutate().request(mutatedRequest).build());

        } catch (ExpiredJwtException e) {
            log.warn("[JWT_AUTH] Token expired path={}", path);
            return writeUnauthorized(exchange, "JWT token has expired", path);
        } catch (JwtException e) {
            log.warn("[JWT_AUTH] Invalid token path={} reason={}", path, e.getMessage());
            return writeUnauthorized(exchange, "JWT token is invalid", path);
        }
    }

    private Claims parseToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRolesList(Claims claims) {
        Object roles = claims.get("roles");
        if (roles == null) return List.of();
        if (roles instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of(roles.toString().split(","));
    }

    private String buildPassport(String userId, List<String> roles, String jti,
                                 long issuedAt, String clientIp) {
        Map<String, Object> passport = new LinkedHashMap<>();
        passport.put("userId", userId);
        passport.put("roles", roles);
        passport.put("jti", jti);
        passport.put("issuedAt", issuedAt);
        passport.put("clientIp", clientIp);
        try {
            byte[] json = MAPPER.writeValueAsBytes(passport);
            return BASE64_ENCODER.encodeToString(json);
        } catch (JsonProcessingException e) {
            log.error("[JWT_AUTH] Failed to build Auth-Passport", e);
            return "";
        }
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        String forwarded = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }

    private boolean isExcluded(String path) {
        return excludedPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);
        problem.setTitle("Unauthorized");
        problem.setDetail(detail);
        problem.setInstance(URI.create(path));

        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        byte[] body = serializeProblemDetail(problem);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body))
        );
    }

    private byte[] serializeProblemDetail(ProblemDetail problem) {
        String json = String.format(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"instance\":\"%s\"}",
                problem.getTitle(),
                problem.getStatus(),
                problem.getDetail(),
                problem.getInstance() != null ? problem.getInstance() : ""
        );
        return json.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 4;
    }

    // --- ConfigurationProperties setters ---

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }
}
