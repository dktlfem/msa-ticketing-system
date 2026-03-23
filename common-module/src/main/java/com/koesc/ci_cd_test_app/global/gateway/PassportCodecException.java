package com.koesc.ci_cd_test_app.global.gateway;

/**
 * Auth-Passport 헤더의 직렬화/역직렬화 실패 시 발생하는 예외.
 */
public class PassportCodecException extends RuntimeException {

    public PassportCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
