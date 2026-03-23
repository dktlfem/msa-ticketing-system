package com.koesc.ci_cd_test_app.ratelimit;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 없이 동작하는 인메모리 Rate Limiter: 대부분 고정 윈도우(Fixed Window) 방식을 사용
 * 1초 고정 윈도 카운터 방식으로 burstCapacity 초과 시 429 반환.
 *
 * ADR-0003:
 * LocalRateLimiter -> RedisRateLimiter로 전환됨. 이 클래스는 비활성화 상태.
 * (고정 윈도우 알고리즘) -> (Token Bucker 알고리즘)로 전환됨.
 * Redis 없는 로컬 개발 환경에서 참고용으로 보존.
 *
 * 재활성화: @Primary, @Component 주석 해제 + application.yml gateway.rate-limiter 설정 복원
 */
// @Primary  — ADR-0003: RedisRateLimiter가 기본 RateLimiter로 동작
// @Component
@ConfigurationProperties(prefix = "gateway.rate-limiter")
public class LocalRateLimiter extends AbstractRateLimiter<LocalRateLimiter.Config> {

    private static final String REMAINING_HEADER = "X-RateLimit-Remaining";
    private static final String REPLENISH_RATE_HEADER = "X-RateLimit-Replenish-Rate";
    private static final String BURST_CAPACITY_HEADER = "X-RateLimit-Burst-Capacity";

    // @ConfigurationProperties 바인딩 필드
    private int defaultReplenishRate = 10;
    private int defaultBurstCapacity = 20;
    private Map<String, Config> routes = new HashMap<>();

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public LocalRateLimiter() {
        super(Config.class, "local-rate-limiter", null);
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        // yml 바인딩 routes 맵 우선 조회 → 없으면 기본값 사용
        Config config = routes.getOrDefault(routeId, defaultConfig());
        long window = System.currentTimeMillis() / 1000L;
        String key = routeId + ":" + id + ":" + window;

        AtomicInteger counter = counters.computeIfAbsent(key, k -> new AtomicInteger(0));
        int count = counter.incrementAndGet();
        boolean allowed = count <= config.getBurstCapacity();
        int remaining = Math.max(0, config.getBurstCapacity() - count);

        return Mono.just(new Response(allowed, Map.of(
                REMAINING_HEADER, String.valueOf(remaining),
                REPLENISH_RATE_HEADER, String.valueOf(config.getReplenishRate()),
                BURST_CAPACITY_HEADER, String.valueOf(config.getBurstCapacity())
        )));
    }

    /** 60초마다 2분 이상 지난 윈도 키를 제거해 메모리 누수 방지. */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpiredCounters() {
        long currentWindow = System.currentTimeMillis() / 1000L;
        counters.entrySet().removeIf(entry -> {
            String[] parts = entry.getKey().split(":");
            if (parts.length < 3) return true;
            try {
                return currentWindow - Long.parseLong(parts[parts.length - 1]) > 120;
            } catch (NumberFormatException e) {
                return true;
            }
        });
    }

    private Config defaultConfig() {
        Config c = new Config();
        c.setReplenishRate(defaultReplenishRate);
        c.setBurstCapacity(defaultBurstCapacity);
        return c;
    }

    // @ConfigurationProperties setter
    public void setDefaultReplenishRate(int defaultReplenishRate) {
        this.defaultReplenishRate = defaultReplenishRate;
    }

    public void setDefaultBurstCapacity(int defaultBurstCapacity) {
        this.defaultBurstCapacity = defaultBurstCapacity;
    }

    public void setRoutes(Map<String, Config> routes) {
        this.routes = new ConcurrentHashMap<>(routes);
    }

    public Map<String, Config> getRoutes() {
        return routes;
    }

    @Data
    public static class Config {
        private int replenishRate = 10;
        private int burstCapacity = 20;
    }
}
