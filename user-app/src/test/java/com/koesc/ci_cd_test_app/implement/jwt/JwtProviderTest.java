package com.koesc.ci_cd_test_app.implement.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JwtProvider 단위 테스트")
class JwtProviderTest {

    private static final String TEST_SECRET = "test-secret-key-must-be-at-least-32-bytes-long!!";
    private static final long ACCESS_TOKEN_VALIDITY = 1800L;  // 30분
    private static final long REFRESH_TOKEN_VALIDITY = 604800L; // 7일

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(TEST_SECRET, ACCESS_TOKEN_VALIDITY, REFRESH_TOKEN_VALIDITY);
    }

    @Test
    @DisplayName("Access Token 생성 시 Claims에 userId, roles, jti, iat가 포함된다")
    void createAccessToken_shouldContainExpectedClaims() {
        // given
        Long userId = 1L;
        List<String> roles = List.of("ROLE_USER");

        // when
        String token = jwtProvider.createAccessToken(userId, roles);

        // then
        Claims claims = jwtProvider.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("roles", List.class)).containsExactly("ROLE_USER");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isNotNull();
    }

    @Test
    @DisplayName("Refresh Token 생성 시 type=refresh claim이 포함된다")
    void createRefreshToken_shouldContainTypeRefreshClaim() {
        // given
        Long userId = 1L;
        List<String> roles = List.of("ROLE_USER");

        // when
        String token = jwtProvider.createRefreshToken(userId, roles);

        // then
        Claims claims = jwtProvider.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("type", String.class)).isEqualTo("refresh");
    }

    @Test
    @DisplayName("유효한 토큰 검증 시 true를 반환한다")
    void validateToken_withValidToken_shouldReturnTrue() {
        // given
        String token = jwtProvider.createAccessToken(1L, List.of("ROLE_USER"));

        // when & then
        assertThat(jwtProvider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("만료된 토큰 검증 시 false를 반환한다")
    void validateToken_withExpiredToken_shouldReturnFalse() {
        // given: 유효기간이 0초인 JwtProvider로 즉시 만료되는 토큰 생성
        JwtProvider expiredProvider = new JwtProvider(TEST_SECRET, 0L, 0L);
        String token = expiredProvider.createAccessToken(1L, List.of("ROLE_USER"));

        // when & then
        assertThat(jwtProvider.validateToken(token)).isFalse();
    }

    @Test
    @DisplayName("변조된 토큰 검증 시 false를 반환한다")
    void validateToken_withTamperedToken_shouldReturnFalse() {
        // given
        String token = jwtProvider.createAccessToken(1L, List.of("ROLE_USER"));
        String tampered = token + "tampered";

        // when & then
        assertThat(jwtProvider.validateToken(tampered)).isFalse();
    }

    @Test
    @DisplayName("만료된 토큰 파싱 시 ExpiredJwtException이 발생한다")
    void parseToken_withExpiredToken_shouldThrowExpiredJwtException() {
        // given
        JwtProvider expiredProvider = new JwtProvider(TEST_SECRET, 0L, 0L);
        String token = expiredProvider.createAccessToken(1L, List.of("ROLE_USER"));

        // when & then
        assertThatThrownBy(() -> jwtProvider.parseToken(token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("getUserId로 토큰에서 userId를 추출할 수 있다")
    void getUserId_shouldReturnCorrectUserId() {
        // given
        String token = jwtProvider.createAccessToken(42L, List.of("ROLE_USER"));

        // when
        Long userId = jwtProvider.getUserId(token);

        // then
        assertThat(userId).isEqualTo(42L);
    }

    @Test
    @DisplayName("다른 시크릿으로 서명된 토큰은 검증에 실패한다")
    void validateToken_withDifferentSecret_shouldReturnFalse() {
        // given
        JwtProvider otherProvider = new JwtProvider(
                "other-secret-key-must-be-at-least-32-bytes-long!!", ACCESS_TOKEN_VALIDITY, REFRESH_TOKEN_VALIDITY);
        String token = otherProvider.createAccessToken(1L, List.of("ROLE_USER"));

        // when & then
        assertThat(jwtProvider.validateToken(token)).isFalse();
    }
}
