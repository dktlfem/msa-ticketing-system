package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.api.request.LoginRequestDTO;
import com.koesc.ci_cd_test_app.api.request.RefreshRequestDTO;
import com.koesc.ci_cd_test_app.api.response.TokenResponseDTO;
import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.implement.jwt.JwtProvider;
import com.koesc.ci_cd_test_app.implement.jwt.RefreshTokenStore;
import com.koesc.ci_cd_test_app.implement.manager.UserManager;
import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("AuthService 단위 테스트")
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @InjectMocks
    private AuthService authService;

    @Mock
    private UserManager userManager;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private RefreshTokenStore refreshTokenStore;

    @Mock
    private PasswordEncoder passwordEncoder;

    private User createTestUser() {
        return User.builder()
                .id(1L)
                .email("test@example.com")
                .name("테스트")
                .password("$2a$10$encodedPassword") // BCrypt 해시
                .point(BigDecimal.ZERO)
                .build();
    }

    @Nested
    @DisplayName("로그인")
    class Login {

        @Test
        @DisplayName("올바른 이메일과 비밀번호로 로그인 시 토큰 쌍이 반환된다")
        void login_withValidCredentials_shouldReturnTokens() {
            // given
            LoginRequestDTO request = new LoginRequestDTO("test@example.com", "password123!");
            User user = createTestUser();

            given(userManager.getUserByEmail("test@example.com")).willReturn(user);
            given(passwordEncoder.matches("password123!", "$2a$10$encodedPassword")).willReturn(true);
            given(jwtProvider.createAccessToken(1L, List.of("ROLE_USER"))).willReturn("access-token");
            given(jwtProvider.createRefreshToken(1L, List.of("ROLE_USER"))).willReturn("refresh-token");
            given(jwtProvider.getRefreshTokenValiditySeconds()).willReturn(604800L);
            given(jwtProvider.getAccessTokenValiditySeconds()).willReturn(1800L);

            // when
            TokenResponseDTO result = authService.login(request);

            // then
            assertThat(result.accessToken()).isEqualTo("access-token");
            assertThat(result.refreshToken()).isEqualTo("refresh-token");
            assertThat(result.expiresIn()).isEqualTo(1800L);
            verify(refreshTokenStore).save(eq(1L), eq("refresh-token"), eq(604800L));
        }

        @Test
        @DisplayName("존재하지 않는 이메일로 로그인 시 AUTHENTICATION_FAILED 예외가 발생한다")
        void login_withNonExistentEmail_shouldThrowAuthenticationFailed() {
            // given
            LoginRequestDTO request = new LoginRequestDTO("notfound@example.com", "password");
            given(userManager.getUserByEmail("notfound@example.com"))
                    .willThrow(new EntityNotFoundException("사용자를 찾을 수 없습니다."));

            // when & then
            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUTHENTICATION_FAILED);
        }

        @Test
        @DisplayName("잘못된 비밀번호로 로그인 시 AUTHENTICATION_FAILED 예외가 발생한다")
        void login_withWrongPassword_shouldThrowAuthenticationFailed() {
            // given
            LoginRequestDTO request = new LoginRequestDTO("test@example.com", "wrongpassword");
            User user = createTestUser();

            given(userManager.getUserByEmail("test@example.com")).willReturn(user);
            given(passwordEncoder.matches("wrongpassword", "$2a$10$encodedPassword")).willReturn(false);

            // when & then
            assertThatThrownBy(() -> authService.login(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AUTHENTICATION_FAILED);
        }
    }

    @Nested
    @DisplayName("토큰 갱신")
    class Refresh {

        @Test
        @DisplayName("유효한 Refresh Token으로 갱신 시 새 토큰 쌍이 반환된다")
        void refresh_withValidToken_shouldReturnNewTokens() {
            // given
            RefreshRequestDTO request = new RefreshRequestDTO("valid-refresh-token");
            Claims claims = mock(Claims.class);
            given(claims.getSubject()).willReturn("1");

            given(jwtProvider.parseToken("valid-refresh-token")).willReturn(claims);
            given(refreshTokenStore.matches(1L, "valid-refresh-token")).willReturn(true);
            given(jwtProvider.createAccessToken(1L, List.of("ROLE_USER"))).willReturn("new-access-token");
            given(jwtProvider.createRefreshToken(1L, List.of("ROLE_USER"))).willReturn("new-refresh-token");
            given(jwtProvider.getRefreshTokenValiditySeconds()).willReturn(604800L);
            given(jwtProvider.getAccessTokenValiditySeconds()).willReturn(1800L);

            // when
            TokenResponseDTO result = authService.refresh(request);

            // then
            assertThat(result.accessToken()).isEqualTo("new-access-token");
            assertThat(result.refreshToken()).isEqualTo("new-refresh-token");
            verify(refreshTokenStore).save(eq(1L), eq("new-refresh-token"), eq(604800L));
        }

        @Test
        @DisplayName("Redis에 저장된 토큰과 불일치 시 REFRESH_TOKEN_NOT_FOUND 예외가 발생한다")
        void refresh_withMismatchedToken_shouldThrowRefreshTokenNotFound() {
            // given
            RefreshRequestDTO request = new RefreshRequestDTO("stolen-refresh-token");
            Claims claims = mock(Claims.class);
            given(claims.getSubject()).willReturn("1");

            given(jwtProvider.parseToken("stolen-refresh-token")).willReturn(claims);
            given(refreshTokenStore.matches(1L, "stolen-refresh-token")).willReturn(false);

            // when & then
            assertThatThrownBy(() -> authService.refresh(request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.REFRESH_TOKEN_NOT_FOUND);

            // 탈취 의심 시 해당 유저의 Refresh Token 삭제 확인
            verify(refreshTokenStore).delete(1L);
        }
    }
}
