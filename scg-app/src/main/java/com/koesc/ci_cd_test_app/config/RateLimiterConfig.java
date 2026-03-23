package com.koesc.ci_cd_test_app.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Rate Limiter 설정 빈 등록
 *
 * "IP 주소를 기준으로 요청을 제한함" 라는 규칙을 Spring에 Bean으로 등록
 */
@Configuration
public class RateLimiterConfig {

    /**
     * Rate Limiting 키를 클라이언트 IP로 결정.
     * X-Forwarded-For 헤더가 있으면 첫 번째 IP(실제 클라이언트)를 사용.
     */
    @Bean
    public KeyResolver remoteAddrKeyResolver() {
        return exchange -> {
            String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return Mono.just(forwardedFor.split(",")[0].trim());
            }
            return Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                    .map(addr -> addr.getAddress().getHostAddress())
                    .defaultIfEmpty("unknown");
        };
    }
}
