package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.api.request.EventRequestDTO;
import com.koesc.ci_cd_test_app.api.response.EventResponseDTO;
import com.koesc.ci_cd_test_app.domain.Event;
import com.koesc.ci_cd_test_app.implement.manager.EventManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("EventService 비즈니스 흐름 테스트 (Mock)")
public class EventServiceTest {

    @Mock
    private EventManager eventManager;

    @InjectMocks
    private EventService eventService;

    @Test
    @DisplayName("공연 생성 요청 시 Manager를 통해 저장 후 DTO로 변환하여 반환한다.")
    void createEvent_Success() {

        // 1. given
        EventRequestDTO request = new EventRequestDTO("Title", "Desc", "URL");
        Event savedEvent = Event.builder()
                .eventId(1L)
                .title("Title")
                .description("Desc")
                .posterUrl("URL")
                .createdAt(LocalDateTime.now())
                .build();

        given(eventManager.createdEvent(any(Event.class))).willReturn(savedEvent);

        // 2. when
        EventResponseDTO response = eventService.createEvent(request);

        // 3. then
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("Title");
        verify(eventManager).createdEvent(any(Event.class));
    }

    @Test
    @DisplayName("전체 목록 조회 시 Manager 결과를 DTO 리스트로 변환한다.")
    void getAllEvents_Success() {

        // 1. given
        List<Event> events = List.of(
                Event.builder().eventId(1L).title("T1").build(),
                Event.builder().eventId(2L).title("T2").build()
        );

        given(eventManager.getAllEvents()).willReturn(events);

        // 2. when
        List<EventResponseDTO> responses = eventService.getAllEvents();

        // 3. then
        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).title()).isEqualTo("T1");
    }
}
