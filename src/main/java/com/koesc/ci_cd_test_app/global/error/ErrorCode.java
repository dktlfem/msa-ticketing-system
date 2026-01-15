package com.koesc.ci_cd_test_app.global.error;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 공통
    INVALID_INPUT_VALUE(HttpStatus.BAD_REQUEST, "C001", "적절하지 않은 입력 값입니다."),

    // 공연(Event) 관련
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND, "E001", "존재하지 않는 공연입니다."),
    EVENT_CLOSED(HttpStatus.BAD_REQUEST, "E002", "이미 종료되거나 취소된 공연입니다."),
    TICKETING_NOT_OPENED(HttpStatus.BAD_REQUEST, "E003", "아직 예매 오픈 전입니다."),
    EVENT_SOLD_OUT(HttpStatus.CONFLICT, "E004", "모든 좌석이 매진되었습니다."),

    // 대기열(WaitingRoom) 관련
    ALREADY_HAS_TOKEN(HttpStatus.CONFLICT, "W001", "이미 유효한 입장 토큰을 보유하고 있습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
