package com.koesc.ci_cd_test_app.filter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 클라이언트가 위조한 내부 인증 헤더를 요청에서 제거하는 GlobalFilter.
 *
 * 반드시 JwtAuthenticationFilter보다 먼저 실행해야 한다.
 * → Sanitize가 위조 헤더를 제거한 후, JWT 필터가 검증된 값을 다시 추가하는 순서.
 *   이 순서가 바뀌면 JWT 필터가 추가한 헤더를 Sanitize가 지워버린다.
 *
 * 제거 대상 헤더 (yml: gateway.security.sanitize-headers, ADR-0007 Phase 3):
 *   - Auth-Passport   : JWT 필터가 검증 후 채워줌 (Base64url JSON)
 *   - Auth-User-Id    : JWT 필터가 검증 후 채워줌
 *   - Internal-Token  : 서비스 간 내부 통신 전용
 *
 * filter order: HIGHEST_PRECEDENCE + 3
 *   (RequestCorrelationFilter(+0) → AccessLogFilter(+1) → InternalPathBlockFilter(+2) → 이 필터(+3))
 */
@Component
@ConfigurationProperties(prefix = "gateway.security")
public class RequestSanitizeFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestSanitizeFilter.class);

    /** yml: gateway.security.sanitize-headers */
    private List<String> sanitizeHeaders = List.of(
            "Auth-Passport",
            "Auth-User-Id",
            "Internal-Token"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();
        String clientIp = resolveClientIp(exchange);

        for (String header : sanitizeHeaders) {
            if (exchange.getRequest().getHeaders().containsKey(header)) {
                log.warn("[SANITIZE] Stripped forged header={} clientIp={} path={}",
                        header, clientIp, exchange.getRequest().getPath().value());
                requestBuilder.headers(headers -> headers.remove(header));
            }
        }

        return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
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

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 3;
    }

    public List<String> getSanitizeHeaders() {
        return sanitizeHeaders;
    }

    public void setSanitizeHeaders(List<String> sanitizeHeaders) {
        this.sanitizeHeaders = sanitizeHeaders;
    }
}
