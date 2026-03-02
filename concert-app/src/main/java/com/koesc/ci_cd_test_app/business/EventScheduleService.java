package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import com.koesc.ci_cd_test_app.domain.eventschedule.EventScheduleDetail;
import com.koesc.ci_cd_test_app.implement.manager.EventScheduleManager;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class EventScheduleService {

    private final EventScheduleManager eventScheduleManager;

    /**
     * 유즈 케이스 A) 특정 공연(eventId)의 회차 리스트 조회
     * - Read-Heavy: readOnly 트랜잭션 적용
     */
    @Transactional(readOnly = true)
    public Page<EventSchedule> getEventSchedules(Long eventId, Pageable pageable) {
        return eventScheduleManager.getSchedulesByEventId(eventId, pageable);
    }

    /**
     * 유즈케이스 B) 특정 회차(scheduleId) 상세 조회 + 예매 가능 상태 계산
     */
    @Transactional(readOnly = true)
    public EventScheduleDetail getEventScheduleDetail(Long scheduleId) {
        return eventScheduleManager.getScheduleDetail(scheduleId);
    }
}
