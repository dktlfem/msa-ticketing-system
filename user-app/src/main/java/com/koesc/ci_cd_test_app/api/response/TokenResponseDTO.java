package com.koesc.ci_cd_test_app.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "JWT 토큰 응답")
public record TokenResponseDTO(
    @Schema(description = "액세스 토큰 (Bearer 스킴으로 Authorization 헤더에 사용)")
    String accessToken,

    @Schema(description = "리프레시 토큰 (액세스 토큰 만료 시 갱신에 사용)")
    String refreshToken,

    @Schema(description = "액세스 토큰 만료까지 남은 시간(초)")
    long expiresIn
) {}
