package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.implement.mapper.WaitingRoomMapper;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

/**
 * WaitingRoomWriterTest 단위 테스트 : 중복 진입/선착순 보장
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaitingRoomWriter 단위 테스트 : (Reactive Redis write)")
public class WaitingRoomWriterTest {

    @Mock
    private WaitingTokenRepository waitingTokenRepository;

    @Spy
    private WaitingRoomMapper waitingRoomMapper;

    @Mock
    private ReactiveRedisTemplate<String, String > reactiveRedisTemplate;

    @InjectMocks
    private WaitingRoomWriter waitingRoomWriter;

    /**
     * Redis ZADD NX를 Lua로 실행해 대기열 중복 진입을 원자적으로 방지한다.
     * 신규 진입이면 1, 기존 진입자면 0을 반환한다.
     */
    @Test
    @DisplayName("addToToken: 신규 진입이면 true 반환")
    void addToToken_NewMember_ReturnsTrue() {

        // 1. given
        Long eventId = 1L;
        Long userId = 100L;

        String key = "waiting-room:event:" + eventId;

        // 2. when
        doReturn(Flux.just(1L)).when(reactiveRedisTemplate)
                .execute(
                        ArgumentMatchers.<RedisScript<Long>>any(),
                        eq(List.of(key)),
                        anyList()
                );

        // 3. then
        StepVerifier.create(waitingRoomWriter.addToToken(eventId, userId))
                .expectNext(true)
                .verifyComplete();
    }

    @Test
    @DisplayName("addToToken: 이미 진입한 유저면 false 반환")
    void addToToken_ExistingMember_ReturnsFalse() {

        // 1. given
        Long eventId = 1L;
        Long userId = 100L;

        String key = "waiting-room:event:" + eventId;

        doReturn(Flux.just(0L)).when(reactiveRedisTemplate)
                .execute(
                        ArgumentMatchers.<RedisScript<Long>>any(),
                        eq(List.of(key)),
                        anyList()
                );


        StepVerifier.create(waitingRoomWriter.addToToken(eventId, userId))
                .expectNext(false)
                .verifyComplete();
    }
}
