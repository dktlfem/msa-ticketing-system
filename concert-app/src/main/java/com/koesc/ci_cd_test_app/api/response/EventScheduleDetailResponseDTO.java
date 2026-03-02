package com.koesc.ci_cd_test_app.api.response;

import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import com.koesc.ci_cd_test_app.domain.eventschedule.BookableStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 상세 조회는 "bookable 같은 계산값"도 포함해야 하니 목록 DTO와 다름.
 */

@Schema(description = "공연 상세 조회 정보 응답")
public record EventScheduleDetailResponseDTO(

        @Schema(description = "공연 회차 고유 ID")
        Long scheduleId,

        @Schema(description = "공연 ID")
        Long eventId,

        @Schema(description = "공연 시작 일시")
        LocalDateTime startTime,

        @Schema(description = "총 좌석 수")
        Integer totalSeats,

        @Schema(description = "예매 가능 여부 (서버 계산 값)")
        Boolean bookable,

        @Schema(description = "예매 가능 판정 코드 (프론트 분기/로그/알림용). 예: BOOKABLE, ALREADY_STARTED, NOT_OPEN_YET, EVENT_CLOSED")
        String bookableCode,

        @Schema(description = "예매 가능/불가 사유를 사용자에게 설명하는 메시지 (서버 계산 값). 예매 가능/불가 사유를 사용자에게 설명하는 메시지 (서버 계산 값).")
        String bookableMessage,

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
    public static EventScheduleDetailResponseDTO from(EventSchedule eventSchedule, BookableStatus status) {
        return new EventScheduleDetailResponseDTO(
                eventSchedule.getScheduleId(),
                eventSchedule.getEventId(),
                eventSchedule.getStartTime(),
                eventSchedule.getTotalSeats(),
                status.bookable(),
                status.code().name(),
                status.message(),
                eventSchedule.getCreatedAt()
        );
    }
}
