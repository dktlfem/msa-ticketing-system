package com.koesc.ci_cd_test_app.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 대기열 토큰(Queue-Token) Gateway 레벨 검증 GlobalFilter.
 *
 * ADR-0008: 왜 SCG에서 Queue-Token을 검증하는가?
 * ─────────────────────────────────────────────────────
 * JwtAuthenticationFilter(+4)가 "이 사람이 누구인지(인증)"를 검증한다면,
 * 이 필터는 "이 사람이 지금 예약할 자격이 있는지(비즈니스 인가 컨텍스트)"를 검증한다.
 *
 * 책임 분리 원칙(SoC):
 *   - SCG: Queue-Token 헤더 존재 여부 + UUID 형식 검증 (게이트웨이 계층)
 *   - booking-app: Queue-Token의 실제 유효성(ACTIVE, 만료 여부, userId/eventId 일치) 검증 (도메인 계층)
 *
 * Fail-Fast 전략:
 *   형식 자체가 잘못된 요청이나 헤더가 누락된 요청은 booking-app까지 보내지 않고
 *   Gateway에서 즉시 거절한다. downstream 서비스의 불필요한 DB 조회와 내부 API 호출을 줄인다.
 *
 * 토큰 전파:
 *   검증 통과 시 'X-Queue-Token-Id' 헤더를 downstream에 추가한다.
 *   booking-app은 이 헤더로 Queue-Token을 신뢰하여 WaitingRoomInternalClient 호출에 사용한다.
 *
 * 보안 고려:
 *   RequestSanitizeFilter(+3)에서 'X-Queue-Token-Id' 외부 위조를 제거하지 않으므로,
 *   이 필터 내부에서 mutate() 전에 기존 'X-Queue-Token-Id' 헤더를 명시적으로 제거한다.
 *   (위조 공격: 클라이언트가 Queue-Token 없이 X-Queue-Token-Id 헤더를 직접 주입하는 시도 방어)
 *
 * filter order: HIGHEST_PRECEDENCE + 5 (JwtAuthenticationFilter +4 이후)
 * → JwtAuth가 Auth-User-Id를 주입한 뒤에 실행되므로, userId 기반 추가 검증이 필요하다면 확장 가능.
 *
 * 검증 대상 경로 (yml: gateway.queue-token.protected-paths):
 *   - POST /api/v1/reservations/** (예약 생성 — 반드시 대기열 통과 후 접근)
 *
 * 검증 제외:
 *   - GET /api/v1/reservations/** (조회는 토큰 불필요)
 *   - 그 외 경로 (JWT 인증만으로 충분)
 */
