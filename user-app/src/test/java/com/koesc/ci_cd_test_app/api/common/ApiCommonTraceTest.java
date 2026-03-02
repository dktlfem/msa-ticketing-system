package com.koesc.ci_cd_test_app.api.common;

import com.koesc.ci_cd_test_app.api.controller.UserController;
import com.koesc.ci_cd_test_app.business.AiModelService;
import com.koesc.ci_cd_test_app.business.UserService;
import com.koesc.ci_cd_test_app.global.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
public class ApiCommonTraceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AiModelService aiModelService;

    @Test
    @WithMockUser
    @DisplayName("모든 API 응답에는 분산 추적을 위한 Trace ID가 포함되어야 한다.")
    void response_ShouldContainTraceId() throws Exception {
        // [Flow] Presentation Layer에서 공통 필터나 Interceptor가 Trace ID를 심는지 확인
        mockMvc.perform(get("/api/v1/users/1"))
                .andExpect(status().isOk())
                // MicroMeter Tracing이나 Custom Filter로 추가한 Trace ID 헤더 확인
                .andExpect(header().exists("X-Trace-Id"));
    }
}
