package com.koesc.ci_cd_test_app.filter;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * /internal/** 경로 403 차단
 *
 * GlobalFilter:
 * WebFilter:
 */
@Component
public class InternalPathBlockFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(InternalPathBlockFilter.class);

    @Value("${gateway.security.internal-block-patterns:/internal/**}")
    private String blockedPatternsRaw;

    private List<String> blockedPatterns;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @PostConstruct
    public void init() {
        blockedPatterns = Arrays.stream(blockedPatternsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        boolean blocked = blockedPatterns.stream()
                .anyMatch(pattern -> pathMatcher.match(pattern, path));

        if (blocked) {
            String requestId = MDC.get("requestId");
            String clientIp = resolveClientIp(exchange);
            log.warn("[BLOCKED] requestId={} clientIp={} path={}", requestId, clientIp, path);
            exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
            return exchange.getResponse().setComplete();
        }

        return chain.filter(exchange);
    }

    private String resolveClientIp(ServerWebExchange exchange) {
        return Optional.ofNullable(
                        exchange.getRequest().getHeaders().getFirst("X-Forwarded-For"))
                .map(header -> header.split(",")[0].trim())
                .orElseGet(() -> Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                        .map(addr -> addr.getAddress().getHostAddress())
                        .orElse("unknown"));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 2;
    }
}
