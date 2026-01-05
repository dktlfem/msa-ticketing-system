package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.api.request.UserRequestDTO;
import com.koesc.ci_cd_test_app.api.response.UserResponseDTO;
import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.implement.manager.UserManager;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService 비즈니스 흐름 테스트 (Mock)")
public class UserServiceTest {

    @Mock
    private UserManager userManager;

    @InjectMocks
    private UserService userService;

    @Test
    @DisplayName("회원가입 시 유저 정보를 Manager에게 전달하고 응답을 DTO로 변환한다.")
    void signUp_flow_success() {

        // 1. given
        UserRequestDTO request = new UserRequestDTO("test@toss.im", "최민석", "raw_password");
        User savedUser = User.builder()
                .id(1L)
                .email("test@toss.im")
                .name("최민석")
                .build();

        // Manager가 register를 호출하면 savedUser를 반환할 것이라고 가정
        given(userManager.register(any(User.class))).willReturn(savedUser);

        // 2. when
        UserResponseDTO response = userService.signUp(request);

        // 3. then
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("test@toss.im");
    }
}
