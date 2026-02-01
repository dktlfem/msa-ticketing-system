package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.api.request.WaitingRoomRequestDTO;
import com.koesc.ci_cd_test_app.api.response.WaitingRoomResponseDTO;
import com.koesc.ci_cd_test_app.business.WaitingRoomService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Tag(name = "WaitingRoom API", description = "대기열 및 토큰 발급 관리 API")
@RestController
@RequestMapping("/api/v1/waiting-room")
@RequiredArgsConstructor
public class WaitingRoomController {

    private final WaitingRoomService waitingRoomService;

    @Operation(
            summary = "대기열 진입 (Join)",
            description = "사용자를 Redis 대기열(Sorted Set)에 등록한다. 이미 등록된 경우 현재 순번을 반환한다."
    )
    @PostMapping("/join")
    public Mono<ResponseEntity<WaitingRoomResponseDTO>> joinWaitingRoom(@RequestBody @Valid WaitingRoomRequestDTO request) {

        // 유저 요청 -> Validator 검증 -> Redis 등록 -> 현재 순번 응답
        // Long rank = waitingRoomService.joinQueue(request.eventId(), request.userId());
        // 진입 직후는 0초 또는 계산값
        // return ResponseEntity.ok(WaitingRoomResponseDTO.waiting(rank, rank * 0));

        // [Refactor] Mono<Long>을 받아 map으로 응답 객체 생성
        return waitingRoomService.joinQueue(request.eventId(), request.userId())
                .map(rank -> ResponseEntity.ok(WaitingRoomResponseDTO.waiting(rank, 0L)));
    }

    @Operation(
            summary = "대기열 상태 조회 및 토큰 발급 (Polling)",
            description = "내 대기 순번을 조회합니다. 순번이 0이 되면 Active Token을 발급받아 반환합니다."
    )
    @GetMapping("/status")
    public Mono<ResponseEntity<WaitingRoomResponseDTO>> checkStatus(
            @RequestParam Long eventId,
            @RequestParam Long userId
    ) {
        // 폴링 요청 -> Redis 순위 확인 -> (통과 시) DB 토큰 발급 -> 응답
        // WaitingRoomResponseDTO response = waitingRoomService.getQueueStatus(eventId, userId);
        // return ResponseEntity.ok(response);

        // [Refactor] Mono<WaitingRoomResponseDTO>를 받아 map으로 ResponseEntity 래핑
        return waitingRoomService.getQueueStatus(eventId, userId)
                .map(ResponseEntity::ok);
    }
}
