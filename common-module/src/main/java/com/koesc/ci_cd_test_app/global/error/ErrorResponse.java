package com.koesc.ci_cd_test_app.global.error;

/**
 * 외부에 너무 내부정보(쿼리/스택/민감 데이터)는 노출하지 말 것
 * scheduleId 정도는 보통 괜찮음(식별자 수준)
 */
public record ErrorResponse(String code, String message, int status) {

    public static ErrorResponse of(ErrorCode ec) {
        return new ErrorResponse(ec.getCode(), ec.getMessage(), ec.getHttpStatus().value());
    }

    public static ErrorResponse of(ErrorCode ec, String message) {
        return new ErrorResponse(ec.getCode(), message, ec.getHttpStatus().value());
    }
}
