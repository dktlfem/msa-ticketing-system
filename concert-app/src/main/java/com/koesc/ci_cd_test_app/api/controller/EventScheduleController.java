package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.api.response.EventScheduleDetailResponseDTO;
import com.koesc.ci_cd_test_app.api.response.EventScheduleResponseDTO;
import com.koesc.ci_cd_test_app.business.EventScheduleService;
import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import com.koesc.ci_cd_test_app.domain.eventschedule.EventScheduleDetail;
import com.koesc.ci_cd_test_app.global.api.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/v1/events/{eventId}/schedules : 리스트 응답 형태
 * GET /api/v1/schedules/{scheduleId} : 상세 응답 형태 (+ bookable 계산값)
 */

@Tag(name = "EventSchedule API", description = "공연 회차(Seat) 조회 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EventScheduleController {

    private final EventScheduleService eventScheduleService;

    /**
     * 유즈케이스 A) 특정 공연(eventId)의 회차 리스트 조회
     *
     * Page : 보통 count 쿼리가 같이 나가서 전체 건수를 알 수 있음
     * Slice : count 쿼리 없이 다음 페이지가 있는지만 알려줘서 read-heavy에 더 유리
     */
    @Operation(summary = "공연 회차 목록 조회", description = "특정 공연(eventId)의 모든 회차를 시작 시간 오름차순으로 조회합니다.")
    @GetMapping("/events/{eventId}/schedules")
    public ResponseEntity<PageResponse<EventScheduleResponseDTO>> getEventSchedules(
            @PathVariable Long eventId,
            // 정렬 안정성까지 최소로 챙기려면 scheduleId tie-breaker도 같이 두는 게 좋음
            @PageableDefault(size = 20, sort = {"startTime", "scheduleId"}, direction = Sort.Direction.ASC) Pageable pageable
    ) {
        Page<EventSchedule> page = eventScheduleService.getEventSchedules(eventId, pageable);

        // 외부 계약 : PageResponse 고정 + DTO 변환은 Presentation 계층에서
        return ResponseEntity.ok(PageResponse.from(page, EventScheduleResponseDTO::from));
    }

    /**
     * 유즈케이스 B) 특정 회차(scheduleId) 상세 조회 + 예매 가능 상태 계산
     */
    @Operation(summary = "회차 상세 조회", description = "특정 회차(scheduleId)의 상세 정보와 예매 가능 상태를 함께 반환합니다.")
    @GetMapping("/schedules/{scheduleId}")
    public ResponseEntity<EventScheduleDetailResponseDTO> getEventScheduleDetail(@PathVariable Long scheduleId) {
        EventScheduleDetail detail = eventScheduleService.getEventScheduleDetail(scheduleId);
        return ResponseEntity.ok(EventScheduleDetailResponseDTO.from(detail.schedule(), detail.bookableStatus()));
    }
}
