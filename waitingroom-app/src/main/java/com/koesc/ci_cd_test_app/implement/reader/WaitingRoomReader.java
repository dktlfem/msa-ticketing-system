package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.implement.mapper.WaitingRoomMapper;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WaitingRoomReader {

    private final WaitingTokenRepository waitingTokenRepository;
    private final WaitingRoomMapper waitingRoomMapper;
    private final RedisTemplate<String, Object> redisTemplate;

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

    public Long getRank(Long eventId, Long userId) {
        String key = "waiting-room:event:" + eventId;
        return redisTemplate.opsForZSet().rank(key, userId.toString());
    }
}
