package com.koesc.ci_cd_test_app.implement.mapper;

import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import com.koesc.ci_cd_test_app.storage.entity.EventScheduleEntity;
import org.springframework.stereotype.Component;

@Component
public class EventScheduleMapper {

    // Entity -> Domain
    public EventSchedule toDomain(EventScheduleEntity entity) {
        return EventSchedule.builder()
                .scheduleId(entity.getScheduleId())
                .eventId(entity.getEventId())
                .startTime(entity.getStartTime())
                .totalSeats(entity.getTotalSeats())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    // TODO: Domain -> Entity는 "쓰기(usecase)" 생길 때 추가
    // public EventScheduleEntity toEntity(EventSchedule)
}
