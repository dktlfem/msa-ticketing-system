package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.Reservation;
import com.koesc.ci_cd_test_app.domain.ReservationStatus;
import com.koesc.ci_cd_test_app.implement.client.ConcertSeatInternalClient;
import com.koesc.ci_cd_test_app.implement.client.WaitingRoomInternalClient;
import com.koesc.ci_cd_test_app.implement.reader.ReservationReader;
import com.koesc.ci_cd_test_app.implement.validator.ReservationValidator;
import com.koesc.ci_cd_test_app.implement.writer.ReservationWriter;
import com.koesc.ci_cd_test_app.storage.entity.ReservationEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ReservationManager {

    private static final long DEFAULT_HOLD_MINUTES = 5L;
    private static final String BOOKING_SERVICE = "booking-service";

    private final ReservationReader reservationReader;
    private final ReservationWriter reservationWriter;
    private final ReservationValidator reservationValidator;

    private final WaitingRoomInternalClient waitingRoomInternalClient;
    private final ConcertSeatInternalClient concertSeatInternalClient;

    /**
     * 유즈케이스 A) 예약 생성
     * - 좌석 HOLD, Waiting Token 검증은 상위 유즈 케이스(또는 다른 서비스 orchestration)에서 처리한다고 가정
     *
     * 흐름:
     * 1. seat 상세 조회 -> eventId 확보
     * 2. waitingroom 토큰 검증
     * 3. concert-app에 좌석 HOLD 요청
     * 4. booking DB에 reservation 저장 + flush
     * 5. waitingroom 토큰 consume
     * 6. 중간 실패 시 좌석 RELEASE 보상
     */
    public Reservation createReservation(Long userId, String waitingToken, Long seatId) {
        reservationValidator.validateCreateRequest(userId, seatId);

        ConcertSeatInternalClient.ConcertSeatDetail seatDetail =
                concertSeatInternalClient.readSeat(seatId);

        if (seatDetail.eventId() == null) {
            throw new IllegalStateException("concert-app seat detail 응답에 eventId가 없습니다. seatId = " + seatId);
        }

        // 1. 대기열 토큰 검증
        WaitingRoomInternalClient.WaitingTokenValidationResult validationResult =
                waitingRoomInternalClient.validateToken(waitingToken, userId, seatDetail.eventId());

        if (validationResult == null || !Boolean.TRUE.equals(validationResult.valid())) {
            throw new IllegalStateException("유효하지 않은 대기열 토큰입니다. tokenId = " + waitingToken);
        }

        // 2. 좌석 임시 점유
        concertSeatInternalClient.holdSeat(seatId);

        try {
            Reservation reservation = Reservation.builder()
                    .userId(userId)
                    .seatId(seatId)
                    .status(ReservationStatus.PENDING)
                    .expiredAt(reservationValidator.calculateExpiredAt(DEFAULT_HOLD_MINUTES))
                    .build();

            Reservation saved = reservationWriter.saveWithFlush(reservation);

            // 3. 예약 저장 성공 후 토큰 사용 처리
            waitingRoomInternalClient.consumeToken(waitingToken, BOOKING_SERVICE);

            return saved;
        } catch (RuntimeException e) {
            try {
                // 예약 저장 실패 시 좌석 점유 복구
                concertSeatInternalClient.releaseSeat(seatId);
            } catch (RuntimeException compensationException) {
                e.addSuppressed(compensationException);
            }
            throw e;
        }
    }

    /**
     * 유즈케이스 B) 예약 단건 조회 (내부 공통)
     */
    public Reservation getReservation(Long reservationId) {
        return reservationReader.read(reservationId);
    }

    /**
     * 유즈케이스 C) 본인 예약 단건 조회
     */
    public Reservation getReservation(Long reservationId, Long userId) {
        Reservation reservation = reservationReader.read(reservationId);
        reservationValidator.validateOwner(reservation, userId);
        return reservation;
    }

    /**
     * 유즈케이스 D) 내 예약 목록 조회
     */
    public Page<Reservation> getReservationsByUserId(Long userId, Pageable pageable) {
        return reservationReader.readByUserId(userId, pageable);
    }

    /**
     * 유즈케이스 E) 내 예약 상태별 목록 조회
     */
    public Page<Reservation> getReservationsByUserIdAndStatus(Long userId, ReservationStatus status, Pageable pageable) {
        return reservationReader.readByUserIdAndStatus(userId, status, pageable);
    }

    /**
     * 유즈케이스 F) 예약 취소
     *
     * 흐름:
     * 1. 본인 예약인지 검증
     * 2. 취소 가능한 상태인지 검증 (PENDING + not expired)
     * 3. concert-app에 좌석 RELEASE 요청
     * 4. 예약 상태를 CANCELLED로 변경
     *
     * - 로컬 상태를 먼저 CANCELLED로 flush
     * - 그 다음 concert-app RELEASE 호출
     * - 외부 호출 실패 시 현재 트랜잭션이 롤백되므로 로컬 DB도 원복됨
     */
    public Reservation cancelReservation(Long reservationId, Long userId) {
        Reservation reservation = reservationReader.read(reservationId);

        reservationValidator.validateOwner(reservation, userId);
        reservationValidator.validateCancellable(reservation);

        Reservation cancelledReservation = reservation.cancel();
        ReservationEntity entity = reservationReader.readEntity(reservationId);

        Reservation saved = reservationWriter.updateWithFlush(cancelledReservation, entity);

        concertSeatInternalClient.releaseSeat(reservation.getSeatId());

        return saved;
    }

    /**
     * 유즈케이스 G) 예약 확정 (payment-app 결제 성공 후 내부 호출)
     *
     * 흐름:
     * 1. 예약 상태 검증 (PENDING + not expired)
     * 2. concert-app에 좌석 SOLD 확정 요청
     * 3. 예약 상태를 CONFIRMED로 변경
     *
     * - 로컬 상태를 먼저 CONFIRMED로 flush
     * - 그 다음 concert-app SOLD 확정 호출
     *
     * 현재 DDL에는 payment_id 저장 컬럼이 없어서 paymentId는 아직 저장하지 않음
     */
    public Reservation confirmReservation(Long reservationId, Long paymentId) {
        Reservation reservation = reservationReader.read(reservationId);

        reservationValidator.validateConfirmable(reservation);

        // 현재 DDL에는 paymentId 저장 컬럼이 없으므로
        // 지금은 예약 상태 변경과 좌석 확정까지만 처리
        // TODO: 후속 DDL 확장 시 confirmed_at, payment_id 연계 검토
        concertSeatInternalClient.confirmSeat(reservation.getSeatId());

        Reservation confirmedReservation = reservation.confirm();
        ReservationEntity entity = reservationReader.readEntity(reservationId);

        return reservationWriter.updateWithFlush(confirmedReservation, entity);
    }

    /**
     * 유즈케이스 H) 예약 만료 처리
     *
     * 흐름:
     * 1. 만료 가능한 상태인지 검증 (PENDING + expired)
     * 2. concert-app에 좌석 RELEASE 요청
     * 3. 예약 상태를 CANCELLED로 변경
     *
     * - 로컬 상태를 먼저 CANCELLED로 flush
     * - 그 다음 concert-app RELEASE 호출
     */
    public Reservation expireReservation(Long reservationId) {
        Reservation reservation = reservationReader.read(reservationId);

        reservationValidator.validateExpirable(reservation);

        // 추후에 EXPIRED 상태를 넣으면 반드시 expire()로 분리해야함.
        Reservation expiredReservation = reservation.cancel();
        ReservationEntity entity = reservationReader.readEntity(reservationId);

        Reservation saved = reservationWriter.updateWithFlush(expiredReservation, entity);

        concertSeatInternalClient.releaseSeat(reservation.getSeatId());

        return saved;
    }

    /**
     * 유즈케이스 I) 만료 대상 예약 목록 조회 (스케줄러용)
     */
    public List<Reservation> getExpiredPendingReservations(LocalDateTime now) {
        return reservationReader.readExpiredPendingReservations(now);
    }

}
