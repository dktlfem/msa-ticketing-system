package com.koesc.ci_cd_test_app.implement.writer;

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

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
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

    @Mock
    private ReactiveZSetOperations<String, String> zSetOperations;

    @InjectMocks
    private WaitingRoomWriter waitingRoomWriter;

    /**
     * TODO : add대신 addIfAbsent를 고려
     * 대규모 트래픽에서 유저가 버튼을 연타했을 때, Score(시간)가 갱신되어 뒤로 밀리는 것을 방지하고
     * 첫 번째 진입 시점의 공정성을 유지하기 위함.
     *
     * 현재 구현은 addIfAbsent 대신,
     *  - rank로 존재 여부 확인 후
     *  - 없으면 add
     * 이 흐름이므로 테스트도 스펙을 맞춰줘야 함.
     */
    @Test
    @DisplayName("addToToken: 기존에 없으면(rank empty) ZADD(add) 수행 후 true 반환")
    void addToToken_Add_WhenNotExists() {

        // 1. given
        Long eventId = 1L;
        Long userId = 100L;

        String key = "waiting-room:event:" + eventId;
        String value = userId.toString();

        given(reactiveRedisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.rank(key, value)).willReturn(Mono.empty()); // 없음
        given(zSetOperations.add(eq(key), eq(value), anyDouble())).willReturn(Mono.just(true));

        // 2. when
        Mono<Boolean> mono = waitingRoomWriter.addToToken(eventId, userId);

        // 3. then
        StepVerifier.create(mono)
                .expectNext(true)
                .verifyComplete();

        verify(zSetOperations).add(eq(key), eq(value), anyDouble());
    }
}
