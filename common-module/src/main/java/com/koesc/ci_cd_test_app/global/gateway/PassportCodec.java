package com.koesc.ci_cd_test_app.global.gateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Auth-Passport 헤더의 Base64url(JSON) 직렬화/역직렬화 유틸.
 *
 * 인코딩: UserPassport → JSON → Base64url (패딩 없음)
 * 디코딩: Base64url → JSON → UserPassport
 */
public final class PassportCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private PassportCodec() {}

    /**
     * UserPassport를 Base64url 인코딩된 JSON 문자열로 직렬화한다.
     *
     * @throws PassportCodecException JSON 직렬화 실패 시
     */
    public static String encode(UserPassport passport) {
        try {
            byte[] json = MAPPER.writeValueAsBytes(passport);
            return ENCODER.encodeToString(json);
        } catch (JsonProcessingException e) {
            throw new PassportCodecException("Failed to encode UserPassport", e);
        }
    }

    /**
     * Base64url 인코딩된 문자열을 UserPassport로 역직렬화한다.
     *
     * @throws PassportCodecException Base64 디코딩 실패 또는 JSON 역직렬화 실패 시
     */
    public static UserPassport decode(String encoded) {
        try {
            byte[] json = DECODER.decode(encoded.getBytes(StandardCharsets.UTF_8));
            return MAPPER.readValue(json, UserPassport.class);
        } catch (IllegalArgumentException e) {
            throw new PassportCodecException("Invalid Base64url encoding in Auth-Passport header", e);
        } catch (Exception e) {
            throw new PassportCodecException("Failed to decode Auth-Passport header", e);
        }
    }
}
