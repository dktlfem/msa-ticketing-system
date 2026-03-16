package com.koesc.ci_cd_test_app.global.error;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "적절하지 않은 입력 값입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "C999", "Internal Server Error"),

    // 공연(Event) 관련
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "E001", "존재하지 않는 공연입니다."),
    EVENT_CLOSED(HttpStatus.BAD_REQUEST, "E002", "이미 종료되거나 취소된 공연입니다."),
    TICKETING_NOT_OPENED(HttpStatus.BAD_REQUEST, "E003", "아직 예매 오픈 전입니다."),
    EVENT_SOLD_OUT(HttpStatus.CONFLICT, "E004", "모든 좌석이 매진되었습니다."),

    // 회차(EventSchedule) 관련
    EVENT_SCHEDULE_NOT_FOUND(HttpStatus.NOT_FOUND, "ES001", "존재하지 않는 회차입니다."),

    // 대기열(WaitingRoom) 관련
    ALREADY_HAS_TOKEN(HttpStatus.CONFLICT, "W001", "이미 유효한 입장 토큰을 보유하고 있습니다."),

    // 낙관적 락(OptimisticLock)
    SEAT_ALREADY_HELD(HttpStatus.CONFLICT, "S001", "다른 사용자가 먼저 좌석을 선택했습니다."),
    SEAT_NOT_FOUND(HttpStatus.NOT_FOUND, "S002", "존재하지 않는 좌석입니다."),

    // 예약(Reservation) 관련
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "R001", "존재하지 않는 예약입니다."),
    RESERVATION_NOT_CONFIRMABLE(HttpStatus.CONFLICT, "R002", "결제 대기 중이 아니거나 만료된 예약입니다."),

    // 결제(Payment) 관련
    PAYMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "P001", "존재하지 않는 결제입니다."),
    PAYMENT_ALREADY_EXISTS(HttpStatus.CONFLICT, "P002", "이미 결제가 존재하는 예약입니다."),
    PAYMENT_AMOUNT_MISMATCH(HttpStatus.BAD_REQUEST, "P003", "결제 금액이 일치하지 않습니다."),
    PAYMENT_INVALID_STATUS(HttpStatus.CONFLICT, "P004", "현재 상태에서 허용되지 않는 결제 요청입니다."),
    PAYMENT_PG_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "P005", "결제 처리 중 오류가 발생했습니다."),
    PAYMENT_IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "P006", "동일한 요청이 처리 중입니다.");


    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
