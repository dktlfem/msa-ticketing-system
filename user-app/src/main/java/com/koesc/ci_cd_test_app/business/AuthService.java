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
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ADR: 인증 서비스 — 로그인/토큰 갱신 비즈니스 로직
 *
 * [인증 흐름]
 * 1. 로그인: email + password → Access Token + Refresh Token 발급
 *    - 비밀번호 검증: BCrypt (PasswordEncoder)
 *    - Refresh Token은 Redis에 저장 (서버 사이드 무효화 지원)
 *
 * 2. 토큰 갱신: Refresh Token → 새 Access Token + 새 Refresh Token 발급
 *    - Token Rotation 적용: 갱신 시 Refresh Token도 재발급
 *    - Redis에 저장된 토큰과 일치 여부 확인 → 탈취된 구 토큰 재사용 차단
 *
 * [역할(Role) 설계]
 * - 현재는 모든 사용자에게 ROLE_USER를 부여 (MVP 수준)
 * - 향후 UserEntity에 roles 컬럼 추가 시 동적 역할 부여로 확장 가능
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final List<String> DEFAULT_ROLES = List.of("ROLE_USER");

    private final UserManager userManager;
    private final JwtProvider jwtProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final PasswordEncoder passwordEncoder;

    /**
     * 로그인 처리
     *
     * 1. 이메일로 사용자 조회
     * 2. BCrypt 비밀번호 검증
     * 3. Access Token + Refresh Token 발급
     * 4. Refresh Token을 Redis에 저장
     */
    @Transactional(readOnly = true)
    public TokenResponseDTO login(LoginRequestDTO request) {
        User user;
        try {
            user = userManager.getUserByEmail(request.email());
        } catch (EntityNotFoundException e) {
            // 사용자 존재 여부를 외부에 노출하지 않기 위해 동일한 에러 메시지 반환
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_FAILED);
        }

        return issueTokens(user.getId());
    }

    /**
     * 토큰 갱신 (Token Rotation)
     *
     * 1. Refresh Token 서명 검증 + 만료 확인
     * 2. Redis 저장 토큰과 일치 여부 확인 (탈취 방지)
     * 3. 새 Access Token + 새 Refresh Token 발급
     * 4. Redis에 새 Refresh Token으로 교체
     */
    public TokenResponseDTO refresh(RefreshRequestDTO request) {
        String refreshToken = request.refreshToken();

        Claims claims;
        try {
            claims = jwtProvider.parseToken(refreshToken);
        } catch (ExpiredJwtException e) {
            throw new BusinessException(ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException e) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }

        Long userId = Long.valueOf(claims.getSubject());

        // Redis에 저장된 토큰과 비교 → Token Rotation 탈취 감지
        if (!refreshTokenStore.matches(userId, refreshToken)) {
            log.warn("[AUTH] Refresh Token 불일치 — 탈취 의심. userId={}", userId);
            // 의심 시 해당 유저의 모든 Refresh Token 삭제 (강제 재로그인)
            refreshTokenStore.delete(userId);
            throw new BusinessException(ErrorCode.REFRESH_TOKEN_NOT_FOUND);
        }

        return issueTokens(userId);
    }

    /**
     * 로그아웃 처리
     *
     * Redis에서 해당 유저의 Refresh Token을 삭제하여 즉시 무효화한다.
     * Access Token은 stateless이므로 서버에서 강제 만료할 수 없지만,
     * 짧은 수명(30분)으로 자연 만료되며, Refresh Token이 삭제되어 갱신이 불가하다.
     */
    public void logout(Long userId) {
        refreshTokenStore.delete(userId);
        log.info("[AUTH] 로그아웃 완료: userId={}", userId);
    }

    /**
     * Access Token + Refresh Token 발급 공통 로직
     */
    private TokenResponseDTO issueTokens(Long userId) {
        String accessToken = jwtProvider.createAccessToken(userId, DEFAULT_ROLES);
        String refreshToken = jwtProvider.createRefreshToken(userId, DEFAULT_ROLES);

        // Refresh Token을 Redis에 저장 (TTL = refresh token 유효 기간)
        refreshTokenStore.save(userId, refreshToken, jwtProvider.getRefreshTokenValiditySeconds());

        log.info("[AUTH] 토큰 발급 완료: userId={}", userId);

        return new TokenResponseDTO(accessToken, refreshToken, jwtProvider.getAccessTokenValiditySeconds());
    }
}
