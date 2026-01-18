package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.Event;
import com.koesc.ci_cd_test_app.implement.mapper.EventMapper;
import com.koesc.ci_cd_test_app.storage.entity.EventEntity;
import com.koesc.ci_cd_test_app.storage.repository.EventRepository;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * EventReader : DB와 Redis 사이의 Read-Through 전략을 수행함.
 *
 *   1. Redis에 있으면 DB 안 가고 리턴
 *   2. Redis에 없으면 DB 조회 후 Redis 적재.
 *   3. DB에도 없으면 예외 발생.
 *
 *   위 3가지 시나리오 테스트
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("EventReader 단위 테스트 : Read-Through Cache (Mock)")
public class EventReaderTest {

    @Mock
    private EventRepository eventRepository;

    @Spy
    private EventMapper eventMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @InjectMocks
    private EventReader eventReader;

    @BeforeEach
    void setUp() {
        // RedisTemplate.opsForValue() 호출 시 Mock 객체 반환 Stubbing
        // readAll() 등 Redis를 안 쓰는 테스트를 위해 lenient() 사용 가능
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    @DisplayName("Redis(L2) 캐시 히트 시 DB 조회 없이 캐시된 데이터를 반환한다.")
    void read_CacheHit() {

        // 1. given
        Long eventId = 1L;
        String redisKey = "event: " + eventId;
        Event cachedEvent = Event.builder().eventId(eventId).title("Cached").build();

        given(valueOperations.get(redisKey)).willReturn(cachedEvent);

        // 2. when
        Event result = eventReader.read(eventId);

        // 3. then
        assertThat(result).isEqualTo(cachedEvent);
        verify(eventRepository, never()).findById(any()); // DB 조회 안 함
    }

    @Test
    @DisplayName("Redis 캐시 미스 시 DB에서 조회하고, Redis에 적재 후 반환한다.")
    void read_CacheMiss_DBHit() {

        // 1. given
        Long eventId = 1L;
        String redisKey  = "event: " + eventId;
        EventEntity entity = EventEntity.builder().eventId(eventId).title("DB Data").build();
        Event domain = Event.builder().eventId(eventId).title("DB Data").build();

        given(valueOperations.get(redisKey)).willReturn(null); // Cache Miss
        given(eventRepository.findById(eventId)).willReturn(Optional.of(entity));
        given(eventMapper.toDomain(entity)).willReturn(domain);

        // when
        Event result = eventReader.read(eventId);

        // then
        assertThat(result.getTitle()).isEqualTo("DB Data");
        verify(eventRepository).findById(eventId); // DB 조회 확인
        verify(valueOperations).set(eq(redisKey), eq(domain), any(Duration.class));

    }

    @Test
    @DisplayName("Redis와 DB 모두 데이터가 없으면 EntityNotFoundException이 발생한다.")
    void read_NotFound() {

        // 1. given
        Long eventId = 999L;
        String redisKey = "event: " + eventId;

        given(valueOperations.get(redisKey)).willReturn(null);
        given(eventRepository.findById(eventId)).willReturn(Optional.empty());

        // 2. when & then
        assertThatThrownBy(() -> eventReader.read(eventId))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessageContaining("공연 정보를 찾을 수 없습니다.");
    }
}
