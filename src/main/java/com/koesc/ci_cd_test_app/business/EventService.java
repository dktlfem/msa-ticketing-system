package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.api.request.EventRequestDTO;
import com.koesc.ci_cd_test_app.api.response.EventResponseDTO;
import com.koesc.ci_cd_test_app.domain.Event;
import com.koesc.ci_cd_test_app.implement.manager.EventManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class EventService {

    private final EventManager eventManager;

    /**
     * 공연 신규 등록
     * @Transactional : DB 저장과 캐시 무효화(필요 시)를 하나의 작업 단위로 묶음
     */
    @Transactional
    public EventResponseDTO createEvent(EventRequestDTO request) {

        // 1. DTO -> Domain 변환
        Event event = Event.create(request.title(), request.description(), request.posterUrl());

        // 2. Manager를 통한 비즈니스 로직 수행 (검증 + 저장)
        Event savedEvent = eventManager.createdEvent(event);

        log.info("Successfully created event: {} (ID: {})", savedEvent.getTitle(), savedEvent.getEventId());

        // 3. Domain -> ResponseDTO 변환
        return EventResponseDTO.from(savedEvent);
    }

    /**
     * 공연 상세 정보 조회
     * readOnly = true : 대규모 조회 성능 최적화 (더티 체킹 제외 등)
     */
    @Transactional(readOnly = true)
    public EventResponseDTO getEventDetails(Long eventId) {

        // 1. Manager를 통해 조회 (내부적으로 L1, L2 캐 적용됨)
        Event eventDetails = eventManager.getEventDetails(eventId);

        // 2. ResponseDTO로 변환하여 반환
        return EventResponseDTO.from(eventDetails);
    }

    /**
     * 전체 공연 목록 조회
     * 대규모 트래픽 주의 : 데이터가 1,000건만 넘어가도 응답 지연 및 메모리 병목이 발생 (페이징 처리 없이 전체를 조회하는 것은 위험)
     * -> 반드시 Paging(Pageable)을 적용하여 한 번에 10~20개씩 끊어야 함.
     */
    @Transactional(readOnly = true)
    public List<EventResponseDTO> getAllEvents() {
        return eventManager.getAllEvents().stream()
                .map(EventResponseDTO::from)
                .toList();
    }
}
