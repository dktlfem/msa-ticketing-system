package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.implement.mapper.WaitingRoomMapper;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("WaitingRoomReader 단위 테스트 : 외부 의존성(Redis/DB)을 격리하고 로직의 정확성만 검증 (Mock)")
public class WaitingRoomReaderTest {

    @Mock
    private WaitingTokenRepository waitingTokenRepository;

    @Spy
    private WaitingRoomMapper waitingRoomMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @InjectMocks
    private WaitingRoomReader waitingRoomReader;

    @Test
    @DisplayName("Redis에서 유저의 대기 순번을 정확히 조회하는지 검증")
    void getRank_Success() {

        // Reader -> Redis ZSET Rank 조회
        // 1. given: Redis의 rank 연산 결과가 5(0부터 시작)라고 가정
        Long eventId = 1L;
        Long userId = 100L;
        String key = "waiting-room:event:" + eventId;

        given(redisTemplate.opsForZSet()).willReturn(zSetOperations);
        given(zSetOperations.rank(key, userId.toString())).willReturn(5L);

        // 2. when
        Long rank = waitingRoomReader.getRank(eventId, userId);

        // 3. then
        assertThat(rank).isEqualTo(5L);

        // Reader는 복잡한 로직보다 '데이터를 어떻게 읽어오는가'가 중요함.
        // Redis Key 컨벤션이 일치하는지, Mocking이 정확한지를 테스트하는 것이 목적임.
    }
}
