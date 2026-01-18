package com.koesc.ci_cd_test_app.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koesc.ci_cd_test_app.api.request.WaitingRoomRequestDTO;
import com.koesc.ci_cd_test_app.business.WaitingRoomService;
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

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * @AutoConfigureMockMvc(addFilters = false) : 보안 필터 체인 자체를 타지 않음
 * @Import(SecurityConfig.class) & @WithMockUser : 가짜 인증 유저를 생성하여 .authenticated() 통과
 */
@WebMvcTest(WaitingRoomController.class)
@Import(SecurityConfig.class) // 1. 작성한 Security 설정(CSRF off 등)을 테스트에 적용
public class WaitingRoomControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WaitingRoomService waitingRoomService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @WithMockUser
    @DisplayName("대기열 진입 API 호출 시 정상적으로 200 OK와 순번을 반환한다.")
    void joinWaitingRoom_ReturnOk() throws Exception {

        // 1. given
        WaitingRoomRequestDTO request = new WaitingRoomRequestDTO(1L, 100L);
        given(waitingRoomService.joinQueue(1L, 100L)).willReturn(50L);

        // 2. when & then
        mockMvc.perform(post("/api/v1/waiting-room/join")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rank").value(50))
                .andExpect(jsonPath("$.isAllowed").value(false));
    }
}
