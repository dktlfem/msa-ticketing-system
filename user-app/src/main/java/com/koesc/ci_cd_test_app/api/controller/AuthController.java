package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.api.request.LoginRequestDTO;
import com.koesc.ci_cd_test_app.api.request.RefreshRequestDTO;
import com.koesc.ci_cd_test_app.api.response.TokenResponseDTO;
import com.koesc.ci_cd_test_app.business.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 인증 API 컨트롤러
 *
 * [엔드포인트]
 * POST /api/v1/auth/login   — 이메일 + 비밀번호로 로그인, JWT 토큰 쌍 반환
 * POST /api/v1/auth/refresh — Refresh Token으로 새 토큰 쌍 발급 (Token Rotation)
 * POST /api/v1/auth/logout  — Refresh Token 무효화 (Redis 삭제)
 *
 * [보안 설정]
 * /api/v1/auth/** 경로는 SecurityConfig에서 permitAll 처리.
 * 인증 없이 접근 가능해야 하므로 JWT 필터 적용 대상에서 제외.
 */
@Tag(name = "Auth API", description = "인증 관련 API (로그인, 토큰 갱신)")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "로그인", description = "이메일과 비밀번호로 로그인하여 JWT 토큰 쌍을 발급받습니다.")
    @PostMapping("/login")
    public TokenResponseDTO login(@RequestBody @Valid LoginRequestDTO request) {
        return authService.login(request);
    }

    @Operation(summary = "토큰 갱신", description = "Refresh Token을 사용하여 새로운 Access Token과 Refresh Token을 발급받습니다.")
    @PostMapping("/refresh")
    public TokenResponseDTO refresh(@RequestBody @Valid RefreshRequestDTO request) {
        return authService.refresh(request);
    }

    /**
     * ADR: 로그아웃 시 userId는 scg-app의 JwtAuthenticationFilter가 주입하는
     * Auth-User-Id 헤더에서 추출한다. 직접 요청 시에는 클라이언트가 전달.
     * Gateway 경유 시 JWT 검증 → Auth-User-Id 헤더 자동 주입 → 이 엔드포인트에서 수신.
     */
    @Operation(summary = "로그아웃", description = "Redis에서 Refresh Token을 삭제하여 토큰을 무효화합니다.")
    @PostMapping("/logout")
    public void logout(@RequestHeader("Auth-User-Id") Long userId) {
        authService.logout(userId);
    }
}
