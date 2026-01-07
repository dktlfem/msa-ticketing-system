package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.Event;
import com.koesc.ci_cd_test_app.implement.mapper.EventMapper;
import com.koesc.ci_cd_test_app.storage.entity.EventEntity;
import com.koesc.ci_cd_test_app.storage.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventWriter {

    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @CacheEvict(cacheNames = "eventCache", key = "#event.eventId") // 저장 시 L1 캐시 삭제
    public Event save(Event event) {

        // 1. Domain -> Entity 변환 (Mapper 역할)
        EventEntity entity = eventMapper.toEntity(event);

        // 2. DB 저장 (Repository 역할)
        EventEntity savedEntity = eventRepository.save(entity);

        // Redis(L2) 캐시도 함께 삭제하여 데이터 정합성 보장
        redisTemplate.delete("event: " + event.getEventId());

        // 3. Entity -> Domain 변환 (Mapper 역할)
        return eventMapper.toDomain(savedEntity);
    }
}
