package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.Reservation;
import com.koesc.ci_cd_test_app.domain.ReservationStatus;
import com.koesc.ci_cd_test_app.implement.client.ConcertSeatInternalClient;
import com.koesc.ci_cd_test_app.implement.client.WaitingRoomInternalClient;
import com.koesc.ci_cd_test_app.implement.reader.ReservationReader;
import com.koesc.ci_cd_test_app.implement.validator.ReservationValidator;
import com.koesc.ci_cd_test_app.implement.writer.ReservationWriter;
import com.koesc.ci_cd_test_app.storage.entity.ReservationEntity;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationManager 단위 테스트")
public class ReservationManagerTest {

    @Mock
    private ReservationReader reservationReader;

    @Mock
    private ReservationWriter reservationWriter;

    @Mock
    private ReservationValidator reservationValidator;

    @Mock
    private WaitingRoomInternalClient waitingRoomInternalClient;

    @Mock
    private ConcertSeatInternalClient concertSeatInternalClient;

    @InjectMocks
    private ReservationManager reservationManager;

    @Test
    @DisplayName("예약 생성 성공: 토큰 검증 -> 좌석 HOLD -> 예약 저장 -> 토큰 consume")
    void createReservation_Success() {

        // 1. given
        Long userId = 1L;
        String waitingToken = "token-123";
        Long seatId = 10L;
        Long eventId = 100L;
        LocalDateTime expiredAt = LocalDateTime.now().plusMinutes(5);

        ConcertSeatInternalClient.ConcertSeatDetail seatDetail =
                new ConcertSeatInternalClient.ConcertSeatDetail(
                        seatId,
                        11L,
                        eventId,
                        1,
                        BigDecimal.valueOf(10000),
                        "AVAILABLE",
                        1L
                );

        WaitingRoomInternalClient.WaitingTokenValidationResult validationResult =
                new WaitingRoomInternalClient.WaitingTokenValidationResult(
                        true,
                        waitingToken,
                        "ACTIVE",
                        expiredAt
                );

        Reservation savedReservation = Reservation.builder()
                .reservationId(999L)
                .userId(userId)
                .seatId(seatId)
                .status(ReservationStatus.PENDING)
                .expiredAt(expiredAt)
                .build();

        given(concertSeatInternalClient.readSeat(seatId)).willReturn(seatDetail);
        given(waitingRoomInternalClient.validateToken(eq(waitingToken), eq(userId), anyLong()))
                .willReturn(validationResult);
        given(reservationValidator.calculateExpiredAt(anyLong())).willReturn(expiredAt);
        given(reservationWriter.saveWithFlush(any(Reservation.class))).willReturn(savedReservation);

        Reservation result = reservationManager.createReservation(userId, waitingToken, seatId);

        // 2. when & then
        assertThat(result).isEqualTo(savedReservation);

        verify(reservationValidator).validateCreateRequest(userId, seatId);
        verify(concertSeatInternalClient).readSeat(seatId);
        verify(waitingRoomInternalClient).validateToken(waitingToken, userId, eventId);
        verify(concertSeatInternalClient).holdSeat(seatId);
        verify(reservationWriter).saveWithFlush(any(Reservation.class));
        verify(waitingRoomInternalClient).consumeToken(waitingToken, "booking-service");
        verify(concertSeatInternalClient, never()).releaseSeat(anyLong());
    }

