package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.implement.mapper.UserMapper;
import com.koesc.ci_cd_test_app.storage.entity.UserEntity;
import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserReader 단위 테스트 (Mock)")
public class UserReaderTest {

    @Mock
    private UserRepository userRepository;

    @Spy // Mapper는 단순 변환 로직이므로 실제 로직을 사용하도록 설정
    private UserMapper userMapper;

    @InjectMocks
    private UserReader userReader;

    @Test
    @DisplayName("id를 통해 사용자를 조회할 수 있다.")
    void readById_success() {

        // 1. given
        Long userId = 1L;
        UserEntity entity = UserEntity.builder()
                .id(userId)
                .email("test@toss.im")
                .name("최민석")
                .build();

        given(userRepository.findById(userId)).willReturn(Optional.of(entity));

        // 2. when
        User user = userReader.read(userId);

        // 3. then
        assertThat(user.getId()).isEqualTo(userId);
        assertThat(user.getName()).isEqualTo("최민석");
    }

    @Test
    @DisplayName("이메일로 사용자를 조회할 수 있다.")
    void readByEmail_success1() {

        // 1. given
        String email = "test@toss.im";
        UserEntity entity = UserEntity.builder()
                .id(1L)
                .email(email)
                .name("최민석")
                .password("test1234")
                .build();

        // Repository가 해당 이메일로 조회했을 때 Optional<UserEntity>를 반환하도록 설정
        given(userRepository.findByEmail(email)).willReturn(Optional.of(entity));

        // 2. when
        User user = userReader.readByEmail(email);

        // 3. then
        assertThat(user).isNotNull();
        assertThat(user.getEmail()).isEqualTo(email);
        assertThat(user.getName()).isEqualTo("최민석");

        // Mapper가 필드 누락 없이 잘 변환했는지 체크
        assertThat(user.getId()).isEqualTo(1L);
    }
}
