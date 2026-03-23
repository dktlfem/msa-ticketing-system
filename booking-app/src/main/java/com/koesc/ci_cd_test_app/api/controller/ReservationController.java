package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.api.request.ReservationCreateRequestDTO;
import com.koesc.ci_cd_test_app.api.response.ReservationResponseDTO;
import com.koesc.ci_cd_test_app.api.response.ReservationStatusResponseDTO;
import com.koesc.ci_cd_test_app.api.response.ReservationSummaryResponseDTO;
import com.koesc.ci_cd_test_app.business.ReservationService;
import com.koesc.ci_cd_test_app.domain.Reservation;
import com.koesc.ci_cd_test_app.domain.ReservationStatus;
import com.koesc.ci_cd_test_app.global.api.pagination.PageResponse;
import com.koesc.ci_cd_test_app.global.gateway.GatewayHeaders;
import com.koesc.ci_cd_test_app.global.gateway.PassportCodec;
import com.koesc.ci_cd_test_app.global.gateway.UserPassport;
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
 * Auth-Passport 헤더에서 UserPassport를 역직렬화하여 userId를 추출한다. (ADR-0007 Phase 2)
 * Queue-Token 헤더로 대기열 통과 토큰을 전달받는다. (ADR-0007: X-Waiting-Token → Queue-Token)
 */
@Tag(name = "Reservation API", description = "예약 생성/조회/취소 API")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    @Operation(
            summary = "예약 생성",
            description = "대기열 통과 토큰(Queue-Token)을 사용해 좌석 예약을 생성합니다."
    )
    @PostMapping("/reservations")
    public ResponseEntity<ReservationResponseDTO> createReservation(
            @RequestHeader(GatewayHeaders.AUTH_PASSPORT) String passportHeader,
            @RequestHeader(GatewayHeaders.QUEUE_TOKEN) String waitingToken,
            @Valid @RequestBody ReservationCreateRequestDTO request
    ) {
        Long userId = extractUserId(passportHeader);
        Reservation reservation = reservationService.createReservation(userId, waitingToken, request.seatId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ReservationResponseDTO.from(reservation));
    }

    @Operation(
            summary = "예약 상세 조회",
            description = "내 예약 1건을 상세 조회합니다."
    )
    @GetMapping("/reservations/{reservationId}")
    public ResponseEntity<ReservationResponseDTO> getReservation(
            @RequestHeader(GatewayHeaders.AUTH_PASSPORT) String passportHeader,
            @PathVariable Long reservationId
    ) {
        Long userId = extractUserId(passportHeader);
        Reservation reservation = reservationService.getReservation(reservationId, userId);
        return ResponseEntity.ok(ReservationResponseDTO.from(reservation));
    }

    @Operation(
            summary = "내 예약 목록 조회",
            description = "내 예약 목록을 상태별/페이지별로 조회합니다."
    )
    @GetMapping("/users/me/reservations")
    public ResponseEntity<PageResponse<ReservationSummaryResponseDTO>> getMyReservations(
            @RequestHeader(GatewayHeaders.AUTH_PASSPORT) String passportHeader,
            @RequestParam(required = false) ReservationStatus status,
            @PageableDefault(
                    size = 20,
                    sort = {"reservedAt", "reservationId"},
                    direction = Sort.Direction.DESC
            ) Pageable pageable
    ) {
        Long userId = extractUserId(passportHeader);
        Page<Reservation> page = reservationService.getMyReservations(userId, status, pageable);
        return ResponseEntity.ok(PageResponse.from(page, ReservationSummaryResponseDTO::from));
    }

    @Operation(
            summary = "예약 취소",
            description = "PENDING 상태의 내 예약을 취소하고, 점유 좌석을 해제합니다."
    )
    @DeleteMapping("/reservations/{reservationId}")
    public ResponseEntity<ReservationStatusResponseDTO> cancelReservation(
            @RequestHeader(GatewayHeaders.AUTH_PASSPORT) String passportHeader,
            @PathVariable Long reservationId
    ) {
        Long userId = extractUserId(passportHeader);
        Reservation reservation = reservationService.cancelReservation(reservationId, userId);
        return ResponseEntity.ok(ReservationStatusResponseDTO.from(reservation));
    }

    private static Long extractUserId(String passportHeader) {
        UserPassport passport = PassportCodec.decode(passportHeader);
        return Long.parseLong(passport.userId());
    }
}
