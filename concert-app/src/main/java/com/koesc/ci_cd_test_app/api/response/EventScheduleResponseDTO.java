package com.koesc.ci_cd_test_app.api.response;

import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 목록은 20~200개가 될 수 있으니, 상세정보까지 내려주면 비용 낭비가 큼.
 * 그래서 목록용 DTO(EventScheduleDetailResponseDTO)를 따로 추가
 */
@Schema(description = "공연 회차 목록 조회 응답")
public record EventScheduleResponseDTO(
        @Schema(description = "공연 회차 고유 ID")
        Long scheduleId,

        @Schema(description = "공연 ID")
        Long eventId,

        @Schema(description = "공연 시작 일시")
        LocalDateTime startTime,

        @Schema(description = "총 좌석 수")
        Integer totalSeats,

        @Schema(description = "생성 일시")
        LocalDateTime createdAt
) {
    /**
     * Domain -> ResponseDTO (API 응답 모양으로 변환)
     *
     * Mapper에 안 두고 여기 정적 팩토리로 둔 이유
     *   -> 핵심 구현 Implementation 계층이 Presentation 계층의 DTO를 직접 알면 레이어링이 깨짐.
     *   -> Domain→DTO 변환이 필요하면 “Implement”가 아니라 “API(Presentation) 쪽”에 둬라.
     */
    public static EventScheduleResponseDTO from(EventSchedule eventSchedule) {
        return new EventScheduleResponseDTO(
                eventSchedule.getScheduleId(),
                eventSchedule.getEventId(),
                eventSchedule.getStartTime(),
                eventSchedule.getTotalSeats(),
                eventSchedule.getCreatedAt()
        );
    }
}
