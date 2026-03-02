package com.koesc.ci_cd_test_app.integration.eventschedule;

import com.koesc.ci_cd_test_app.AbstractIntegrationTest;
import com.koesc.ci_cd_test_app.storage.entity.EventScheduleEntity;
import com.koesc.ci_cd_test_app.storage.repository.EventScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.LocalDateTime;

import static com.koesc.ci_cd_test_app.global.error.ErrorCode.EVENT_SCHEDULE_NOT_FOUND;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DB + JPA + Controller + Validator까지 실제로 엮였을 때 상세조회가 진짜로 맞게 동작하는지 통합테스트
 *
 * 목표 1. 존재하는 scheduleId면 200 + 응답 데이터 정확
 *  scheduleId/eventId/startTime/totalSeats/createdAt이 내려오는지
 *  bookable이 Clock 기준으로 계산돼서 내려오는지
 *
 * 목표 2. startTime이 과거면 bookable=false로 내려오는지
 *  "상세조회 + 정책 계산"이 통합적으로 맞는지
 *
 * 목표 3. 없는 scheduleId면 에러 스펙이 맞는지 (중요)
 *  status code가 404인지(또는 ErrorCode에 맞는 코드)
 *  ErrorResponse 구조가 기대한 형태인지(code/message 등)
 */
public class EventScheduleDetailIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventScheduleRepository repository;

    @Autowired
    private Clock clock; // TestClockConfig에서 주입되는 Clock

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("상세 조회: 시작 시간이 미래면 bookable=true가 내려온다(실제 DB + 실제 Validator까지 통합)")
    void detail_bookable_true_when_future() throws Exception {
        LocalDateTime now = LocalDateTime.now(clock);

        EventScheduleEntity saved = repository.save(EventScheduleEntity.builder()
                .eventId(10L)
                .startTime(now.plusHours(1))
                .totalSeats(100)
                .build());

        mockMvc.perform(get("/api/v1/schedules/{scheduleId}", saved.getScheduleId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduleId").value(saved.getScheduleId()))
                .andExpect(jsonPath("$.eventId").value(10))
                .andExpect(jsonPath("$.bookable").value(true));
    }

    @Test
    @DisplayName("상세 조회: 시작 시간이 과거면 bookable=false가 내려온다")
    void detail_bookable_false_when_past() throws Exception{
        LocalDateTime now = LocalDateTime.now(clock); // 2026-02-09T12:00:00(서울 기준)으로 고정

        EventScheduleEntity saved = repository.save(EventScheduleEntity.builder()
                .eventId(10L)
                .startTime(now.minusMinutes(1))
                .totalSeats(100)
                .build());

        mockMvc.perform(get("/api/v1/schedules/{scheduleId}", saved.getScheduleId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookable").value(false));
    }

    @Test
    @DisplayName("상세 조회: 존재하지 않는 scheduleId면 에러 응답으로 떨어진다(운영에서 가장 흔한 케이스)")
    void detail_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/schedules/{scheduleId}", 999999L))
                // 여기 status는 GlobalExceptionHandler 정책에 맞춰 조정
                .andExpect(status().isNotFound())
                // ErrorResponse 필드명에 맞춤
                .andExpect(jsonPath("$.code").value(EVENT_SCHEDULE_NOT_FOUND.getCode())) // "ES001"
                .andExpect(jsonPath("$.status").value(EVENT_SCHEDULE_NOT_FOUND.getHttpStatus().value())) // 404
                .andExpect(jsonPath("$.message").exists());
    }
}
