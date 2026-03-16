package com.koesc.ci_cd_test_app.implement.client;

public interface BookingInternalClient {

    ReservationDetail readReservation(Long reservationId);

    void confirmReservation(Long reservationId, Long paymentId);

    record ReservationDetail(
            Long reservationId,
            Long userId,
            Long seatId,
            String status,
            String expiredAt
    ) {}
}
