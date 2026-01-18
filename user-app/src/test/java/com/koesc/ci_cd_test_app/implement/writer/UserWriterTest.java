package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.implement.mapper.UserMapper;
import com.koesc.ci_cd_test_app.storage.entity.UserEntity;
import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * 초기 : 평문 비밀번호로 DB에 저장이 잘 되는지 확인
 * 테스트 통과 추후 : UserService에서 암호화해서 User.create에 넘긴다.
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("UserWriter 단위 테스트 (Mock)")
public class UserWriterTest {

    @Mock
    private UserRepository userRepository;

    @Spy
    private UserMapper userMapper;

    @InjectMocks
    private UserWriter userWriter;

    @Test
    @DisplayName("유저를 생성하면 저장된 도메인 객체를 반환한다.")
    void save_success() {

        // 1. given
        User newUser = User.create("test@toss.im", "민석", "pw");

        UserEntity savedEntity = UserEntity.builder()
                .id(1L)
                .email("test@toss.im")
                .name("민석")
                .build();

        given(userRepository.save(any(UserEntity.class))).willReturn(savedEntity);

        // 2. when
        User result = userWriter.create(newUser);

        // 3. then
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("민석");
    }
}
