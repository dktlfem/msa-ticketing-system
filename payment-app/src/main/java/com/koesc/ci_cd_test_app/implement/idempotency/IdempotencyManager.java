package com.koesc.ci_cd_test_app.implement.idempotency;

import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 기반 Idempotency 관리.
 * 클라이언트는 모든 결제 요청에 Idempotency-Key 헤더를 포함해야 한다.
 *
 * 처리 흐름:
 * 1. startProcessing() → PROCESSING 상태 기록 (atomic setIfAbsent)
 * 2. 비즈니스 로직 수행
 * 3. complete() → 응답 JSON 저장 (TTL 24h)
 * 4. 이후 동일 키 재요청 → complete()에 저장된 응답 반환
 *
 * Redis 장애 시: DB UK(reservation_id, order_id)가 2차 방어.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyManager {

    private static final String KEY_PREFIX = "payment:idempotency:";
    private static final String PROCESSING = "PROCESSING";
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 처리 시작을 원자적으로 기록한다.
     *
     * @return 이미 완료된 응답이 있으면 Optional.of(responseJson), 없으면 Optional.empty()
     * @throws BusinessException PROCESSING 상태인 경우 (다른 스레드가 처리 중)
     */
    public Optional<String> startProcessing(String idempotencyKey) {
        String redisKey = KEY_PREFIX + idempotencyKey;
        String existing = stringRedisTemplate.opsForValue().get(redisKey);

        if (PROCESSING.equals(existing)) {
            log.warn("Idempotency conflict - key={}", idempotencyKey);
            throw new BusinessException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        }

        if (existing != null) {
            // 이미 완료된 응답 캐시 히트
            log.info("Idempotency cache hit - key={}", idempotencyKey);
            return Optional.of(existing);
        }

        // atomic setIfAbsent: 레이스 컨디션 방지
        Boolean set = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, PROCESSING, TTL);
        if (Boolean.FALSE.equals(set)) {
            throw new BusinessException(ErrorCode.PAYMENT_IDEMPOTENCY_CONFLICT);
        }

        return Optional.empty();
    }

    /**
     * 처리 완료 후 응답 JSON을 저장한다.
     */
    public void complete(String idempotencyKey, String responseJson) {
        stringRedisTemplate.opsForValue().set(KEY_PREFIX + idempotencyKey, responseJson, TTL);
    }

    /**
     * 처리 실패 시 PROCESSING 키를 제거한다.
     * 클라이언트가 재시도할 수 있도록 한다.
     */
    public void remove(String idempotencyKey) {
        stringRedisTemplate.delete(KEY_PREFIX + idempotencyKey);
    }
}
