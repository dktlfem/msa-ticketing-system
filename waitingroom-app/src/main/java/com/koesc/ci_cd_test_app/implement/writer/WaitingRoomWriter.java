package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.implement.mapper.WaitingRoomMapper;
import com.koesc.ci_cd_test_app.storage.entity.WaitingTokenEntity;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class WaitingRoomWriter {

    /**
     * 중복 진입 방지를 위해 ZADD NX 명령을 Lua 스크립트로 작성.
     * Java 레벨의 분기 처리보다 Redis 내부에서의 원자적 처리가 성능과 정합성 면에서 우수하다고 판단함.
     *
     * ZADD: Redis의 'Sorted Set(정렬된 집합)'에 데이터를 넣으라는 명령어 (티켓팅 대기열 순서)
     * NX: 'Only If Not Exists'의 약자임, 만약 이미 데이터가 있다면 넣지 않고, 없을 때만 새로 넣음.
     * KEYS[1]: 데이터를 저장할 장소(키 이름)
     * ARGV[1], ARGV[2]: 저장할 점수(순서)와 값(사용자 아이디 등)
     *
     * 이미 줄 서 있는 사람이 아니라면, 정해진 순서번호로 대기열에 추가함. 이미 있다면 아무 실행 x
     * -> 원자성 보장, 네트워크 왕복 횟수(RTT) 감소
     */
    private static final RedisScript<Long> ZADD_NX_SCRIPT = RedisScript.of("""
        return redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])
        """, Long.class);

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
     * 내부 API에서 토큰 상태를 즉시 바꿔야 할 때 사용
     */
    @Transactional
    public WaitingToken changeStatus(String tokenId, WaitingTokenStatus nextStatus) {
        WaitingTokenEntity entity = waitingTokenRepository.findById(tokenId)
                .orElseThrow(() -> new EntityNotFoundException("토큰을 찾을 수 없습니다. tokenId = " + tokenId));

        entity.changeStatus(nextStatus);

        WaitingTokenEntity saved = waitingTokenRepository.saveAndFlush(entity);
        return waitingRoomMapper.toDomain(saved);
    }

    /**
     * ACTIVE + not expired 일 때만 USED 로 변경
     * 성공하면 true, 실패하면 false
     */
    @Transactional
    public boolean consumeIfActive(String tokenId, LocalDateTime now) {
        return waitingTokenRepository.updateStatusIfCurrentAndNotExpired(
                tokenId,
                WaitingTokenStatus.ACTIVE,
                WaitingTokenStatus.USED,
                now
        ) == 1;
    }

    /**
     * ACTIVE + expired 일 때만 EXPIRED 로 변경
     * 성공하면 true, 이미 다른 요청이 처리했으면 false
     */
    @Transactional
    public boolean markExpiredIfActive(String tokenId, LocalDateTime now) {
        return waitingTokenRepository.updateStatusIfCurrentAndExpired(
                tokenId,
                WaitingTokenStatus.ACTIVE,
                WaitingTokenStatus.EXPIRED,
                now
        ) == 1;
    }

    /**
     * [Redis] 대기열 진입 (ZADD NX)
     * - 이미 들어온 userId면 score를 덮어쓰지 않고 false 반환
     * - 신규 진입이면 true 반환
     * - score는 최초 진입 시각을 유지
     *
     * Boolean -> Mono<Boolean>으로 변경
     * Non-blocking I/O: 리액티브 서버(Netty)는 데이터가 올 때까지 스레드를 비워두고 기다린다.
     * Mono는 지금 당장 값이 없지만, 작업이 끝나면 이 바구니에 담아둘게라는 비동기 약속임.
     */
    public Mono<Boolean> addToToken(Long eventId, Long userId) {
        String key = "waiting-room:event:" + eventId;
        String score = String.valueOf(System.currentTimeMillis());
        String member = userId.toString();


        return reactiveRedisTemplate.execute(
                        ZADD_NX_SCRIPT,
                        List.of(key),
                        List.of(score, member)
                )
                .next()
                .map(result -> result != null && result == 1L)
                .defaultIfEmpty(false);
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
