package com.koesc.ci_cd_test_app.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

@Schema(description = "사용자 정보 응답")
public record UserResponseDTO(
    @Schema(description = "사용자 고유 ID")
    Long id,

    @Schema(description = "이메일")
    String email,

    @Schema(description = "이름")
    String name,

    @Schema(description = "보유 포인트")
    BigDecimal point
) {}
