package com.koesc.ci_cd_test_app.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.koesc.ci_cd_test_app.api.request.UserRequestDTO;
import com.koesc.ci_cd_test_app.api.response.UserResponseDTO;
import com.koesc.ci_cd_test_app.business.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

/**
 * @MockMVc : 가상의 브라우저가 가상의 서버에게 요청을 보내는 상황을 시뮬레이션해서 테스트 함.
 */
@WebMvcTest(UserController.class) // 다른 테스트는 필요없고, 오직 UserController만 테스트 함.
public class UserControllerTest {

    @Autowired
    private MockMvc mockMvc; // 가상의 브라우저, 실제로 네트워크를 타지 않고 HTTP 요청을 컨트롤러에 날림.

    @Autowired
    private ObjectMapper objectMapper;

    // 스프링부트 v3.4.0부터 @MockBean -> @MockitoBean으로 이름 변경 (기능은 동일)
    @MockitoBean
    private UserService userService;

    @Test
    @DisplayName("회원가입 요청 시 유효한 입력이면 200 OK를 반환한다")
    void signup_success() throws Exception {

        // 1. given
        UserRequestDTO request = new UserRequestDTO("newuser@toss.im", "최민석", "password123!");
        UserResponseDTO response = new UserResponseDTO(1L, "newuser@toss.im", "최민석", BigDecimal.ZERO);

        given(userService.signUp(any(UserRequestDTO.class))).willReturn(response);

        // 2. when & then
        mockMvc.perform(post("/api/v1/users/signup") // 1. 이 주소로 POST 요청을 보냄
                        .content(objectMapper.writeValueAsString(request)) // 2. 요청 본문(Body)에 아까 만든 JSON 데이터를 담음
                        .contentType(MediaType.APPLICATION_JSON)) // 3. 이 데이터 형식은 JSON 형식임
                .andDo(print()) // 4. 결과가 어떻게 나왔는지 콘솔로 출력
                .andExpect(status().isOk()) // 5. 응답 코드가 200 OK인지 확인
                .andExpect(jsonPath("$.id").value(1L)) // 6. 응답 결과(JSON) 중에서 "id" 값이 1인지 확인
                .andExpect(jsonPath("$.email").value("newuser@toss.im"))
                .andExpect(jsonPath("$.name").value("최민석"));

    }

    @Test
    @DisplayName("회원가입 요청 시 이메일 형식이 잘못되면 400 Bad Request를 반환한다")
    void signup_fail_invalid_email() throws Exception {

        // 1. given
        UserRequestDTO invalidRequest = new UserRequestDTO("invalid-email", "최민석", "password123!");

        // 2. when & then
        mockMvc.perform(post("/api/v1/users/signup")
                        .content(objectMapper.writeValueAsString(invalidRequest))
                        .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isBadRequest()); // @Valid에 의해 차단됨
    }

    @Test
    @DisplayName("사용자 ID로 정보를 조회하면 200 OK와 사용자 정보를 반환한다")
    void getInfo_success() throws Exception {

        // 1. given
        Long userId = 1L;
        UserResponseDTO response = new UserResponseDTO(userId, "test@toss.im", "최민석", BigDecimal.valueOf(1000));

        given(userService.getInfo(userId)).willReturn(response);

        // 2. when & then
        mockMvc.perform(get("/api/v1/users/{userId}", userId))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(userId))
                .andExpect(jsonPath("$.point").value(1000));
    }

}
