package com.koesc.ci_cd_test_app.implement.manager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class WaitingRoomRateLimiter {

    private final RedisTemplate<String, Object> redisTemplate;
    private static final long MAX_ENTER_PER_SECOND = 100L;

    public boolean isAllowedToEnter(Long eventId) {

        // 현재 초(Second) 단위의 키 생성
        String key = "rate_limit:event:" + eventId + ":" + (System.currentTimeMillis() / 1000);

        // Redis INCR 연산 (원자성 보장)
        Long count = redisTemplate.opsForValue().increment(key);

        if (count != null && count == 1) {
            redisTemplate.expire(key, 2, TimeUnit.SECONDS); // 2초 후 자동 삭제
        }

        return count != null && count <= MAX_ENTER_PER_SECOND;
    }
}
