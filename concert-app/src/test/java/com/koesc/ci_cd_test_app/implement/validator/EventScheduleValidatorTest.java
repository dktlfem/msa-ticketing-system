package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.domain.eventschedule.BookableReason;
import com.koesc.ci_cd_test_app.domain.eventschedule.BookableStatus;
import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.*;

/**
 * 예매 가능 여부(bookable)가  시간/정책에 따라 잘 계산되는지 테스트
 * Clock 고정해서 "미래면 true, 과거면 false" 같은 핵심 분기 보장
 */
public class EventScheduleValidatorTest {

    private final Clock fixedClock =
            Clock.fixed(Instant.parse("2030-01-01T10:00:00Z"), ZoneId.of("UTC"));

    private final EventScheduleValidator validator = new EventScheduleValidator(fixedClock);

    @Test
    @DisplayName("공연 시작 시간이 이미 지났으면 예매 불가(ALREADY_STARTED)")
    void notBookable_whenAlreadyStarted() {
        LocalDateTime now = LocalDateTime.now(fixedClock);

        EventSchedule schedule = EventSchedule.builder()
                .scheduleId(1L)
                .eventId(10L)
                .startTime(now.minusMinutes(1)) // 과거
                .totalSeats(100)
                .createdAt(now.minusDays(1))
                .build();

        BookableStatus status = validator.evaluateBookable(schedule);

        assertThat(status.bookable()).isFalse();
        assertThat(status.code()).isEqualTo(BookableReason.ALREADY_STARTED);
        assertThat(status.message()).contains("지났습니다.");

    }

    @Test
    @DisplayName("공연 시작 시간이 아직 안 왔으면 예매 가능(BOOKABLE)")
    void bookable_whenNotStartedYet() {
        LocalDateTime now = LocalDateTime.now(fixedClock);

        EventSchedule schedule = EventSchedule.builder()
                .scheduleId(1L)
                .eventId(10L)
                .startTime(now.plusMinutes(1)) // 미래
                .totalSeats(100)
                .createdAt(now.minusDays(1))
                .build();

        BookableStatus status = validator.evaluateBookable(schedule);

        assertThat(status.bookable()).isTrue();
        assertThat(status.code()).isEqualTo(BookableReason.BOOKABLE);
    }

    @Test
    @DisplayName("경계값: startTime == now 일 때 현재 정책은 예매 가능으로 판정된다(문서화)")
    void boundary_startTimeEqualsNow_isBookableInCurrentPolicy() {
        LocalDateTime now = LocalDateTime.now(fixedClock);

        EventSchedule schedule = EventSchedule.builder()
                .scheduleId(1L)
                .eventId(10L)
                .startTime(now) // 경계값
                .totalSeats(100)
                .createdAt(now.minusDays(1))
                .build();

        BookableStatus status = validator.evaluateBookable(schedule);

        assertThat(status.bookable()).isTrue();
    }
}
