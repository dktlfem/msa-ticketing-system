package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.implement.mapper.WaitingRoomMapper;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.ReactiveZSetOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.mockito.BDDMockito.given;

/**
 * WaitingRoomReaderTest 단위 테스트 : Reactive Redis 읽기 I/O 계약
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaitingRoomReader 단위 테스트 : 외부 의존성(Redis/DB)을 격리하고 로직의 정확성만 검증 (Mock)")
public class WaitingRoomReaderTest {

    @Mock
    private WaitingTokenRepository waitingTokenRepository;

    @Spy
    private WaitingRoomMapper waitingRoomMapper;

    @Mock
    private ReactiveRedisTemplate<String, String> reactiveRedisTemplate;

    @Mock
    private ReactiveZSetOperations<String, String> zSetOperations;

    @InjectMocks
    private WaitingRoomReader waitingRoomReader;

    @Test
    @DisplayName("Redis에서 유저의 대기 순번을 정확히 조회하는지 검증")
    void getRank_Success() {

        // waiting-room:event:{eventId} 키로 ZSET rank를 조회한다.
        // 1. given: Redis의 rank 연산 결과가 5(0부터 시작)라고 가정
        Long eventId = 1L;
        Long userId = 100L;
        String key = "waiting-room:event:" + eventId;

        given(reactiveRedisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.rank(key, userId.toString())).willReturn(Mono.just(5L));

        // 2. when
        Mono<Long> mono = waitingRoomReader.getRank(eventId, userId);

        // 3. then
        StepVerifier.create(mono)
                .expectNext(5L)
                .verifyComplete();
    }
}
