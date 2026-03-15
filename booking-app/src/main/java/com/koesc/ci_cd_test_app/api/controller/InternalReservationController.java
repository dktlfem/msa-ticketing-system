package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.api.request.ReservationConfirmRequestDTO;
import com.koesc.ci_cd_test_app.api.response.ReservationResponseDTO;
import com.koesc.ci_cd_test_app.api.response.ReservationStatusResponseDTO;
import com.koesc.ci_cd_test_app.business.ReservationService;
import com.koesc.ci_cd_test_app.domain.Reservation;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 프론트(외부)가 호출하는 게 아닌, 내부 서비스(payment-app)가 호출하는 내부 API
 *
 * ex)
 * - payment-app -> 예약 확정
 * - 스케줄러/배치 -> 예약 만료 처리
 * - 내부 서비스 -> 예약 상세 조회
 */
@Tag(name = "Internal Reservation API", description = "서비스 간 예약 내부 API")
@RestController
@RequestMapping("/internal/v1/reservations")
@RequiredArgsConstructor
public class InternalReservationController {

    private final ReservationService reservationService;

    @Operation(
            summary = "예약 내부 상세 조회",
            description = "다른 서비스(payment-app 등)가 reservationId 기준으로 예약 정보를 조회합니다."
    )
    @GetMapping("/{reservationId}")
    public ResponseEntity<ReservationResponseDTO> getReservationInternal(
            @PathVariable Long reservationId
    ) {
        Reservation reservation = reservationService.getReservationInternal(reservationId);
        return ResponseEntity.ok(ReservationResponseDTO.from(reservation));
    }

    @Operation(
            summary = "예약 확정",
            description = "payment-app 결제 성공 후 예약 상태를 CONFIRMED로 변경합니다."
    )
    @PostMapping("/{reservationId}/confirm")
    public ResponseEntity<ReservationStatusResponseDTO> confirmReservation(
            @PathVariable Long reservationId,
            @Valid @RequestBody ReservationConfirmRequestDTO request
    ) {
        Reservation reservation = reservationService.confirmReservation(reservationId, request.paymentId());
        return ResponseEntity.ok(ReservationStatusResponseDTO.from(reservation));
    }

    @Operation(
            summary = "예약 만료 처리",
            description = "만료된 예약을 CANCELLED로 변경하고 좌석 점유를 해제합니다."
    )
    @PostMapping("/{reservationId}/expire")
    public ResponseEntity<ReservationStatusResponseDTO> expireReservation(
            @PathVariable Long reservationId
    ) {
        Reservation reservation = reservationService.expireReservation(reservationId);
        return ResponseEntity.ok(ReservationStatusResponseDTO.from(reservation));
    }
}
