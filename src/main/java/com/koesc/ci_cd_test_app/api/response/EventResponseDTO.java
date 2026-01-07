package com.koesc.ci_cd_test_app.api.response;

import com.koesc.ci_cd_test_app.domain.Event;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "공연 정보 응답")
public record EventResponseDTO(

    @Schema(description = "공연 고유 ID")
    Long id,

    @Schema(description = "공연 제목")
    String title,

    @Schema(description = "공연 상세 설명")
    String description,

    @Schema(description = "포스터 이미지 URL")
    String posterUrl,

    @Schema(description = "생성 일시")
    LocalDateTime createdAt,

    @Schema(description = "수정 일시 (캐시 정합성 판단 기준)")
    LocalDateTime updatedAt
) {
    public static EventResponseDTO from(Event event) {
        return new EventResponseDTO(
                event.getEventId(),
                event.getTitle(),
                event.getDescription(),
                event.getPosterUrl(),
                event.getCreatedAt(),
                event.getUpdatedAt()
        );
    }
}
