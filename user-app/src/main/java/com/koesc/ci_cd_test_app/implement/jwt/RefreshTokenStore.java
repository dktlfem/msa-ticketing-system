package com.koesc.ci_cd_test_app.implement.jwt;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * ADR: Refresh Token Redis 저장소
 *
 * [저장 구조]
 * Key:   refresh:token:{userId}
 * Value: refreshToken (JWT 문자열)
 * TTL:   jwt.refresh-token-validity-seconds (기본 7일)
 *
 * [설계 근거]
 * - userId 기준으로 1개의 Refresh Token만 유지 (단일 디바이스 정책)
 *   → 새 로그인 시 기존 Refresh Token을 덮어씀
 *   → 다중 디바이스 지원 필요 시 Key를 refresh:token:{userId}:{deviceId}로 확장 가능
 *
 * - Redis TTL로 만료 관리 → 별도 스케줄러 불필요
 * - 로그아웃 시 해당 Key 삭제로 즉시 무효화
 *
 * [면접 포인트]
 * Q. "왜 DB가 아닌 Redis에 저장하나요?"
 * A. Refresh Token은 조회 빈도가 높고(매 Access Token 재발급 시) 수명이 제한적이므로
 *    TTL 기반 자동 만료가 가능한 Redis가 RDB 대비 적합하다.
 *    또한 토큰 갱신은 단순 Key-Value 조회이므로 Redis의 O(1) 조회 성능이 유리하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefreshTokenStore {

    private static final String KEY_PREFIX = "refresh:token:";

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * Refresh Token 저장 (기존 토큰이 있으면 덮어씀)
     */
    public void save(Long userId, String refreshToken, long ttlSeconds) {
        String key = KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(key, refreshToken, ttlSeconds, TimeUnit.SECONDS);
        log.debug("[RefreshTokenStore] 저장 완료: userId={}", userId);
    }

    /**
     * 저장된 Refresh Token 조회
     *
     * @return 저장된 토큰 문자열, 없거나 만료 시 null
     */
    public String find(Long userId) {
        String key = KEY_PREFIX + userId;
        Object value = redisTemplate.opsForValue().get(key);
        return value != null ? value.toString() : null;
    }

    /**
     * Refresh Token 삭제 (로그아웃 시 호출)
     */
    public void delete(Long userId) {
        String key = KEY_PREFIX + userId;
        redisTemplate.delete(key);
        log.debug("[RefreshTokenStore] 삭제 완료: userId={}", userId);
    }

    /**
     * 저장된 토큰과 요청 토큰 일치 여부 확인
     * → Token Rotation 시 탈취된 구 토큰 재사용 방지
     */
    public boolean matches(Long userId, String refreshToken) {
        String stored = find(userId);
        return refreshToken.equals(stored);
    }
}
