package com.koesc.ci_cd_test_app.implement.client.impl;

import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.implement.client.ConcertSeatInternalClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * booking-app -> concert-app 내부 호출용 구현체
 *
 * 역할:
 * 1. 좌석 상세 조회
 * 2. 좌석 HOLD
 * 3. 좌석 RELEASE
 * 4. 좌석 CONFIRM
 *
 * base-url은 nginx_proxy 또는 나중에 scg-app으로 두는 걸 권장
 *
 * 전제:
 * - concert-app 내부 API가 plain DTO(JSON 객체)로 응답한다고 가정
 * - 현재 프로젝트의 Controller 스타일과 맞추기 위해 envelope(success/data/error) 없이 단순화
 */
@Component
public class ConcertSeatInternalClientImpl implements ConcertSeatInternalClient {

    private final RestClient restClient;

    public ConcertSeatInternalClientImpl(
            RestClient.Builder builder,
            @Value("${internal.clients.concert.base-url:http://nginx_proxy}") String baseUrl
    ) {
        this.restClient = builder
                .baseUrl(baseUrl)
                .build();
    }

    @Override
    public ConcertSeatDetail readSeat(Long seatId) {
        try {
            SeatDetailResponse response = restClient.get()
                    .uri("/internal/v1/seats/{seatId}", seatId)
                    .retrieve()
                    .body(SeatDetailResponse.class);

            if (response == null) {
                throw new IllegalStateException("concert-app 좌석 상세 조회 응답이 null 입니다. seatId = " + seatId);
            }

            return new ConcertSeatDetail(
                    response.seatId(),
                    response.scheduleId(),
                    response.eventId(),
                    response.seatNo(),
                    response.price(),
                    response.status(),
                    response.version()
            );
        } catch (RestClientResponseException e) {
            throw new IllegalStateException(
                    "concert-app 좌석 상세 조회 실패: status=%s, body=%s"
                            .formatted(e.getStatusCode(), e.getResponseBodyAsString()),
                    e
            );
        }
    }

    @Override
    public SeatCommandResult holdSeat(Long seatId) {
        return changeSeatStatus(
                seatId,
                "/internal/v1/seats/{seatId}/hold",
                new SeatCommandRequest("AVAILABLE"),
                "concert-app seat hold"
        );
    }

    @Override
    public SeatCommandResult releaseSeat(Long seatId) {
        return changeSeatStatus(
                seatId,
                "/internal/v1/seats/{seatId}/release",
                new SeatCommandRequest("HOLD"),
                "concert-app seat release"
        );
    }

    @Override
    public SeatCommandResult confirmSeat(Long seatId) {
        return changeSeatStatus(
                seatId,
                "/internal/v1/seats/{seatId}/confirm",
                new SeatCommandRequest("HOLD"),
                "concert-app seat confirm"
        );
    }

    private SeatCommandResult changeSeatStatus(
            Long seatId,
            String uriTemplate,
            SeatCommandRequest request,
            String apiName
    ) {
        try {
            SeatCommandResponse response = restClient.post()
                    .uri(uriTemplate, seatId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(SeatCommandResponse.class);

            if (response == null) {
                throw new IllegalStateException(apiName + " 응답이 null 입니다. seatId = " + seatId);
            }

            return new SeatCommandResult(
                    response.seatId(),
                    response.scheduleId(),
                    response.status(),
                    response.version()
            );
        } catch (RestClientResponseException e) {
            // ADR: concert-app 409 → BusinessException(SEAT_ALREADY_HELD) 변환
            //
            // 배경:
            //   concert-app은 낙관적 락(@Version) 충돌 또는 좌석 상태 불일치(HOLD→HOLD) 시
            //   409 SEAT_CONCURRENT_CONFLICT를 반환한다.
            //
            // 문제:
            //   기존 코드는 모든 HTTP 오류를 IllegalStateException으로 감싸 re-throw했다.
            //   GlobalExceptionHandler에 IllegalStateException 전용 핸들러가 없으므로
            //   handleAll(Exception e) → C999 500 응답 → k6 5xx 카운트 증가 → 임계치 위반.
            //
            // 해결:
            //   409는 "좌석이 이미 선점됨"을 의미하므로 SEAT_ALREADY_HELD(S001, HTTP 409)로 변환.
            //   GlobalExceptionHandler.handleBusiness()가 409 JSON 응답을 생성하게 된다.
            //   → 클라이언트에게 재시도 불가한 비즈니스 오류임을 명확히 전달.
            //   → k6에서 409는 conflict(예상된 경합)으로 분류되어 5xx 카운트에 포함되지 않음.
            if (e.getStatusCode().value() == 409) {
                throw new BusinessException(ErrorCode.SEAT_ALREADY_HELD);
            }
            throw new IllegalStateException(
                    "%s 실패: status=%s, body=%s"
                            .formatted(apiName, e.getStatusCode(), e.getResponseBodyAsString()),
                    e
            );
        }
    }

    private record SeatCommandRequest(String expectedStatus) {
    }

    /**
     * concert-app 내부 조회 API 응답 DTO
     *
     * 중요:
     * - booking-app이 waiting token 검증용 eventId가 필요하므로
     * - concert-app 좌석 상세 내부 API는 반드시 eventId를 같이 내려줘야 함
     */
    private record SeatDetailResponse(
            Long seatId,
            Long scheduleId,
            Long eventId,
            Integer seatNo,
            BigDecimal price,
            String status,
            Long version
    ) {
    }

    /**
     * concert-app 내부 상태 변경 API 응답 DTO
     */
    private record SeatCommandResponse(
            Long seatId,
            Long scheduleId,
            String status,
            Long version
    ) {
    }
}
