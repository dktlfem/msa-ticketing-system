package com.koesc.ci_cd_test_app.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

/**
 * Rate Limiter KeyResolver 설정 (ADR-0010)
 *
 * [왜 userId 기반인가?]
 * IP 기반 rate-limit은 NAT/VPN 환경에서 다수 사용자가 단일 IP를 공유할 때
 * 한 사용자의 과다 요청이 같은 IP의 다른 사용자 할당량을 소진하는 문제가 있다.
 * 인증된 사용자는 userId 단위로 제한하여 사용자별 공정한 트래픽 분배를 보장한다.
 *
 * [실행 순서 보장]
 * RequestRateLimiter는 route filter(GatewayFilter)이므로, GlobalFilter인
 * JwtAuthenticationFilter(HIGHEST_PRECEDENCE + 4)가 먼저 실행된 후 Auth-User-Id를
 * 주입한다. KeyResolver 호출 시점에 Auth-User-Id 헤더는 이미 사용 가능한 상태다.
 *
 * [Redis 키 설계]
 * "user:{userId}" / "ip:{address}" 접두사로 userId와 IP 간 키 충돌 방지.
 */
@Configuration
public class RateLimiterConfig {

    /**
     * userId 우선, IP 폴백 KeyResolver.
     *
     * 1순위 — Auth-User-Id 헤더 존재 시: "user:{userId}"
     *   JwtAuthenticationFilter(+4)가 JWT 검증 후 주입. 인증 사용자 → userId 기반 제한.
     *
     * 2순위 — X-Forwarded-For 존재 시: "ip:{실제클라이언트IP}"
     *   Nginx/LB 경유 시 실제 클라이언트 IP 추출 (첫 번째 값).
     *
     * 3순위 — RemoteAddress 폴백: "ip:{소켓IP}"
     *   direct 연결 또는 X-Forwarded-For 없는 경우. excluded-paths(/actuator/**)에 해당.
     */
    @Bean
    public KeyResolver principalOrIpKeyResolver() {
        return exchange -> {
            // Auth-User-Id: JwtAuthenticationFilter(GlobalFilter +4)가 JWT 검증 후 주입.
            // excluded-paths(/actuator/**, /fallback/**)는 JwtFilter를 통과하지 않으므로 null → IP 폴백.
            String userId = exchange.getRequest().getHeaders().getFirst("Auth-User-Id");
            if (userId != null && !userId.isBlank()) {
                return Mono.just("user:" + userId);
            }

            // IP 폴백 — Nginx/LB가 X-Forwarded-For를 주입한 경우 첫 번째(실제 클라이언트) IP 사용
            String forwardedFor = exchange.getRequest().getHeaders().getFirst("X-Forwarded-For");
            if (forwardedFor != null && !forwardedFor.isBlank()) {
                return Mono.just("ip:" + forwardedFor.split(",")[0].trim());
            }

            // 최종 폴백: 소켓 RemoteAddress (로컬 개발, direct 연결)
            return Mono.justOrEmpty(exchange.getRequest().getRemoteAddress())
                    .map(addr -> "ip:" + addr.getAddress().getHostAddress())
                    .defaultIfEmpty("ip:unknown");
        };
    }
}
