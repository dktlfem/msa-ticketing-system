package com.koesc.ci_cd_test_app.implement.writer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("WaitingRoomWriter 단위 테스트 : 외부 의존성(Redis/DB)을 격리하고 로직의 정확성만 검증 (Mock)")
public class WaitingRoomWriterTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @InjectMocks
    private WaitingRoomWriter waitingRoomWriter;

    @Test
    @DisplayName("대기열 진입 시 addIfAbsent를 사용하여 최초 진입 시간을 보장하는지 검증")
    void addToToken_ShouldUseAddIfAbsent() {

        // Writer -> Redis ZSET addIfAbsent 호출
        // 1. given
        Long eventId = 1L;
        Long userId = 100L;
        String key = "waiting-room:event:" + eventId;

        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.addIfAbsent(eq(key), eq(userId.toString()), anyDouble())).willReturn(true);

        // 2. when
        Boolean result = waitingRoomWriter.addToToken(eventId, userId);

        // 3. then
        assertThat(result).isTrue();
        verify(zSetOperations).addIfAbsent(eq(key), eq(userId.toString()), anyDouble());

        // Why? : add대신 addIfAbsent를 썼나
        // 대규모 트래픽에서 유저가 버튼을 연타했을 때, Score(시간)가 갱신되어 뒤로 밀리는 것을 방지하고
        // 첫 번째 진입 시점의 공정성을 유지하기 위함.
    }
}
