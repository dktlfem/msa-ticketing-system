package com.koesc.ci_cd_test_app.api.controller;

import com.koesc.ci_cd_test_app.business.EventScheduleService;
import com.koesc.ci_cd_test_app.domain.eventschedule.BookableReason;
import com.koesc.ci_cd_test_app.domain.eventschedule.BookableStatus;
import com.koesc.ci_cd_test_app.domain.eventschedule.EventSchedule;
import com.koesc.ci_cd_test_app.domain.eventschedule.EventScheduleDetail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.data.domain.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 목표 1. Controller가 응답 JSON을 PageResponse 스펙대로 만들었는지
 * 테스트로 보장 1. Controller가 /api/v1/events/{eventId}/schedules 요청을 받으면
 *               응답이 PageResponse 형식(content/page/sort)으로 나온다.
 *
 * 목표 2. Controller가 Service 호출할 때 Pageable에 기본 정렬(startTime, scheduleId)을 제대로 넣었는지
 * 테스트로 보장 2. Controller가 Service 호출할 때 pageable에 startTime, scheduleId 정렬이 포함되어 전달된다.
 *               (즉, 누가 tie-breaker를 빼도 테스트가 바로 터짐)
 */

@WebMvcTest(EventScheduleController.class)
public class EventScheduleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventScheduleService eventScheduleService;

    @Test
    @DisplayName("목록 조회 : PageResponse(concert/page/sort) 계약이 깨지지 않는다 + 기본 정렬(startTime, scheduleId)이 적용된다.")
    void list_contract_and_defaultSort() throws Exception {

        // 1. given
        Long eventId = 10L;
        LocalDateTime time = LocalDateTime.of(2030, 1, 1, 10, 0);

        EventSchedule schedule1 = EventSchedule.builder()
                .scheduleId(1L).eventId(eventId).startTime(time).totalSeats(100).createdAt(time.minusDays(1))
                .build();

        EventSchedule schedule2 = EventSchedule.builder()
                .scheduleId(2L).eventId(eventId).startTime(time).totalSeats(100).createdAt(time.minusDays(1))
                .build();

        Pageable returnPage = PageRequest.of(0, 20,
                Sort.by(Sort.Order.asc("startTime"), Sort.Order.asc("scheduleId")));

        Page<EventSchedule> page = new PageImpl<>(List.of(schedule1, schedule2), returnPage, 5);

        // 가짜 동작(Stub) : Controller가 Service를 호출하면 위 returnPage를 돌려주는 작업 수행
        when(eventScheduleService.getEventSchedules(eq(eventId), any(Pageable.class)))
                .thenReturn(page);

        // 2. when
        // 실제 HTTP 요청을 보낸 것처럼 MockMvc로 Controller를 실행시키기
        mockMvc.perform(get("/api/v1/events/{eventId}/schedules", eventId))
                .andExpect(status().isOk())
                // content 구조
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].scheduleId").value(1))
                .andExpect(jsonPath("$.content[0].eventId").value(10))
                // page 구조
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(20))
                .andExpect(jsonPath("$.page.totalElements").value(2))
                // sort구조
                .andExpect(jsonPath("$.sort.length()").value(2))
                .andExpect(jsonPath("$.sort[0].property").value("startTime"))
                .andExpect(jsonPath("$.sort[0].direction").value("ASC"))
                .andExpect(jsonPath("$.sort[1].property").value("scheduleId"))
                .andExpect(jsonPath("$.sort[1].direction").value("ASC"));

        // Controller가 service를 호출할 때 Pageable이 제대로 들어갔는지 확인(타이브레이커 포함)
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(eventScheduleService).getEventSchedules(eq(eventId), captor.capture());


        // 3. then
        Pageable passed = captor.getValue();
        assertThat(passed.getPageNumber()).isEqualTo(0);
        assertThat(passed.getPageSize()).isEqualTo(20);
        assertThat(passed.getSort().getOrderFor("startTime")).isNotNull();
        assertThat(passed.getSort().getOrderFor("scheduleId")).isNotNull();
        assertThat(passed.getSort().getOrderFor("startTime").getDirection()).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("상세 조회 : bookable 계산 결과가 응답에 포함된다.")
    void detail_contains_bookable() throws Exception {

        // 1. given
        Long scheduledId = 99L;
        LocalDateTime time = LocalDateTime.of(2030, 1, 1, 10, 0);

        EventSchedule schedule = EventSchedule.builder()
                .scheduleId(scheduledId).eventId(10L).startTime(time).totalSeats(100).createdAt(time.minusDays(1))
                .build();

        BookableStatus bookableStatus = new BookableStatus(
                true, BookableReason.BOOKABLE, "예매 가능"
        );

        when(eventScheduleService.getEventScheduleDetail(scheduledId))
                .thenReturn(new EventScheduleDetail(schedule, bookableStatus));

        mockMvc.perform(get("/api/v1/schedules/{scheduleId}", scheduledId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(99))
                .andExpect(jsonPath("$.bookable").value(true))
                .andExpect(jsonPath("$.bookableCode").value("BOOKABLE"))
                .andExpect(jsonPath("$.bookableMessage").value("예매 가능"));
    }
}
