package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.Event;
import com.koesc.ci_cd_test_app.implement.mapper.EventMapper;
import com.koesc.ci_cd_test_app.storage.entity.EventEntity;
import com.koesc.ci_cd_test_app.storage.repository.EventRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Service에 캐시 로직을 넣지 않는다.
 * Reader가 캐시 레이어를 먼저 뒤지고 없으면 DB로 가는 Read-Through 패턴을 전담한다
 */
@Component
@RequiredArgsConstructor
public class EventReader {

    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    // Caffeine 로컬 캐시 (L1)
    // sync = true로 Cache Stampede 방지
    @Cacheable(cacheNames = "eventCache", key = "#eventId", sync = true)
    public Event read(Long eventId) {

        // 1. Redis(L2) 확인
        String redisKey = "event: " + eventId;
        Event cachedEvent = (Event) redisTemplate.opsForValue().get(redisKey);
        if (cachedEvent != null) return cachedEvent;

        // 2. DB 확인 (L1, L2 모두 미스 시)
        EventEntity entity = eventRepository.findById(eventId)
                .orElseThrow(() -> new EntityNotFoundException("공연 정보를 찾을 수 없습니다."));
        Event domain = eventMapper.toDomain(entity);

        // 3. Redis(L2)에 다시 쓰기
        redisTemplate.opsForValue().set(redisKey, domain, Duration.ofHours(1));
        return domain;
    }

    public List<Event> readAll() {
        return eventRepository.findAll().stream()
                .map(eventMapper::toDomain)
                .toList();
    }
}
