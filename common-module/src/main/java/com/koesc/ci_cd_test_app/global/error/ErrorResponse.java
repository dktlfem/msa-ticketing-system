package com.koesc.ci_cd_test_app.global.error;

public record ErrorResponse(
        String code,
        String message,
        int status
) {
    public static ErrorResponse of(ErrorCode ec) {
        return new ErrorResponse(ec.getCode(), ec.getMessage(), ec.getHttpStatus().value());
    }
}
