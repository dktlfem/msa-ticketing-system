package com.koesc.ci_cd_test_app.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * 공연 도메인 (불변 객체 스타일)
 * User 도메인과 동일한 아키텍처 스타일을 유지하여 일관성 확
 */

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Event {

    private final Long eventId;
    private final String title;
    private final String description;
    private final String posterUrl;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    /**
     * 공연 정보 수정 (불변성 유지)
     * 기존 객체를 수정하는 대신 toBuilder로 새 객체를 반환하여 Side Effect 방지
     */
    public Event updateInfo(String title, String description, String posterUrl) {
        return this.toBuilder()
                .title(title)
                .description(description)
                .posterUrl(posterUrl)
                .build();
    }

    /**
     * 새로운 공연(Event) 생성용 정적 팩토리 메서드
     */
    public static Event create(String title, String description, String posterUrl) {
        return builder()
                .title(title)
                .description(description)
                .posterUrl(posterUrl)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
