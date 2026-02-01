package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.implement.mapper.WaitingRoomMapper;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class WaitingRoomReader {

    private final WaitingTokenRepository waitingTokenRepository;
    private final WaitingRoomMapper waitingRoomMapper;
    // 기존 RedisTemplate -> ReactiveRedisTemplate으로 교체
    private final ReactiveRedisTemplate<String, Object> reactiveRedisTemplate;

    /**
     * [잠재적 병목 포인트 & 해결 전략]
     * 요청 흐름 : Service -> Reader -> Repository -> DB
     * 병목 : 수만 명이 동시에 "나 입장 토큰 있어?"라고 물어볼 때 DB 부하 발생.
     * 해결 : 추후 이곳에 @Cacheable을 붙이거나, Redis에서 먼저 토큰 존재 여부를 확인하는 로직을 추가하여
     * DB 조회를 방어하는 '방패' 역할을 이 클래스가 수행하게 됨. (지금은 DB 조회만 구현)
     */
    public WaitingToken findToken(String tokenId) {
        return waitingTokenRepository.findById(tokenId)
                .map(waitingRoomMapper::toDomain)
                .orElse(null);
    }

    public WaitingToken findTokenByUser(Long userId, Long eventId) {
        return waitingTokenRepository.findByUserIdAndEventId(userId, eventId)
                .map(waitingRoomMapper::toDomain)
                .orElse(null);
    }

    /**
     * public Long getRank(Long eventId, Long userId) {
     * String key = "waiting-room:event:" + eventId;
     * return reactiveRedisTemplate.opsForZSet().rank(key, userId.toString());
     * }
     */

    // Long -> Mono<Long>으로 변경
    // Non-blocking I/O: 리액티브 서버(Netty)는 데이터가 올 때까지 스레드를 비워두고 기다린다.
    // Mono는 지금 당장 값이 없지만, 작업이 끝나면 이 바구니에 담아둘게라는 비동기 약속임.
    public Mono<Long> getRank(Long eventId, Long userId) {
        String key = "waiting-room:event:" + eventId;
        return reactiveRedisTemplate.opsForZSet()
                .rank(key, userId.toString());
    }
}
