package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.api.request.ReservationCreateRequestDTO;
import com.koesc.ci_cd_test_app.api.response.ReservationResponseDTO;
import com.koesc.ci_cd_test_app.api.response.ReservationStatusResponseDTO;
import com.koesc.ci_cd_test_app.api.response.ReservationSummaryResponseDTO;
import com.koesc.ci_cd_test_app.business.ReservationService;
import com.koesc.ci_cd_test_app.domain.Reservation;
import com.koesc.ci_cd_test_app.domain.ReservationStatus;
import com.koesc.ci_cd_test_app.global.api.pagination.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 외부(프론트/포스트맨/브라우저)가 실제로 호출하는 외부 API Controller
 *
 * 현재는 인증 연동 전 단계이므로 X-User-Id 헤더를 임시로 사용한다.
 * 나중에 Spring Security/JWT 붙이면 @AuthenticationPrincipal 등으로 교체하면 된다.
 */
@Tag(name = "Reservation API", description = "예약 생성/조회/취소 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    /**
     * 예약 생성
     * - 현재는 JWT 미연동 상태를 가정하여 X-User-Id 헤더를 임시 적용
     * - 대기열 통과 토큰은 X-Waiting-Token 헤더로 전달
     */
    @Operation(
            summary = "예약 생성",
            description = "대기열 통과 토큰(X-Waiting-Token)을 사용해 좌석 예약을 생성합니다."
    )
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponseDTO> createReservation(
            @RequestHeader("X-User-Id") Long userId,
            @RequestHeader("X-Waiting-Token") String waitingToken,
            @Valid @RequestBody ReservationCreateRequestDTO request
    ) {
        Reservation reservation = reservationService.createReservation(userId, waitingToken, request.seatId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReservationResponseDTO.from(reservation));
    }

    /**
     * 예약 상세 조회
     * - 본인 예약인지 Service/Validator에서 검증
     */
    @Operation(
            summary = "예약 상세 조회",
            description = "내 예약 1건을 상세 조회합니다."
    )
    @GetMapping("/reservations/{reservationId}")
    public ResponseEntity<ReservationResponseDTO> getReservation(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long reservationId
    ) {
        Reservation reservation = reservationService.getReservation(reservationId, userId);
        return ResponseEntity.ok(ReservationResponseDTO.from(reservation));
    }

    /**
     * 내 예약 목록 조회
     * - status는 선택값(null이면 전체)
     */
    @Operation(
            summary = "내 예약 목록 조회",
            description = "내 예약 목록을 상태별/페이지별로 조회합니다."
    )
    @GetMapping("/users/me/reservations")
    public ResponseEntity<PageResponse<ReservationSummaryResponseDTO>> getMyReservations(
            @RequestHeader("X-User-Id") Long userId,
            @RequestParam(required = false) ReservationStatus status,
            @PageableDefault(
                    size = 20,
                    sort = {"reservedAt", "reservationId"},
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        Page<Reservation> page = reservationService.getMyReservations(userId, status, pageable);
        return ResponseEntity.ok(PageResponse.from(page, ReservationSummaryResponseDTO::from));
    }

    /**
     * 예약 취소
     * - PENDING 상태의 본인 예약만 취소 가능
     */
    @Operation(
            summary = "예약 취소",
            description = "PENDING 상태의 내 예약을 취소하고, 점유 좌석을 해제합니다."
    )
    @DeleteMapping("/reservations/{reservationId}")
    public ResponseEntity<ReservationStatusResponseDTO> cancelReservation(
            @RequestHeader("X-User-Id") Long userId,
            @PathVariable Long reservationId
    ) {
        Reservation reservation = reservationService.cancelReservation(reservationId, userId);
        return ResponseEntity.ok(ReservationStatusResponseDTO.from(reservation));
    }
}
