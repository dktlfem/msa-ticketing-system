package com.koesc.ci_cd_test_app.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koesc.ci_cd_test_app.business.ReservationService;
import com.koesc.ci_cd_test_app.domain.Reservation;
import com.koesc.ci_cd_test_app.domain.ReservationStatus;
import com.koesc.ci_cd_test_app.global.error.GlobalExceptionHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ReservationController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@DisplayName("ReservationController 단위 테스트")
public class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReservationService reservationService;

    @Test
    @DisplayName("예약 생성 성공 시 201 Created와 예약 정보를 반환한다")
    void createReservation_success() throws Exception {

        // 1. given
        Long userId = 1L;
        String waitingToken = "token-123";
        Long seatId = 10L;

        Reservation reservation = Reservation.builder()
                .reservationId(999L)
                .userId(userId)
                .seatId(seatId)
                .status(ReservationStatus.PENDING)
                .reservedAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().plusMinutes(5))
                .build();

        given(reservationService.createReservation(userId, waitingToken, seatId))
                .willReturn(reservation);

        mockMvc.perform(post("/api/v1/reservations")
                        .header("X-User-Id", userId)
                        .header("X-Waiting-Token", waitingToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "seatId": 10
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reservationId").value(999L))
                .andExpect(jsonPath("$.userId").value(1L))
                .andExpect(jsonPath("$.seatId").value(10L))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("예약 생성 시 seatId가 0 이하이면 400 Bad Request를 반환한다")
    void createReservation_ValidationFail() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .header("X-User-Id", 1L)
                        .header("X-Waiting-Token", "token-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "seatId": 0
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C001"));
    }

    @Test
    @DisplayName("예약 취소 성공 시 200 OK와 CANCELLED 상태를 반환한다")
    void cancelReservation_Success() throws Exception {
        Long userId = 1L;
        Long reservationId = 999L;

        Reservation cancelledReservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(userId)
                .seatId(10L)
                .status(ReservationStatus.CANCELLED)
                .reservedAt(LocalDateTime.now().minusMinutes(1))
                .expiredAt(LocalDateTime.now().plusMinutes(4))
                .build();

        given(reservationService.cancelReservation(reservationId, userId))
                .willReturn(cancelledReservation);

        mockMvc.perform(delete("/api/v1/reservations/{reservationId}", reservationId)
                        .header("X-User-Id", userId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(999L))
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(reservationService).cancelReservation(reservationId, userId);
    }
}
