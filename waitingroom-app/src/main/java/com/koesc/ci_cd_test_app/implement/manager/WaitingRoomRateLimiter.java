package com.koesc.ci_cd_test_app.implement.manager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class WaitingRoomRateLimiter {

    // 기존 RedisTemplate -> ReactiveRedisTemplate으로 교체
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;
    private static final long MAX_ENTER_PER_SECOND = 100L;

    // boolean -> Mono<Boolean>으로 변경
    public Mono<Boolean> isAllowedToEnter(Long eventId) {

        // 현재 초(Second) 단위의 키 생성
        String key = "rate_limit:event:" + eventId + ":" + (System.currentTimeMillis() / 1000);

        // Redis INCR 연산 (원자성 보장)
        // 1. increment 연산을 수행하고 그 결과(Mono<Long>)를 처리한다.
        return reactiveRedisTemplate.opsForValue().increment(key)
                .flatMap(count -> {
                    // 2. 최초 호출(count == 1) 시 만료 시간 설정 파이프라인 연결
                    if (count != null && count == 1) {
                        return reactiveRedisTemplate.expire(key, Duration.ofSeconds(2))
                                .thenReturn(count); // expire 결과 대신 count를 다음 단계로 전달
                    }
                    return Mono.just(count);
                })
                // 3. 최종적으로 제한 수치와 비교하여 Boolean 반환
                .map(count -> count != null && count <= MAX_ENTER_PER_SECOND);
    }
}
