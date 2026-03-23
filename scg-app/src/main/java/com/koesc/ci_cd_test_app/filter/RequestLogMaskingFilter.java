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
import java.util.Map;

/**
 * 요청 헤더를 로깅하되 민감 헤더를 마스킹하는 GlobalFilter.
 *
 * - Authorization: "Bearer [10자리][MASKED]" 형식
 * - gateway.logging.sensitive-headers에 등록된 헤더: "***MASKED***"
 * - 응답 status는 doFinally에서 로깅 (비동기 응답 완료 후)
 *
 * 필터 우선순위: HIGHEST_PRECEDENCE + 4 (가장 마지막 cross-cutting 필터)
 */
@Component
@ConfigurationProperties(prefix = "gateway.logging")
public class RequestLogMaskingFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RequestLogMaskingFilter.class);
    private static final String MASKED = "***MASKED***";
    private static final int BEARER_VISIBLE_LEN = 10;

    /** yml: gateway.logging.sensitive-headers */
    private List<String> sensitiveHeaders = List.of("Cookie", "X-Api-Key");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String method = request.getMethod().name();
        String path = request.getPath().value();

        if (log.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder();
            sb.append("[ACCESS] ").append(method).append(" ").append(path).append(" headers=[");
            for (Map.Entry<String, List<String>> entry : request.getHeaders().entrySet()) {
                String headerName = entry.getKey();
                String maskedValue = maskHeader(headerName, entry.getValue());
                sb.append(headerName).append(":").append(maskedValue).append(", ");
            }
            sb.append("]");
            log.debug(sb.toString());
        } else {
            log.info("[ACCESS] {} {}", method, path);
        }

        return chain.filter(exchange)
                .doFinally(signal -> {
                    int statusCode = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : 0;
                    log.info("[ACCESS_DONE] {} {} status={}", method, path, statusCode);
                });
    }

    private String maskHeader(String headerName, List<String> values) {
        if (values == null || values.isEmpty()) return "";

        // Authorization 헤더는 앞 10자 노출 후 마스킹
        if ("Authorization".equalsIgnoreCase(headerName)) {
            String value = values.get(0);
            if (value != null && value.startsWith("Bearer ")) {
                String token = value.substring(7); // "Bearer " 제거
                if (token.length() > BEARER_VISIBLE_LEN) {
                    return "Bearer " + token.substring(0, BEARER_VISIBLE_LEN) + "[MASKED]";
                }
                return "Bearer [MASKED]";
            }
            return MASKED;
        }

        // yml에 등록된 민감 헤더
        for (String sensitive : sensitiveHeaders) {
            if (sensitive.equalsIgnoreCase(headerName)) {
                return MASKED;
            }
        }

        return String.join(", ", values);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 8;
    }

    public List<String> getSensitiveHeaders() {
        return sensitiveHeaders;
    }

    public void setSensitiveHeaders(List<String> sensitiveHeaders) {
        this.sensitiveHeaders = sensitiveHeaders;
    }
}
