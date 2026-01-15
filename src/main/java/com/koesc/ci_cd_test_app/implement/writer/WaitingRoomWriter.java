package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.implement.mapper.WaitingRoomMapper;
import com.koesc.ci_cd_test_app.storage.entity.WaitingTokenEntity;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class WaitingRoomWriter {

    private final WaitingTokenRepository waitingTokenRepository;
    private final WaitingRoomMapper waitingRoomMapper;
    private final RedisTemplate<String, Object> redisTemplate; // Redis 추가

    /**
     * 토큰 발급 및 저장
     * 흐름 : Redis 대기열 통과 -> Manager -> Writer -> DB 저장
     */
    public WaitingToken save(WaitingToken waitingToken) {
        WaitingTokenEntity entity = waitingRoomMapper.toEntity(waitingToken);
        WaitingTokenEntity savedEntity = waitingTokenRepository.save(entity);
        return waitingRoomMapper.toDomain(savedEntity);
    }

    /**
     * 토큰 만료 처리
     * Transactional을 통해 더티 체킹(Dirty Checking)으로 업데이트 수행
     */
    @Transactional
    public void expireToken(String tokenId) {
        waitingTokenRepository.findById(tokenId).ifPresent(entity -> {
            entity.expire(); // Entity 내부 로직 호출
            // 별도의 save 호출 없이 트랜잭션 종료 시점에 update 쿼리 발생
        });
    }

    /**
     * [Redis] 대기열 진입 (ZADD)
     * Score를 현재 시간(System.currentTimeMillis())으로 설정하여 선착순 보장
     */
    public Boolean addToToken(Long eventId, Long userId) {
        String key = "waiting-room:event:" + eventId;

        // ZADD 사용 : Score를 현재 시간으로 설정하여 선착순 보장
        // addIfAbsent : 동일 유저가 여러 번 클릭해도 처음 진입한 시간을 유지하도록 설계 (선착순의 공정성 보장)
        // 반환값: true(신규 진입), false(이미 존재하여 score 갱신됨 - 여기선 시간 갱신 안 함)

        // 병목 포인트 : 100만명이 동시에 접속하면 Redis 싱글 스레드가 ZADD 연산을 처리하다가 CPU 피크를 찍을 수도 있음.
        return redisTemplate.opsForZSet().addIfAbsent(key, userId.toString(), System.currentTimeMillis());
    }


    /**
     * [Redis] 대기열 퇴장 (ZREM)
     * 토큰 발급에 성공하면 대기열에서 삭제함
     */
    public void removeFromQueue(Long eventId, Long userId) {
        String key = "waiting-room:event:" + eventId;
        redisTemplate.opsForZSet().remove(key, userId.toString());
    }
}
