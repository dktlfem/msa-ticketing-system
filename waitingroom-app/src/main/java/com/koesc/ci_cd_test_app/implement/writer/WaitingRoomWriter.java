package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.implement.mapper.WaitingRoomMapper;
import com.koesc.ci_cd_test_app.storage.entity.WaitingTokenEntity;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class WaitingRoomWriter {

    private final WaitingTokenRepository waitingTokenRepository;
    private final WaitingRoomMapper waitingRoomMapper;
    // 기존 RedisTemplate -> ReactiveRedisTemplate으로 교체
    private final ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

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
     *
     * public Boolean addToToken(Long eventId, Long userId) {
     *         String key = "waiting-room:event:" + eventId;
     *
     *         // ZADD 사용 : Score를 현재 시간으로 설정하여 선착순 보장
     *         // addIfAbsent : 동일 유저가 여러 번 클릭해도 처음 진입한 시간을 유지하도록 설계 (선착순의 공정성 보장)
     *         // 반환값: true(신규 진입), false(이미 존재하여 score 갱신됨 - 여기선 시간 갱신 안 함)
     *
     *         // 병목 포인트 : 100만명이 동시에 접속하면 Redis 싱글 스레드가 ZADD 연산을 처리하다가 CPU 피크를 찍을 수도 있음.
     *         return reactiveRedisTemplate.opsForZSet().addIfAbsent(key, userId.toString(), System.currentTimeMillis());
     *     }
     */

    // Boolean -> Mono<Boolean>으로 변경
    // Non-blocking I/O: 리액티브 서버(Netty)는 데이터가 올 때까지 스레드를 비워두고 기다린다.
    // Mono는 지금 당장 값이 없지만, 작업이 끝나면 이 바구니에 담아둘게라는 비동기 약속임.
    public Mono<Boolean> addToToken(Long eventId, Long userId) {
        String key = "waiting-room:event:" + eventId;
        String value = userId.toString();
        double score = (double) System.currentTimeMillis();

        return reactiveRedisTemplate.opsForZSet()
                .rank(key, value) // 1. 먼저 해당 유저의 순위가 있는지 조회 (존재 확인)
                .hasElement() // 2. 결과값이 있는지 여부를 Mono<Boolean>으로 변환
                .flatMap(exists -> {
                    if (exists) {
                        // 3. 이미 존재하면 추가하지 않고 false 반환 (기존 선착순 시간 보존)
                        return Mono.just(false);
                    }
                    // 4. 존재하지 않을 때만 신규 추가
                    return reactiveRedisTemplate.opsForZSet().add(key, value, score);
                });
    }


    /**
     * [Redis] 대기열 퇴장 (ZREM)
     * 토큰 발급에 성공하면 대기열에서 삭제함
     *
     * Blocking(MVC) 환경에서는 삭제 작업이 성공하면 리턴할 값이 없으므로 void를 쓰는 것이 관례
     * -> delete()가 끝날 때까지 스레드가 기다렸다가 다음 줄 코드를 실행하므로, 반환값이 없어도 순서가 보장됨.
     * Reactive(WebFlux) 환경에서는 모든 작업이 데이터의 흐름(Stream)으로 이어지므로 void를 지양하고 Mono를 리턴해야함
     * -> 모든 작업이 비동기 약속 이므로, 메서드가 void를 리턴해버리면 작업이 언제 끝나는지 알 수 있는 방법이 사라짐.
     */
    public Mono<Long> removeFromQueue(Long eventId, Long userId) {
        String key = "waiting-room:event:" + eventId;
        return reactiveRedisTemplate.opsForZSet()
                .remove(key, userId.toString());
    }
}
