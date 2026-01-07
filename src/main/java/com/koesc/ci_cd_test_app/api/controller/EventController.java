package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.api.request.EventRequestDTO;
import com.koesc.ci_cd_test_app.api.response.EventResponseDTO;
import com.koesc.ci_cd_test_app.business.EventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Event API", description = "공연(Event) 정보 관리 API")
@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @Operation(summary = "공연 신규 등록", description = "공연 제목, 설명, 포스터 정보를 입력하여 새로운 공연을 등록합니다.")
    @PostMapping()
    public ResponseEntity<EventResponseDTO> createEvent(@RequestBody @Valid EventRequestDTO request) {
        return ResponseEntity.ok(eventService.createEvent(request));
    }

    @Operation(summary = "공연 상세 정보 조회", description = "공연 ID를 통해 상세 정보를 조회합니다. (2차 캐싱 적용)")
    @GetMapping("/{eventId}")
    public ResponseEntity<EventResponseDTO> getEvent(@PathVariable Long eventId) {
        return ResponseEntity.ok(eventService.getEventDetails(eventId));
    }

    @Operation(summary = "전체 공연 목록 조회", description = "등록된 모든 공연의 목록을 조회합니다.")
    @GetMapping
    public ResponseEntity<List<EventResponseDTO>> getEvents() {
        return ResponseEntity.ok(eventService.getAllEvents());
    }
}
