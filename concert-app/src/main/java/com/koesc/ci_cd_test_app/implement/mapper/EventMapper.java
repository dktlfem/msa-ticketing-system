package com.koesc.ci_cd_test_app.implement.mapper;

import com.koesc.ci_cd_test_app.domain.Event;
import com.koesc.ci_cd_test_app.storage.entity.EventEntity;
import org.springframework.stereotype.Component;

@Component
public class EventMapper {

    // Entity -> Domain
    public Event toDomain(EventEntity entity) {
        if (entity == null) return null;

        return Event.builder()
                .eventId(entity.getEventId())
                .title(entity.getTitle())
                .description(entity.getDescription())
                .posterUrl(entity.getPosterUrl())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    // Domain -> Entity
    public EventEntity toEntity(Event event) {
        if (event == null) return null;

        return EventEntity.builder()
                .eventId(event.getEventId())
                .title(event.getTitle())
                .description(event.getDescription())
                .posterUrl(event.getPosterUrl())
                .createdAt(event.getCreatedAt())
                .build();
    }
}
