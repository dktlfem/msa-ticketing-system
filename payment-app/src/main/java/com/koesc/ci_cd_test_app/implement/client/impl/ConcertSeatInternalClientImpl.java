package com.koesc.ci_cd_test_app.implement.client.impl;

import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.implement.client.ConcertSeatInternalClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class ConcertSeatInternalClientImpl implements ConcertSeatInternalClient {

    private final RestClient restClient;

    public ConcertSeatInternalClientImpl(
            RestClient.Builder restClientBuilder,
            @Value("${internal.clients.concert.base-url:http://nginx_proxy}") String baseUrl) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
    }

    @Override
    public SeatDetail readSeat(Long seatId) {
        try {
            return restClient.get()
                    .uri("/internal/v1/seats/{seatId}", seatId)
                    .retrieve()
                    .body(SeatDetail.class);
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("Seat not found - seatId={}", seatId);
            throw new BusinessException(ErrorCode.SEAT_NOT_FOUND);
        } catch (Exception e) {
            log.error("Failed to read seat - seatId={}", seatId, e);
            throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR, "좌석 조회 실패: " + e.getMessage());
        }
    }
}
