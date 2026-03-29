package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.Reservation;
import com.koesc.ci_cd_test_app.domain.ReservationStatus;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.implement.client.ConcertSeatInternalClient;
import com.koesc.ci_cd_test_app.implement.client.WaitingRoomInternalClient;
import com.koesc.ci_cd_test_app.implement.reader.ReservationReader;
import com.koesc.ci_cd_test_app.implement.validator.ReservationValidator;
import com.koesc.ci_cd_test_app.implement.writer.ReservationWriter;
import com.koesc.ci_cd_test_app.storage.entity.ReservationEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

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

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock mockLock;

    @InjectMocks
    private ReservationManager reservationManager;

    /**
     * 분산락 관련 공통 Mock 설정.
     *
     * lenient() 사용 이유:
     * Mockito strict stubbing 정책상, @BeforeEach에서 등록한 stub이 특정 테스트에서
     * 사용되지 않으면 UnnecessaryStubbingException이 발생한다.
     * confirmReservation 테스트처럼 createReservation을 호출하지 않는 케이스나,
     * tryLock을 오버라이드하는 락 실패 테스트에서 isHeldByCurrentThread가 미사용되는
     * 경우가 있으므로, 공통 락 설정은 lenient()로 관리한다.
     */
    @BeforeEach
    void setUpLockMock() throws InterruptedException {
        lenient().when(redissonClient.getLock(anyString())).thenReturn(mockLock);
        lenient().when(mockLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        lenient().when(mockLock.isHeldByCurrentThread()).thenReturn(true);
    }

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

    // =========================================================================
    // 분산락(Redisson) 관련 테스트
    // =========================================================================

    @Test
    @DisplayName("분산락 획득 실패: 다른 요청이 락을 보유 중이면 RESERVATION_LOCK_CONFLICT(429)를 던진다")
    void createReservation_LockNotAcquired_ThrowsLockConflict() throws InterruptedException {
        // given: tryLock이 false를 반환 → 락 획득 실패 시뮬레이션
        given(mockLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class))).willReturn(false);

        Long userId = 1L;
        String waitingToken = "token-999";
        Long seatId = 10L;

        // when & then
        assertThatThrownBy(() -> reservationManager.createReservation(userId, waitingToken, seatId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.RESERVATION_LOCK_CONFLICT));

        // 락 획득 실패이므로 하위 로직 전혀 호출 안 됨
        verify(concertSeatInternalClient, never()).readSeat(anyLong());
        verify(concertSeatInternalClient, never()).holdSeat(anyLong());
        verify(reservationWriter, never()).saveWithFlush(any(Reservation.class));
    }

    @Test
    @DisplayName("분산락 해제 보장: 예약 저장 실패 시에도 finally에서 락이 반드시 해제된다")
    void createReservation_SaveFailure_LockIsAlwaysReleased() throws InterruptedException {
        // given
        Long userId = 1L;
        String waitingToken = "token-123";
        Long seatId = 10L;
        Long eventId = 100L;
        LocalDateTime expiredAt = LocalDateTime.now().plusMinutes(5);

        ConcertSeatInternalClient.ConcertSeatDetail seatDetail =
                new ConcertSeatInternalClient.ConcertSeatDetail(
                        seatId, 11L, eventId, 1, BigDecimal.valueOf(10000), "AVAILABLE", 1L
                );
        WaitingRoomInternalClient.WaitingTokenValidationResult validationResult =
                new WaitingRoomInternalClient.WaitingTokenValidationResult(
                        true, waitingToken, "ACTIVE", expiredAt
                );

        given(concertSeatInternalClient.readSeat(seatId)).willReturn(seatDetail);
        given(waitingRoomInternalClient.validateToken(eq(waitingToken), eq(userId), anyLong()))
                .willReturn(validationResult);
        given(reservationValidator.calculateExpiredAt(anyLong())).willReturn(expiredAt);
        given(reservationWriter.saveWithFlush(any(Reservation.class)))
                .willThrow(new RuntimeException("DB 장애 시뮬레이션"));

        // when
        assertThatThrownBy(() -> reservationManager.createReservation(userId, waitingToken, seatId))
                .isInstanceOf(RuntimeException.class);

        // then: 예외 발생 여부와 무관하게 락 해제가 호출되어야 함
        verify(mockLock).unlock();
        // 좌석 보상 로직도 동작해야 함
        verify(concertSeatInternalClient).releaseSeat(seatId);
    }

    @Test
    @DisplayName("분산락 동시성: 동일 좌석에 동시 요청 시 오직 1건만 성공한다")
    void createReservation_ConcurrentRequests_OnlyOneSucceeds() throws InterruptedException {
        // given: 실제 경합 시나리오 — 첫 번째 스레드만 락 획득 성공
        Long seatId = 10L;
        Long eventId = 100L;
        LocalDateTime expiredAt = LocalDateTime.now().plusMinutes(5);

        ConcertSeatInternalClient.ConcertSeatDetail seatDetail =
                new ConcertSeatInternalClient.ConcertSeatDetail(
                        seatId, 11L, eventId, 1, BigDecimal.valueOf(10000), "AVAILABLE", 1L
                );
        WaitingRoomInternalClient.WaitingTokenValidationResult validationResult =
                new WaitingRoomInternalClient.WaitingTokenValidationResult(
                        true, "token", "ACTIVE", expiredAt
                );
        Reservation savedReservation = Reservation.builder()
                .reservationId(1L).userId(1L).seatId(seatId)
                .status(ReservationStatus.PENDING).expiredAt(expiredAt).build();

        // 첫 번째 호출만 락 획득 성공, 이후 호출은 실패
        given(mockLock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(true)   // 1번 스레드: 성공
                .willReturn(false); // 2번 스레드: 실패

        given(concertSeatInternalClient.readSeat(seatId)).willReturn(seatDetail);
        given(waitingRoomInternalClient.validateToken(anyString(), anyLong(), anyLong()))
                .willReturn(validationResult);
        given(reservationValidator.calculateExpiredAt(anyLong())).willReturn(expiredAt);
        given(reservationWriter.saveWithFlush(any(Reservation.class))).willReturn(savedReservation);

        int threadCount = 2;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final long userId = i + 1L;
            executor.submit(() -> {
                try {
                    reservationManager.createReservation(userId, "token", seatId);
                    successCount.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.RESERVATION_LOCK_CONFLICT) {
                        failCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // then: 성공 1건, 실패(락 충돌) 1건
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);
    }
}
