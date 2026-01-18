package com.koesc.ci_cd_test_app.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 공연 생성/수정 요청 DTO
 * Record를 사용하여 불변성 확보 및 Boilerplate 코드 제거
 */
public record EventRequestDTO(
   @NotBlank(message = "공연 제목은 필수입니다.")
   @Size(max = 100, message = "공연 제목은 100자를 초과할 수 없습니다.")
   String title,

   @NotBlank(message = "공연 상세 설명은 필수입니다.")
   String description,

   @Size(max = 255, message = "포스터 URL 경로가 너무 깁니다.")
   String posterUrl
) {}
