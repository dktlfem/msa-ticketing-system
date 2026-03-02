package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import com.koesc.ci_cd_test_app.global.error.exception.EventScheduleNotFoundException;
import com.koesc.ci_cd_test_app.implement.mapper.EventScheduleMapper;
import com.koesc.ci_cd_test_app.storage.repository.EventScheduleRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class EventScheduleReader {

    private final EventScheduleRepository eventScheduleRepository;
    private final EventScheduleMapper eventScheduleMapper;

    /**
     * 유즈 케이스 A) 특정 공연(eventId)의 회차 리스트 조회
     */
    public Page<EventSchedule> readByEventId(Long eventId, Pageable pageable) {
        return eventScheduleRepository.findByEventId(eventId, pageable)
                .map(eventScheduleMapper::toDomain);
    }

    /**
     * 유즈케이스 B) 특정 회차(scheduleId) 상세 조회 + 예매 가능 상태 계산
     */
    public EventSchedule read(Long scheduleId) {
        return eventScheduleRepository.findById(scheduleId)
                .map(eventScheduleMapper::toDomain)
                .orElseThrow(() -> new EventScheduleNotFoundException(scheduleId));
    }
}
