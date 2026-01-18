package com.koesc.ci_cd_test_app.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koesc.ci_cd_test_app.api.request.EventRequestDTO;
import com.koesc.ci_cd_test_app.api.response.EventResponseDTO;
import com.koesc.ci_cd_test_app.business.EventService;
import com.koesc.ci_cd_test_app.global.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventController.class)
@Import(SecurityConfig.class) // 1. 작성한 Security 설정(CSRF off 등)을 테스트에 적용
public class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EventService eventService;

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN") // 2. 이 테스트를 수행하기 위해 가짜 사용자를 주입 (인증 통과)
    @DisplayName("공연 등록 성공 시 200 OK와 생성된 정보를 반환한다.")
    void createEvent_Success() throws Exception {

        // 1. given
        EventRequestDTO request = new EventRequestDTO("콘서트", "설명", "url");
        EventResponseDTO response = new EventResponseDTO(1L, "콘서트", "설명", "url", LocalDateTime.now(), LocalDateTime.now());

        given(eventService.createEvent(any(EventRequestDTO.class))).willReturn(response);

        // 2. when & then
        mockMvc.perform(post("/api/v1/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(request)))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.title").value("콘서트"));
    }

    @Test
    @WithMockUser // 기본값(user/User)으로 인증 통과
    @DisplayName("공연 등록 시 필수 값이 누락되면 400 Bad Request를 반환한다.")
    void createEvent_Validation_Fail() throws Exception {

        // 1. given
        EventRequestDTO invalidRequest = new EventRequestDTO("", "", "");// 제목, 설명, 공연 url 빈 값

        // 2. when & then
        mockMvc.perform(post("/api/v1/events")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(invalidRequest)))
                .andDo(print())
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser // 인증 통과
    @DisplayName("공연 상세 조회 성공 시 200 OK를 반환한다.")
    void getEvent_Success() throws Exception {

        // 1. given
        Long eventId = 1L;
        EventResponseDTO response = new EventResponseDTO(eventId, "콘서트", "설명", "url", LocalDateTime.now(), LocalDateTime.now());

        given(eventService.getEventDetails(eventId)).willReturn(response);

        // 2. when & then
        mockMvc.perform(get("/api/v1/events/{eventId}", eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(eventId));
    }

    @Test
    @WithMockUser // 인증 통과
    @DisplayName("전체 공연 목록 조회 성공 시 200 OK와 리스트를 반환한다.")
    void getEvents_Success() throws Exception {

        // 1. given
        List<EventResponseDTO> responseList = List.of(
                new EventResponseDTO(1L, "A", "desc", "url", LocalDateTime.now(), LocalDateTime.now()),
                new EventResponseDTO(2L, "B", "desc", "url", LocalDateTime.now(), LocalDateTime.now())
        );

        given(eventService.getAllEvents()).willReturn(responseList);

        // 2. when & then
        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }
}
