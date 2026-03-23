package com.koesc.ci_cd_test_app.filter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 서버가 응답을 보낼 때 보안 응답 헤더를 자동으로 붙여주는 필터
 *
 * 실제로 하는 일:
 * 클라이언트 요청 -> 게이트웨이 처리 -> 응답 보낼 때 아래 헤더 자동 추가
 *
 * 헤더 : 역할
 * - Strict-Transport-Security : "앞으로 1년간 HTTPS로만 접속할 것"
 * - X-Frame-Options: Deny : "이 페이지를 iframe 안에 넣지 말 것" (클릭재킹 방어)
 * - X-Content-Type-Options: nosniff : "파일 형식 멋대로 추측하지 말 것"
 * - Content-Security-Policy : "스크립트는 우리 도메인 것만 실행할 것"
 */
@Component
public class SecurityHeaderFilter implements GlobalFilter, Ordered {

    @Value("${gateway.security.headers.content-security-policy:default-src 'self'}")
    private String contentSecurityPolicy;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        response.getHeaders().set("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        response.getHeaders().set("X-Frame-Options", "DENY");
        response.getHeaders().set("X-Content-Type-Options", "nosniff");
        response.getHeaders().set("Content-Security-Policy", contentSecurityPolicy);
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 6;
    }
}
