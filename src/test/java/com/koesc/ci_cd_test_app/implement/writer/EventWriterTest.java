package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.Event;
import com.koesc.ci_cd_test_app.implement.mapper.EventMapper;
import com.koesc.ci_cd_test_app.storage.entity.EventEntity;
import com.koesc.ci_cd_test_app.storage.repository.EventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * EventWriter : 저장과 동시에 Cache Eviction(삭제)을 통해 데이터 정합성을 맞춘다.
 *
 *   1. Entity 변환 및 DB 저장
 *   2. Redis 캐시 삭제 (명시적 호출 확인)
 *   3. Domain 반환
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("EventWriter 단위 테스트 : Write-Through/Evict (Mock)")
public class EventWriterTest {

    @Mock
    private EventRepository eventRepository;

    @Spy
    private EventMapper eventMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @InjectMocks
    private EventWriter eventWriter;

    @Test
    @DisplayName("공연 정보 저장 시 DB에 저장하고 Redis 캐시를 명시적으로 삭제한다.")
    void save_Success() {

        // 1. given
        Long eventId = 1L;
        Event event = Event.builder().eventId(eventId).title("New").build();
        EventEntity entity = EventEntity.builder().eventId(eventId).title("New").build();
        EventEntity savedEntity = EventEntity.builder().eventId(eventId).title("New").build();

        given(eventMapper.toEntity(event)).willReturn(entity);
        given(eventRepository.save(entity)).willReturn(savedEntity);
        given(eventMapper.toDomain(savedEntity)).willReturn(event);

        // 2. when
        Event result = eventWriter.save(event);

        // 3. then
        verify(eventRepository).save(any(EventEntity.class));

        // [중요] 저장 후 반드시 캐시 키가 삭제되었는지 확인 (Write-Through 전략 검증)
        verify(redisTemplate).delete("event: " + eventId);
    }
}
