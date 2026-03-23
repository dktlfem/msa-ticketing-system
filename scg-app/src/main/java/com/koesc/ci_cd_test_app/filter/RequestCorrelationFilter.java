package com.koesc.ci_cd_test_app.filter;

import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Optional;
import java.util.UUID;

/**
 * X-Request-Id 생성 및 MDC 등록
 */
@Component
public class RequestCorrelationFilter implements GlobalFilter, Ordered {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String requestId = Optional.ofNullable(
                exchange.getRequest().getHeaders().getFirst(REQUEST_ID_HEADER)
        ).orElseGet(() -> UUID.randomUUID().toString());

        ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                .header(REQUEST_ID_HEADER, requestId)
                .build();

        exchange.getResponse().getHeaders().set(REQUEST_ID_HEADER, requestId);

        ServerWebExchange mutatedExchange = exchange.mutate()
                .request(mutatedRequest)
                .build();

        MDC.put(MDC_KEY, requestId);
        // Reactor Context에도 저장 — Hooks.enableAutomaticContextPropagation()과 함께
        // Slf4jMdcThreadLocalAccessor가 스레드 전환(CircuitBreaker fallback 등) 시 MDC 복원
        return chain.filter(mutatedExchange)
                .contextWrite(Context.of(MDC_KEY, requestId))
                .doFinally(signalType -> MDC.remove(MDC_KEY));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
