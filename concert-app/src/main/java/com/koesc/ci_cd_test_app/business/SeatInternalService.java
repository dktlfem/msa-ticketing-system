package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.domain.SeatStatus;
import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import com.koesc.ci_cd_test_app.implement.reader.EventScheduleReader;
import com.koesc.ci_cd_test_app.implement.reader.SeatReader;
import com.koesc.ci_cd_test_app.implement.writer.SeatWriter;
import com.koesc.ci_cd_test_app.storage.entity.SeatEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class SeatInternalService {

    private final SeatReader seatReader;
    private final SeatWriter seatWriter;
    private final EventScheduleReader eventScheduleReader;

    @Transactional(readOnly = true)
    public SeatDetailResult readSeat(Long seatId) {
        Seat seat = seatReader.read(seatId);
        EventSchedule schedule = eventScheduleReader.read(seat.getScheduleId());

        return new SeatDetailResult(
                seat.getSeatId(),
                seat.getScheduleId(),
                schedule.getEventId(),
                seat.getSeatNo(),
                seat.getPrice(),
                seat.getStatus().name(),
                seat.getVersion()
        );
    }

    @Transactional
    public SeatCommandResult hold(Long seatId, String expectedStatus) {
        return changeStatus(seatId, expectedStatus, SeatStatus.AVAILABLE, SeatStatus.HOLD);
    }

    @Transactional
    public SeatCommandResult release(Long seatId, String expectedStatus) {
        return changeStatus(seatId, expectedStatus, SeatStatus.HOLD, SeatStatus.AVAILABLE);
    }

    @Transactional
    public SeatCommandResult confirm(Long seatId, String expectedStatus) {
        return changeStatus(seatId, expectedStatus, SeatStatus.HOLD, SeatStatus.SOLD);
    }

    private SeatCommandResult changeStatus(
            Long seatId,
            String rawExpectedStatus,
            SeatStatus expectedStatus,
            SeatStatus nextStatus
    ) {
        SeatStatus clientExpected = parseExpectedStatus(rawExpectedStatus, expectedStatus);

        Seat current = seatReader.read(seatId);
        if (current.getStatus() != clientExpected) {
            throw new IllegalStateException(toConflictCode(current.getStatus()));
        }

        Seat changed = switch (nextStatus) {
            case HOLD -> current.hold();
            case AVAILABLE -> current.release();
            case SOLD -> current.toBuilder()
                    .status(SeatStatus.SOLD)
                    .updatedAt(LocalDateTime.now())
                    .build();
        };

        try {
            Seat saved = seatWriter.updateWithFlush(changed, current.getVersion());

            return new SeatCommandResult(
                    saved.getSeatId(),
                    saved.getScheduleId(),
                    saved.getStatus().name(),
                    saved.getVersion()
            );
        } catch (OptimisticLockingFailureException e) {
            throw new IllegalStateException("SEAT_CONCURRENT_CONFLICT");
        }
    }

    private SeatStatus parseExpectedStatus(String raw, SeatStatus mustBe) {
        final SeatStatus parsed;

        try {
            parsed = SeatStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "BAD_REQUEST: expectedStatus 값이 올바르지 않습니다. expected = " + mustBe.name() + ", actual=" + raw
            );
        }

        if (parsed != mustBe) {
            throw new IllegalArgumentException(
                    "BAD_REQUEST: 이 엔드포인트는 expectedStatus = " + mustBe.name() + " 로 호출해야 합니다. actual=" + raw
            );
        }

        return parsed;
    }

    private String toConflictCode(SeatStatus status) {
        return switch (status) {
            case HOLD -> "SEAT_ALREADY_HELD";
            case SOLD -> "SEAT_ALREADY_SOLD";
            case AVAILABLE -> "SEAT_ALREADY_AVAILABLE";
        };
    }

    public record SeatDetailResult(
            Long seatId,
            Long scheduleId,
            Long eventId,
            Integer seatNo,
            BigDecimal price,
            String status,
            Long version
    ) {
    }

    public record SeatCommandResult(
            Long seatId,
            Long scheduleId,
            String status,
            Long version
    ) {
    }
}
