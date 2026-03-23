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
 * 인증된 사용자의 행위를 기록하는 Audit Log GlobalFilter.
 *
 * JwtAuthenticationFilter(+4)가 Auth-User-Id 헤더를 세팅한 이후에 실행(+5)되어
 * 검증된 userId를 감사 로그에 포함한다.
 *
 * 비인증 요청(401 반환된 경우)은 JwtAuthenticationFilter에서 체인이 중단되므로
 * 이 필터까지 도달하지 않는다. 감사 로그에는 인증된 행위만 기록된다.
 *
 * 기록 항목: timestamp(자동), requestId, userId, method, path, statusCode, durationMs
 * 출력 형식: LogstashEncoder → audit.log 파일 (logback-spring.xml에서 분리된 appender)
 *
 * filter order: HIGHEST_PRECEDENCE + 5
 */
@Component
public class AuditLogFilter implements GlobalFilter, Ordered {

    /**
     * logger 이름을 "audit.log"로 지정.
     * logback-spring.xml에서 이 logger 이름을 additivity=false 로 설정해
     * 감사 로그가 app.log에 중복 기록되지 않도록 분리한다.
     */
    private static final Logger log = LoggerFactory.getLogger("audit.log");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long startMs = System.currentTimeMillis();
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getPath().value();

        // JwtAuthenticationFilter가 검증 후 추가한 헤더에서 userId 추출 (ADR-0007 신규 헤더)
        String userId = exchange.getRequest().getHeaders().getFirst("Auth-User-Id");

        return chain.filter(exchange).doFinally(signal -> {
            long durationMs = System.currentTimeMillis() - startMs;
            int statusCode = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value()
                    : 0;

            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("requestId", MDC.get("requestId"));
            fields.put("userId", userId != null ? userId : "anonymous");
            fields.put("method", method);
            fields.put("path", path);
            fields.put("statusCode", statusCode);
            fields.put("durationMs", durationMs);

            log.info(Markers.appendEntries(fields), "AUDIT");
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }
}