@Component
@ConfigurationProperties(prefix = "gateway.queue-token")
public class QueueTokenValidationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(QueueTokenValidationFilter.class);

    /** 클라이언트가 요청 시 포함해야 하는 헤더. waitingroom-app이 발급한 tokenId. */
    static final String QUEUE_TOKEN_HEADER = "Queue-Token";

    /**
     * SCG가 downstream(booking-app)에 전달하는 헤더.
     *
     * ADR-0007 준수: X- 접두사를 사용하지 않는다 (RFC 6648 권고).
     * 'Auth-*' 네임스페이스: SCG가 검증 후 주입하는 신뢰된 헤더 (Auth-Passport, Auth-User-Id와 동일 계열).
     *
     * Auth-Passport와 별도 헤더로 유지하는 이유 (ADR-0008):
     *   - Auth-Passport는 JWT에서 추출한 "인증 컨텍스트" (who you are) → JwtAuthenticationFilter(+4) 생성
     *   - Auth-Queue-Token은 대기열 통과를 증명하는 "비즈니스 컨텍스트" (what you can do) → 이 필터(+5) 생성
     *   두 관심사는 다르고, 생명주기(생성 시점)도 다르다.
     *   Auth-Passport에 포함하려면 +5에서 디코딩 → 필드 추가 → 재인코딩이 필요해 복잡도만 늘어난다.
     *
     * RequestSanitizeFilter(+3) 의 sanitize-headers 목록에도 이 헤더가 포함되어 있어
     * 외부 클라이언트의 직접 주입을 차단한다. 이 필터(+5) 내부에서도 추가로 strip 후 재설정한다.
     */
    static final String FORWARDED_TOKEN_HEADER = "Auth-Queue-Token";

    /** 대기열 토큰이 필요한 HTTP 메서드 (비멱등 변이 요청만 적용) */
    private static final Set<HttpMethod> PROTECTED_METHODS = Set.of(
            HttpMethod.POST,
            HttpMethod.PUT,
            HttpMethod.PATCH,
            HttpMethod.DELETE
    );

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    /** yml: gateway.queue-token.protected-paths */
    private List<String> protectedPaths = List.of("/api/v1/reservations/**");

    /** yml: gateway.queue-token.enabled (false 시 필터 비활성화, 개발 편의) */
    private boolean enabled = true;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (!enabled) {
            return chain.filter(exchange);
        }

        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        HttpMethod method = request.getMethod();

        // 보호 경로 + 보호 메서드에 해당하지 않으면 통과
        if (!isProtected(path, method)) {
            return chain.filter(exchange);
        }

        // 외부에서 위조된 X-Queue-Token-Id 헤더 제거 (Sanitize 역할 수행)
        // 이 헤더는 SCG만 생성할 수 있어야 한다.
        ServerHttpRequest sanitizedRequest = request.mutate()
                .headers(headers -> headers.remove(FORWARDED_TOKEN_HEADER))
                .build();

        String queueToken = sanitizedRequest.getHeaders().getFirst(QUEUE_TOKEN_HEADER);

        // 1. 헤더 존재 여부 검증
        if (queueToken == null || queueToken.isBlank()) {
            log.warn("[QUEUE_TOKEN] Missing header path={} method={} clientIp={}",
                    path, method, resolveClientIp(exchange));
            return writeForbidden(exchange, "Queue-Token header is required for reservation", path);
        }

        // 2. UUID 형식 검증 (waitingroom-app이 UUID v4로 발급)
        if (!isValidUuid(queueToken.trim())) {
            log.warn("[QUEUE_TOKEN] Invalid format path={} tokenPrefix={}",
                    path, queueToken.length() > 8 ? queueToken.substring(0, 8) + "***" : "***");
            return writeForbidden(exchange, "Queue-Token format is invalid", path);
        }

        String tokenId = queueToken.trim();
        log.debug("[QUEUE_TOKEN] Valid token format path={} tokenPrefix={}***",
                path, tokenId.substring(0, 8));

        // 3. 형식 검증 통과 → X-Queue-Token-Id 헤더를 downstream에 추가하여 전파
        //    booking-app은 이 헤더를 WaitingRoomInternalClient 호출 시 tokenId로 사용한다.
        ServerHttpRequest mutatedRequest = sanitizedRequest.mutate()
                .header(FORWARDED_TOKEN_HEADER, tokenId)
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    /**
     * 보호 대상 경로 + 메서드 여부를 판단한다.
     *
     * ADR: GET(조회)은 대기열 없이 예약 내역을 조회할 수 있어야 하므로 제외.
     * POST/PUT/PATCH/DELETE(변이)는 반드시 대기열 통과 후 접근해야 한다.
     */
    private boolean isProtected(String path, HttpMethod method) {
        if (!PROTECTED_METHODS.contains(method)) {
            return false;
        }
        return protectedPaths.stream().anyMatch(pattern -> pathMatcher.match(pattern, path));
    }

    /**
     * UUID v4 형식 검증.
     *
     * waitingroom-app의 WaitingTokenEntity.tokenId는 UUID.randomUUID().toString()으로 생성된다.
     * 형식: xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx (표준 UUID 문자열)
     *
     * ADR: UUID 파싱 실패를 형식 오류로 처리한다. 실제 토큰 유효성(ACTIVE/EXPIRED 등)은
     * booking-app이 WaitingRoomInternalClient를 통해 DB에서 검증한다.
     */
    private boolean isValidUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
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

    private Mono<Void> writeForbidden(ServerWebExchange exchange, String detail, String path) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Forbidden");
        problem.setDetail(detail);
        problem.setInstance(URI.create(path));

        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String json = String.format(
                "{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"instance\":\"%s\"}",
                problem.getTitle(),
                problem.getStatus(),
                problem.getDetail(),
                path
        );
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body))
        );
    }

    @Override
    public int getOrder() {
        // JwtAuthenticationFilter(+4) 이후에 실행.
        // JWT 인증이 완료된 사용자에 대해서만 Queue-Token을 검증한다.
        // (미인증 사용자는 +4에서 이미 401 반환됨)
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    // --- ConfigurationProperties setters ---

    public List<String> getProtectedPaths() {
        return protectedPaths;
    }

    public void setProtectedPaths(List<String> protectedPaths) {
        this.protectedPaths = protectedPaths;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
