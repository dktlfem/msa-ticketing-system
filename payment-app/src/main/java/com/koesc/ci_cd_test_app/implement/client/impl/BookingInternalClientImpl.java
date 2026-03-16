package com.koesc.ci_cd_test_app.implement.client.impl;

import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.implement.client.BookingInternalClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Slf4j
@Component
public class BookingInternalClientImpl implements BookingInternalClient {

    private final RestClient restClient;

    public BookingInternalClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${internal.clients.booking.base-url:http://nginx_proxy}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public ReservationDetail readReservation(Long reservationId) {
        try {
            return restClient.get()
                    .uri("/internal/v1/reservations/{id}", reservationId)
                    .retrieve()
                    .body(ReservationDetail.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Reservation not found - reservationId={}", reservationId);
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND);
        } catch (Exception e) {
            log.error("Failed to read reservation - reservationId={}", reservationId, e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "예약 조회 실패: " + e.getMessage());
        }
    }

    @Override
    public void confirmReservation(Long reservationId, Long paymentId) {
        try {
            restClient.post()
                    .uri("/internal/v1/reservations/{id}/confirm", reservationId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("paymentId", paymentId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Reservation not found for confirm - reservationId={}", reservationId);
            throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND);
        } catch (HttpClientErrorException.Conflict e) {
            log.warn("Reservation not confirmable - reservationId={}", reservationId);
            throw new BusinessException(ErrorCode.RESERVATION_NOT_CONFIRMABLE);
        } catch (Exception e) {
            log.error("Failed to confirm reservation - reservationId={}, paymentId={}",
                    reservationId, paymentId, e);
            // 상위에서 보상 트랜잭션(PG 취소) 처리를 위해 예외를 그대로 전파
            throw new RuntimeException("예약 확정 호출 실패: " + e.getMessage(), e);
        }
    }
}
