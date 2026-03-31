package com.koesc.ci_cd_test_app.domain;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
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
// ADR: Redis L2 캐시 역직렬화 지원
// - @AllArgsConstructor(access = PRIVATE)로 Jackson이 직접 생성자를 호출할 수 없음.
// - @JsonDeserialize(builder = EventBuilder.class) + @JsonPOJOBuilder(withPrefix = "")으로
//   Lombok이 생성한 빌더를 Jackson 역직렬화에 활용한다.
// - withPrefix = "" 이유: Lombok Builder의 setter 메서드는 "withXxx"가 아닌 "xxx" 형식.
@JsonDeserialize(builder = Event.EventBuilder.class)
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
     * Jackson 역직렬화용 Builder 클래스 (Lombok @Builder와 병행 선언)
     * Lombok이 이 클래스에 Builder 구현을 채워넣으며, Jackson은 이 Builder로 Event를 복원한다.
     */
    @JsonPOJOBuilder(withPrefix = "")
    public static class EventBuilder {
        // Lombok이 코드 생성 — 명시적 구현 불필요
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
