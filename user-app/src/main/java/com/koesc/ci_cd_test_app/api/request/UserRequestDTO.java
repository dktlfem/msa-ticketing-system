package com.koesc.ci_cd_test_app.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "회원 가입 요청")
public record UserRequestDTO(
    @Schema(description = "이메일", example = "test@example.com")
    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    String email,

    @Schema(description = "이름", example = "최민석")
    @NotBlank(message = "이름은 필수입니다.")
    String name,

    @Schema(description = "비밀번호", example = "password123!")
    @NotBlank(message = "비밀번호는 필수입니다.")
    String password
) {}
