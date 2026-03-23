package com.koesc.ci_cd_test_app.filter;

import net.logstash.logback.marker.Markers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 전체 트래픽 Access Log GlobalFilter.
 *
 * 인증 실패(401), 차단(403), Bulkhead 거절(503) 등 모든 요청을 기록한다.
 * 필터 체인에서 가장 앞에 위치하기 때문에 전체 처리 시간(durationMs)을 측정한다.
 *
 * 기록 항목: timestamp(자동), requestId, method, path, statusCode, durationMs, clientIp
 * 출력 형식: LogstashEncoder가 JSON으로 직렬화 → app.log, console
 *
 * filter order: HIGHEST_PRECEDENCE + 1
 *   (RequestCorrelationFilter(+0) → 이 필터(+1) → 이후 모든 필터)
 */
@Component
public class AccessLogFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startMs = System.currentTimeMillis();
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().value();
        String clientIp = resolveClientIp(exchange);

        return chain.filter(exchange).doFinally(signal -> {
            long durationMs = System.currentTimeMillis() - startMs;
            int statusCode = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value()
                    : 0;

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("requestId", MDC.get("requestId"));
            fields.put("method", method);
            fields.put("path", path);
            fields.put("statusCode", statusCode);
            fields.put("durationMs", durationMs);
            fields.put("clientIp", clientIp);

            log.info(Markers.appendEntries(fields), "ACCESS");
        });
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
        return Ordered.HIGHEST_PRECEDENCE + 1;
    }
}
