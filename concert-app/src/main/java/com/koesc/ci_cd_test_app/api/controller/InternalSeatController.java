package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.business.SeatInternalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;

/**
 * 1. 응답 형태(성공)
 * - GET /internal/v1/seats/{seatId} -> SeatDetailResponse
 * - POSt /internal/v1/seats/{seatId}/hold|release|confirm -> SeatCommandResponse
 *
 * 2. 실패 시
 * - 404 좌석 없음
 * - 400 expectedStatus 값이 잘못됨
 * - 409 상태 불일치(이미 HOLD/SOLD 등) 또는 낙관적 락 충돌
 */
@Tag(name = "Internal Seat API", description = "서비스 간 좌석 재고 제어(hold/release/confirm) 내부 API")
@RestController
@RequestMapping("/internal/v1/seats")
@RequiredArgsConstructor
public class InternalSeatController {

    private final SeatInternalService seatInternalService;

    // seat 테이블에 event_id가 없으므로 schedule_id -> event_id를 한 번 더 조회해서 내려줌
    @Operation(summary = "좌석 상세 조회(내부)", description = "booking-app이 seatId로 좌석 상세(+eventId) 정보를 조회합니다.")
    @GetMapping("/{seatId}")
    public ResponseEntity<SeatDetailResponse> readSeat(@PathVariable Long seatId) {
        try {
            SeatInternalService.SeatDetailResult result = seatInternalService.readSeat(seatId);

            return ResponseEntity.ok(new SeatDetailResponse(
                    result.seatId(),
                    result.scheduleId(),
                    result.eventId(),
                    result.seatNo(),
                    result.price(),
                    result.status(),
                    result.version()
            ));
        } catch (RuntimeException e) {
            throw mapSeatException(e, seatId);
        }
    }

    @Operation(summary = "좌석 HOLD(내부)", description = "AVAILABLE -> HOLD")
    @PostMapping("/{seatId}/hold")
    public ResponseEntity<SeatCommandResponse> holdSeat(
            @PathVariable Long seatId,
            @Valid @RequestBody SeatCommandRequest request
    ) {
        try {
            SeatInternalService.SeatCommandResult result =
                    seatInternalService.hold(seatId, request.expectedStatus());

            return ResponseEntity.ok(new SeatCommandResponse(
                    result.seatId(),
                    result.scheduleId(),
                    result.status(),
                    result.version()
            ));
        } catch (RuntimeException e) {
            throw mapSeatException(e, seatId);
        }
    }

    @Operation(summary = "좌석 RELEASE(내부)", description = "HOLD -> AVAILABLE")
    @PostMapping("/{seatId}/release")
    public ResponseEntity<SeatCommandResponse> releaseSeat(
            @PathVariable Long seatId,
            @Valid @RequestBody SeatCommandRequest request
    ) {
        try {
            SeatInternalService.SeatCommandResult result =
                    seatInternalService.release(seatId, request.expectedStatus());

            return ResponseEntity.ok(new SeatCommandResponse(
                    result.seatId(),
                    result.scheduleId(),
                    result.status(),
                    result.version()
            ));
        } catch (RuntimeException e) {
            throw mapSeatException(e, seatId);
        }
    }

    @Operation(summary = "좌석 CONFIRM(내부)", description = "HOLD -> SOLD")
    @PostMapping("/{seatId}/confirm")
    public ResponseEntity<SeatCommandResponse> confirmSeat(
            @PathVariable Long seatId,
            @Valid @RequestBody SeatCommandRequest request
    ) {
        try {
            SeatInternalService.SeatCommandResult result =
                    seatInternalService.confirm(seatId, request.expectedStatus());

            return ResponseEntity.ok(new SeatCommandResponse(
                    result.seatId(),
                    result.scheduleId(),
                    result.status(),
                    result.version()
            ));
        } catch (RuntimeException e) {
            throw mapSeatException(e, seatId);
        }
    }

    private ResponseStatusException mapSeatException(RuntimeException e, Long seatId) {
        if (e instanceof EntityNotFoundException) {
            return new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }

        if (e instanceof IllegalArgumentException) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }

        if (e instanceof IllegalStateException) {
            String code = e.getMessage();

            if ("SEAT_CONCURRENT_CONFLICT".equals(code)) {
                return new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "SEAT_CONCURRENT_CONFLICT: 동시에 좌석 상태 변경이 발생했습니다. seatId=" + seatId,
                        e
                );
            }

            return new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    code + ": seatId=" + seatId,
                    e
            );
        }

        return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
    }

    /**
     * Request/Response DTO
     */
    public record SeatCommandRequest(
            @NotBlank String expectedStatus
    ) {
    }

    public record SeatDetailResponse(
            Long seatId,
            Long scheduleId,
            Long eventId,
            Integer seatNo,
            BigDecimal price,
            String status,
            Long version
    ) {
    }

    public record SeatCommandResponse(
            Long seatId,
            Long scheduleId,
            String status,
            Long version
    ) {
    }
}