    @Test
    @DisplayName("예약 생성 실패: 유효하지 않은 토큰이면 좌석 HOLD를 호출하지 않는다")
    void createReservation_InvalidToken_DoesNotHoldSeat() {

        // 1. given
        Long userId = 1L;
        String waitingToken = "bad-token";
        Long seatId = 10L;
        Long eventId = 100L;

        ConcertSeatInternalClient.ConcertSeatDetail seatDetail =
                new ConcertSeatInternalClient.ConcertSeatDetail(
                        seatId,
                        11L,
                        eventId,
                        1,
                        BigDecimal.valueOf(10000),
                        "AVAILABLE",
                        1L
                );

        WaitingRoomInternalClient.WaitingTokenValidationResult invalidResult =
                new WaitingRoomInternalClient.WaitingTokenValidationResult(
                        false,
                        waitingToken,
                        "INVALID",
                        null
                );

        given(concertSeatInternalClient.readSeat(seatId)).willReturn(seatDetail);
        given(waitingRoomInternalClient.validateToken(eq(waitingToken), eq(userId), anyLong()))
                .willReturn(invalidResult);

        // 2. when & then
        assertThatThrownBy(() -> reservationManager.createReservation(userId, waitingToken, seatId))
                .isInstanceOf(IllegalStateException.class);

        verify(concertSeatInternalClient, never()).holdSeat(anyLong());
        verify(reservationWriter, never()).saveWithFlush(any(Reservation.class));
        verify(waitingRoomInternalClient, never()).consumeToken(anyString(), anyString());
    }

    @Test
    @DisplayName("예약 저장 실패: 좌석 HOLD 이후 saveWithFlush 예외가 나면 RELESE로 보상한다")
    void createReservation_SaveFailure_ReleasesSeat() {

        // 1. given
        Long userId = 1L;
        String waitingToken = "token-123";
        Long seatId = 10L;
        Long eventId = 100L;
        LocalDateTime expiredAt = LocalDateTime.now().plusMinutes(5);

        ConcertSeatInternalClient.ConcertSeatDetail seatDetail =
                new ConcertSeatInternalClient.ConcertSeatDetail(
                        seatId,
                        11L,
                        eventId,
                        1,
                        BigDecimal.valueOf(10000),
                        "AVAILABLE",
                        1L
                );

        WaitingRoomInternalClient.WaitingTokenValidationResult validationResult =
                new WaitingRoomInternalClient.WaitingTokenValidationResult(
                        true,
                        waitingToken,
                        "ACTIVE",
                        expiredAt
                );

        given(concertSeatInternalClient.readSeat(seatId)).willReturn(seatDetail);
        given(waitingRoomInternalClient.validateToken(eq(waitingToken), eq(userId), anyLong()))
                .willReturn(validationResult);
        given(reservationValidator.calculateExpiredAt(anyLong())).willReturn(expiredAt);
        given(reservationWriter.saveWithFlush(any(Reservation.class)))
                .willThrow(new RuntimeException("db save failed"));

        // 2. when & then
        assertThatThrownBy(() -> reservationManager.createReservation(userId, waitingToken, seatId))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db save failed");

        verify(concertSeatInternalClient).holdSeat(seatId);
        verify(concertSeatInternalClient).releaseSeat(seatId);
        verify(waitingRoomInternalClient, never()).consumeToken(anyString(), anyString());
    }

    @Test
    @DisplayName("예약 확정 성공: confirmSeat는 정확히 1번만 호출된다")
    void createReservation_ConfirmSeat_OnlyOne() {

        // 1. given
        Long reservationId = 1L;
        Long paymentId = 1000L;
        Long seatId = 10L;
        LocalDateTime expiredAt = LocalDateTime.now().plusMinutes(5);

        Reservation reservation = Reservation.builder()
                .reservationId(reservationId)
                .userId(1L)
                .seatId(seatId)
                .status(ReservationStatus.PENDING)
                .expiredAt(expiredAt)
                .build();

        Reservation confirmedReservation = reservation.confirm();
        ReservationEntity entity = mock(ReservationEntity.class);

        given(reservationReader.read(reservationId)).willReturn(reservation);
        given(reservationReader.readEntity(reservationId)).willReturn(entity);
        given(reservationWriter.updateWithFlush(any(Reservation.class), eq(entity)))
                .willReturn(confirmedReservation);

        Reservation result = reservationManager.confirmReservation(reservationId, paymentId);

        // 2. when & then
        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        verify(reservationValidator).validateConfirmable(reservation);
        verify(concertSeatInternalClient, times(1)).confirmSeat(seatId);
        verify(reservationWriter).updateWithFlush(any(Reservation.class), eq(entity));
    }
}
