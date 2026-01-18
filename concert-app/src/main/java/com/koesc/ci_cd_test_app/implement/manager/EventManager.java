package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.Event;
import com.koesc.ci_cd_test_app.implement.reader.EventReader;
import com.koesc.ci_cd_test_app.implement.validator.EventValidator;
import com.koesc.ci_cd_test_app.implement.writer.EventWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class EventManager {

    private final EventReader eventReader;
    private final EventWriter eventWriter;
    private final EventValidator eventValidator;

    /**
     * 공연 등록 : 검증 후 저장 및 캐시 무효화 조율
     */
    public Event createdEvent(Event event) {

        // 1. 비즈니스 유효성 검증 (제목 중복 등)
        eventValidator.validateBasicInfo(event.getTitle(), event.getDescription());
        eventValidator.validateDuplicateTitle(event.getTitle());

        // 2. DB 저장
        return eventWriter.save(event);
    }

    /**
     * 공연 수정 : 수정 후 정합성을 위해 캐시 처리를 Writer가 하거나 Manager(여기서)가 조율
     */
    public Event updateEvent(Long eventId, String title, String description, String posterUrl) {

        // 1. 존재 여부 확인
        Event currentEvent = eventReader.read(eventId);

        // 2. 도메인 모델을 통한 불변 객체 갱신
        Event updatedEvent = currentEvent.updateInfo(title, description, posterUrl);

        // 3. 저장 (Writer 내부에서 @CacheEvict 처리 권장)
        return eventWriter.save(updatedEvent);
    }

    /**
     * 공연 상세 정보 조회
     * Reader에서 구현한 2차 캐싱(L1, L2) 전략을 그대로 활용하여 DB(L3) 부하를 차단함.
     */
    public Event getEventDetails(Long eventId) {
        return eventReader.read(eventId);
    }

    /**
     * 전체 공연 목록 조회
     * 초기 개발 단계에서는 List를 반환하지만,
     * 대규모 환경(L3 DB 보호)을 위해 Reader 단계에서 페이징 처리가 권장됨.
     */
    public List<Event> getAllEvents() {
        return eventReader.readAll();
    }
}
