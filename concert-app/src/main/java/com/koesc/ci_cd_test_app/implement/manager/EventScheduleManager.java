package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import com.koesc.ci_cd_test_app.domain.eventschedule.BookableStatus;
import com.koesc.ci_cd_test_app.domain.eventschedule.EventScheduleDetail;
import com.koesc.ci_cd_test_app.implement.reader.EventScheduleReader;
import com.koesc.ci_cd_test_app.implement.validator.EventScheduleValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventScheduleManager {

    private final EventScheduleReader eventScheduleReader;
    private final EventScheduleValidator eventScheduleValidator;

    /**
     * 유즈케이스 A) eventId -> schedules
     */
    public Page<EventSchedule> getSchedulesByEventId(Long eventId, Pageable pageable) {
        return eventScheduleReader.readByEventId(eventId, pageable);
    }

    /**
     * 유즈케이스 B) scheduleId -> detail (+ bookable)
     */
    public EventScheduleDetail getScheduleDetail(Long scheduleId) {
        EventSchedule schedule = eventScheduleReader.read(scheduleId);
        BookableStatus status = eventScheduleValidator.evaluateBookable(schedule);
        return new EventScheduleDetail(schedule, status);
    }
}
