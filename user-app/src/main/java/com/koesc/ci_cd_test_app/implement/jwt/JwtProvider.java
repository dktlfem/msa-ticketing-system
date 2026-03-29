package com.koesc.ci_cd_test_app.implement.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * ADR: JWT 토큰 생성 및 검증 — HMAC-SHA256 서명
 *
 * [설계 근거]
 * - scg-app의 JwtAuthenticationFilter와 동일한 서명 알고리즘(HMAC-SHA256) 및 Claims 규약을 따른다.
 * - Claims 규약:
 *   - sub (subject): userId (String으로 변환)
 *   - roles (custom): 역할 목록 (List<String>)
 *   - jti: 토큰 고유 ID (UUID) — 토큰 무효화 및 추적에 활용
 *   - iat: 토큰 발급 시각
 *   - exp: 토큰 만료 시각
 *
 * [Access Token vs Refresh Token]
 * - Access Token: 짧은 수명(30분), API 요청 인증에 사용
 * - Refresh Token: 긴 수명(7일), Access Token 재발급에만 사용
 * - Refresh Token은 Redis에 저장하여 서버 사이드 무효화 가능 (로그아웃, 강제 만료)
 *
 * [scg-app 연동]
 * - scg-app은 gateway.security.jwt-secret 프로퍼티로 동일한 시크릿을 공유한다.
 * - 운영 환경에서는 Spring Cloud Config 또는 환경변수로 시크릿을 주입해야 한다.
 */
@Slf4j
@Component
public class JwtProvider {

    private final SecretKey secretKey;
    private final long accessTokenValidityMs;
    private final long refreshTokenValidityMs;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-validity-seconds:1800}") long accessTokenValiditySeconds,
            @Value("${jwt.refresh-token-validity-seconds:604800}") long refreshTokenValiditySeconds
    ) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenValidityMs = accessTokenValiditySeconds * 1000;
        this.refreshTokenValidityMs = refreshTokenValiditySeconds * 1000;
    }

    /**
     * Access Token 생성
     *
     * @param userId 사용자 고유 ID
     * @param roles  사용자 역할 목록
     * @return 서명된 JWT Access Token 문자열
     */
    public String createAccessToken(Long userId, List<String> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenValidityMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))   // sub: userId (scg-app 규약)
                .claim("roles", roles)              // roles: 역할 목록 (scg-app 규약)
                .id(UUID.randomUUID().toString())   // jti: 토큰 고유 ID
                .issuedAt(now)                      // iat: 발급 시각
                .expiration(expiry)                 // exp: 만료 시각
                .signWith(secretKey)                // HMAC-SHA256 서명
                .compact();
    }

    /**
     * Refresh Token 생성
     *
     * Access Token과 동일한 구조이나 수명이 길다.
     * Redis에 저장하여 서버 사이드 무효화를 지원한다.
     */
    public String createRefreshToken(Long userId, List<String> roles) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + refreshTokenValidityMs);

        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("roles", roles)
                .claim("type", "refresh")           // 토큰 타입 구분
                .id(UUID.randomUUID().toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 토큰에서 Claims 파싱 (서명 검증 포함)
     *
     * @throws ExpiredJwtException 토큰 만료 시
     * @throws JwtException        서명 불일치 등 유효하지 않은 토큰
     */
    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(secretKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 토큰에서 userId 추출
     */
    public Long getUserId(String token) {
        return Long.valueOf(parseToken(token).getSubject());
    }

    /**
     * 토큰 유효성 검증
     *
     * @return true: 유효, false: 만료 또는 변조
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("[JWT] 토큰 만료: {}", e.getMessage());
            return false;
        } catch (JwtException e) {
            log.warn("[JWT] 유효하지 않은 토큰: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Access Token 만료 시간(초) 반환 — 응답 DTO에 포함
     */
    public long getAccessTokenValiditySeconds() {
        return accessTokenValidityMs / 1000;
    }

    /**
     * Refresh Token 만료 시간(초) 반환 — Redis TTL 설정에 사용
     */
    public long getRefreshTokenValiditySeconds() {
        return refreshTokenValidityMs / 1000;
    }
}
