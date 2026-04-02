package com.koesc.ci_cd_test_app.global.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PassportCodec의 encode/decode 동작을 검증한다.
 *
 * 테스트 범위:
 *  1) round-trip: encode → decode 결과가 원본과 일치
 *  2) bad Base64: 유효하지 않은 Base64url 입력 시 PassportCodecException
 *  3) bad JSON: Base64url은 정상이지만 JSON 구조가 맞지 않을 때 PassportCodecException
 */
class PassportCodecTest {

    @Test
    @DisplayName("encode → decode round-trip: 원본 UserPassport와 동일한 값이 복원된다")
    void roundTrip() {
        // given
        UserPassport original = new UserPassport(
                "user-42",
                List.of("ROLE_USER", "ROLE_ADMIN"),
                "jti-abc-123",
                1700000000L,
                "192.168.0.1"
        );

        // when
        String encoded = PassportCodec.encode(original);
        UserPassport decoded = PassportCodec.decode(encoded);

        // then
        assertAll(
                () -> assertEquals(original.userId(), decoded.userId()),
                () -> assertEquals(original.roles(), decoded.roles()),
                () -> assertEquals(original.jti(), decoded.jti()),
                () -> assertEquals(original.issuedAt(), decoded.issuedAt()),
                () -> assertEquals(original.clientIp(), decoded.clientIp())
        );
    }

    @Test
    @DisplayName("유효하지 않은 Base64url 입력 시 PassportCodecException이 발생한다")
    void decodeInvalidBase64ThrowsException() {
        // given — '!!!' 는 Base64url 알파벳에 포함되지 않는 문자
        String invalidBase64 = "!!!not-valid-base64!!!";

        // when & then
        PassportCodecException ex = assertThrows(
                PassportCodecException.class,
                () -> PassportCodec.decode(invalidBase64)
        );
        assertTrue(ex.getMessage().contains("Base64url"));
    }

    @Test
    @DisplayName("Base64url은 정상이지만 JSON 구조가 맞지 않으면 PassportCodecException이 발생한다")
    void decodeInvalidJsonThrowsException() {
        // given — "not-json" 을 Base64url로 인코딩
        String notJson = java.util.Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("not-json".getBytes());

        // when & then
        PassportCodecException ex = assertThrows(
                PassportCodecException.class,
                () -> PassportCodec.decode(notJson)
        );
        assertTrue(ex.getMessage().contains("decode"));
    }
}
