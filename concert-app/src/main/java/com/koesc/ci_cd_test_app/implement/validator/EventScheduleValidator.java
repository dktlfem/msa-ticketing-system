package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import com.koesc.ci_cd_test_app.domain.eventschedule.BookableReason;
import com.koesc.ci_cd_test_app.domain.eventschedule.BookableStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 예매 가능 정책
 *  1. 현재 시간이 startTime 이전인가?
 *  2. 이미 공연이 시작했으면 예매 불가
 *  3. 티켓팅 오픈 정책, 종료 정책, 취소된 공연 정책 등
 */

@Component
public class EventScheduleValidator {

    private final Clock clock;

    public EventScheduleValidator(Clock clock) {
        this.clock = clock;
    }

    public BookableStatus evaluateBookable(EventSchedule schedule) {
        LocalDateTime now = LocalDateTime.now(clock);

        if (schedule.getStartTime().isBefore(now)) {
            return new BookableStatus(
                    false,
                    BookableReason.ALREADY_STARTED,
                    "공연 시작 시간이 지났습니다."
            );
        }

        // TODO: 티켓팅 오픈 시간, 이벤트 상태(OPEN/CLOSED), 판매 정책 등
        return new BookableStatus(
                true,
                BookableReason.BOOKABLE,
                "예매 가능합니다."
        );
    }
}
