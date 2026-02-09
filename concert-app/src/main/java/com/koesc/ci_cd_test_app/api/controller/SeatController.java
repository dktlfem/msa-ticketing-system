    package com.koesc.ci_cd_test_app.api.controller;

    import com.koesc.ci_cd_test_app.api.request.SeatRequestDTO;
    import com.koesc.ci_cd_test_app.api.response.SeatResponseDTO;
    import com.koesc.ci_cd_test_app.business.SeatService;
    import io.swagger.v3.oas.annotations.Operation;
    import io.swagger.v3.oas.annotations.tags.Tag;
    import jakarta.validation.Valid;
    import lombok.RequiredArgsConstructor;
    import org.springframework.http.ResponseEntity;
    import org.springframework.web.bind.annotation.*;

    import java.util.List;

    @Tag(name = "Seat API", description = "좌석(Seat) 조회 및 점유 관리 API")
    @RestController
    @RequestMapping("/api/v1/seats")
    @RequiredArgsConstructor
    public class SeatController {

        private final SeatService seatService;

        @Operation(summary = "예약 가능 좌석 조회", description = "특정 스케줄의 예약 가능한(AVAILABLE) 좌석 목록을 조회합니다.")
        @GetMapping("/available/{scheduleId}")
        public ResponseEntity<List<SeatResponseDTO>> getAvailableSeats(@PathVariable Long scheduleId) {
            return ResponseEntity.ok(seatService.getAvailableSeats(scheduleId));
        }

        @Operation(summary = "좌석 임시 점유", description = "선택한 좌석을 5분간 임시 점유(HOLD) 상태로 변경합니다. (낙관적 락 적용)")
        @PostMapping("/hold")
        public ResponseEntity<SeatResponseDTO> holdSeat(@RequestBody @Valid SeatRequestDTO request) {

            // SeatService에서 낙관적 락을 통한 동시성 제어가 수행된다.
            return ResponseEntity.ok(seatService.holdSeat(request));
        }
    }
