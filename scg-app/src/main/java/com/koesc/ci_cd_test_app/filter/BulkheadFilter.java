package com.koesc.ci_cd_test_app.filter;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Route 단위 동시 요청 제한 GlobalFilter.
 *
 * BulkheadRegistry에서 route ID로 Bulkhead 인스턴스를 획득한다.
 * 인스턴스가 없으면 default 설정(maxConcurrentCalls=20)으로 자동 생성.
 * payment-service는 Resilience4jConfig에서 maxConcurrentCalls=10으로 사전 등록.
 *
 * 동시 요청 수 초과 시: RFC 7807 ProblemDetail (503, application/problem+json)
 * 필터 우선순위: HIGHEST_PRECEDENCE + 3 (CB보다 앞, 마스킹 필터보다 뒤)
 */
@Component
@ConfigurationProperties(prefix = "gateway.bulkhead")
public class BulkheadFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(BulkheadFilter.class);

    /** 기본 maxConcurrentCalls (yml override 가능) */
    private int defaultMaxConcurrentCalls = 20;

    /** route ID → maxConcurrentCalls 오버라이드 */
    private Map<String, Integer> routes = new HashMap<>();

    private final BulkheadRegistry bulkheadRegistry;

    public BulkheadFilter(BulkheadRegistry bulkheadRegistry) {
        this.bulkheadRegistry = bulkheadRegistry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // fallback 경로는 Bulkhead 적용 제외
        String path = exchange.getRequest().getPath().value();
        if (path.startsWith("/fallback")) {
            return chain.filter(exchange);
        }

        String routeId = resolveRouteId(exchange);
        Bulkhead bulkhead = bulkheadRegistry.bulkhead(routeId);

        if (!bulkhead.tryAcquirePermission()) {
            log.warn("[BULKHEAD_REJECTED] routeId={} path={} available={}",
                    routeId, path, bulkhead.getMetrics().getAvailableConcurrentCalls());
            return writeBulkheadResponse(exchange, routeId);
        }

        return chain.filter(exchange)
                .doFinally(signal -> bulkhead.releasePermission());
    }

    private String resolveRouteId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null ? route.getId() : "default";
    }

    private Mono<Void> writeBulkheadResponse(ServerWebExchange exchange, String routeId) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
        problem.setTitle("Too Many Concurrent Requests");
        problem.setDetail("Service '" + routeId + "' is currently at capacity. Please retry shortly.");
        problem.setInstance(URI.create(exchange.getRequest().getPath().value()));

        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        byte[] body = serializeProblemDetail(problem);
        return exchange.getResponse().writeWith(
                Mono.just(exchange.getResponse().bufferFactory().wrap(body))
        );
    }

    /**
     * Jackson 의존성 없이 ProblemDetail을 수동 직렬화.
     * SCG는 WebFlux 기반이므로 Jackson ObjectMapper가 컨텍스트에 있으나,
     * GlobalFilter에서 직접 주입하지 않기 위해 단순 포맷으로 처리.
     */
    private byte[] serializeProblemDetail(ProblemDetail problem) {
        String json = String.format(
                "{\"type\":\"%s\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\",\"instance\":\"%s\"}",
                problem.getType() != null ? problem.getType() : "about:blank",
                problem.getTitle(),
                problem.getStatus(),
                problem.getDetail(),
                problem.getInstance() != null ? problem.getInstance() : ""
        );
        return json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 7;
    }

    // --- ConfigurationProperties setters ---

    public int getDefaultMaxConcurrentCalls() {
        return defaultMaxConcurrentCalls;
    }

    public void setDefaultMaxConcurrentCalls(int defaultMaxConcurrentCalls) {
        this.defaultMaxConcurrentCalls = defaultMaxConcurrentCalls;
    }

    public Map<String, Integer> getRoutes() {
        return routes;
    }

    public void setRoutes(Map<String, Integer> routes) {
        this.routes = routes;
    }
}
