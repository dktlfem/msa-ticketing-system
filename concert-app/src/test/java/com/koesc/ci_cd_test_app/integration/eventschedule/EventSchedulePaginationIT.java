package com.koesc.ci_cd_test_app.integration.eventschedule;

import com.koesc.ci_cd_test_app.AbstractIntegrationTest;
import com.koesc.ci_cd_test_app.storage.entity.EventScheduleEntity;
import com.koesc.ci_cd_test_app.storage.repository.EventScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * DB에서 정렬 + 페이징이 진짜로 안정적으로 동작하는지 본다.
 *
 * 페이징 장애 : 페이지 넘겼는데 중복/누락 발생, 정렬이 흔들려서 사용자가 데이터를 못 봄 등등
 *
 * 목표 1. PagerResponse 형태가 유지되는지
 *  content/page/sort가 내려오는지
 *
 * 목표 2. eventId 필터링이 맞는지
 *  eventId=10으로 요청했는데 eventId=11 데이터가 섞이면 큰일
 *
 * 목표 3. 정렬이 안정적으로 적용되는지 (핵심)
 *  startTime이 같은 데이터 2개 이상을 넣어 "동점 상황" 만들기
 *  응답에서 그 둘이 scheduleId 오름차순으로 나오는지 확인
 *
 * 목표 4. 페이지 이동 시 중복/누락이 없는지
 *  page=0, size=2로 받은 scheduleId 집합과
 *  page=1, size=2로 받은 scheduleId 집합이 겹치지 않는지
 *  totalElements/totalPages가 맞는지
 *
 * Step 1) DB에 데이터 심기(Seed)
 * - eventId=10에 회차 5개 넣기
 * - 그 중 2개는 startTime을 똑같이 만들기(동점 상황 만들기: tie-breaker 검증)
 * - eventId=11에도 데이터 1개 넣어서 "필터링" 되는지 확인
 *
 * Step 2) API 호출
 * GET /api/v1/events/10/schedules?page=0&size=2%sort=startTime,asc&sort=scheduleId,asc
 *
 * Step 3) JSON 검증
 * - $.content : 길이가 2인지
 * - $.page.number : 0인지, $.page.size : 2인지
 * - $.sort에 startTime, scheduleId가 들어있는지
 *
 * - 제일 중요 : 동점자(startTime 같은 애들)가 scheduleId 오름차순인지
 */

public class EventSchedulePaginationIT extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private EventScheduleRepository repository;

    @BeforeEach
    void cleanDatabase() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("목록 조회: PageResponse(content/page/sort) + eventId 필터 + 정렬 안정성(startTime 동일 시 scheduleId 오름차순) + 페이지 메타 검증")
    void pagination_contract_sort_stable() throws Exception {
        Long eventId = 10L;

        // startTime 동점 상황 만들기(정렬 안정성 테스트 핵심)
        LocalDateTime t10 = LocalDateTime.of(2030, 1, 1, 10, 0);
        LocalDateTime t11 = LocalDateTime.of(2030, 1, 1, 11, 0);
        LocalDateTime t12 = LocalDateTime.of(2030, 1, 1, 12, 0);

        // eventId = 10 데이터 5개
        EventScheduleEntity a = repository.save(EventScheduleEntity.builder().eventId(eventId).startTime(t10).totalSeats(100).build());
        EventScheduleEntity b = repository.save(EventScheduleEntity.builder().eventId(eventId).startTime(t10).totalSeats(100).build()); // startTime 동점
        EventScheduleEntity c = repository.save(EventScheduleEntity.builder().eventId(eventId).startTime(t11).totalSeats(100).build());
        EventScheduleEntity d = repository.save(EventScheduleEntity.builder().eventId(eventId).startTime(t12).totalSeats(100).build());
        EventScheduleEntity e = repository.save(EventScheduleEntity.builder().eventId(eventId).startTime(t12).totalSeats(100).build());

        long minId = Math.min(a.getScheduleId(), b.getScheduleId());
        long maxId = Math.max(a.getScheduleId(), b.getScheduleId());

        int first = Math.toIntExact(Math.min(minId, maxId));
        int second = Math.toIntExact(Math.min(minId, maxId));

        // 다른 eventId 데이터도 섞어 넣어서 필터링 확인
        repository.save(EventScheduleEntity.builder().eventId(11L).startTime(t10).totalSeats(100).build());

        // page = 0, size = 2 (정렬 파라미터를 주지 않아서 Controller의 @PageableDefault가 적용되는지 확인)
        // URL: /api/v1/events/10/schedules/page=0&size=2
        mockMvc.perform(get("/api/v1/events/{eventId}/schedules", eventId)
                    .param("page", "0")
                    .param("size", "2"))
                .andExpect(status().isOk())
                // 외부 API 스펙: content/page/sort 존재
                .andExpect(jsonPath("$.content").exists())
                .andExpect(jsonPath("$.page").exists())
                .andExpect(jsonPath("$.sort").exists())

                // 페이지 메타 검증: eventId=10 데이터 5개여야 함
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$.page.size").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(3))
                .andExpect(jsonPath("$.page.first").value(true))
                .andExpect(jsonPath("$.page.last").value(false))

                // content 길이
                .andExpect(jsonPath("$.content.length()").value(2))

                // 정렬 안정성(동점자 처리):
                // startTime이 같은 a,b가 맨 앞에 와야하고, scheduleId 오름차순이어야 함
                .andExpect(jsonPath("$.sort[0].property").value("startTime"))
                .andExpect(jsonPath("$.sort[1].property").value("scheduleId"))

                // 실제 데이터 순서 검증
                .andExpect(jsonPath("$.content[0].scheduleId").value(Math.toIntExact(minId)))
                .andExpect(jsonPath("$.content[1].scheduleId").value(Math.toIntExact(maxId)));

        // page = 1도 호출해서 중복/누락이 없는지 체크 (겹치면 사고)
        mockMvc.perform(get("/api/v1/events/{eventId}/schedules", eventId)
                .param("page", "1")
                .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                // 두 번째 페이지 첫 데이터는 c(11시)여야 자연스러운 흐름
                .andExpect(jsonPath("$.content[0].scheduleId").value(c.getScheduleId()));

    }
}
