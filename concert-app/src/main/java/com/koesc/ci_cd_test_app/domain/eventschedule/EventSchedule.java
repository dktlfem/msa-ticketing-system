package com.koesc.ci_cd_test_app.domain.eventschedule;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * EventSchedule : 회차 사실
 * TODO 고도화 : CQRS 패턴을 도입하여 추후에 EventScheduleView (조회 전용) / EventSchedule (명령/변경용)
 */
@Getter
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class EventSchedule {

    private final Long scheduleId;
    private final Long eventId;
    private final LocalDateTime startTime;
    private final Integer totalSeats;
    private final LocalDateTime createdAt;
}
