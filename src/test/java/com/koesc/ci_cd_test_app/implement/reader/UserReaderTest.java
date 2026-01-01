package com.koesc.ci_cd_test_app.implement.reader;

import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.implement.mapper.UserMapper;
import com.koesc.ci_cd_test_app.storage.entity.UserEntity;
import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;

@DataJpaTest
@Import({UserReader.class, UserMapper.class})
public class UserReaderTest {

    @Autowired private UserReader userReader;
    @Autowired private UserRepository userRepository; // 테스트 데이터 넣기용

    @Test
    @DisplayName("id를 통해 사용자를 조회할 수 있다.")
    void readById_success() {

        // 1. given
        UserEntity entity = UserEntity.builder()
                .email("id_test@toss.im") // 이메일 충돌 방지
                .name("최민석")
                .password("password")
                .point(BigDecimal.ZERO)
                .build();

        UserEntity savedEntity = userRepository.save(entity);

        System.out.println("생성된 ID : " + savedEntity.getId());

        // 2. when
        User user = userReader.read(savedEntity.getId());

        // 3. then
        assertThat(user).isNotNull();
        assertThat(user.getId()).isEqualTo(savedEntity.getId());
        assertThat(user.getName()).isEqualTo("최민석");
    }

    @Test
    @DisplayName("이메일로 사용자를 조회할 수 있다.")
    void readByEmail_success() {

        // 1. given
        // DB에 이미 저장된 데이터(Entity)가 있을 때, 이걸 꺼내서 도메인 객체로 잘 변환해서 가져오는지 테스트
        UserEntity entity = UserEntity.builder()
                .email("test@toss.im")
                .name("민석")
                .password("pw")
                .point(BigDecimal.ZERO)
                .build();

        UserEntity savedEntity = userRepository.save(entity);

        // 2. when (Reader로 데이터 조회)
        User user = userReader.readByEmail(savedEntity.getEmail());

        // 3. then
        assertThat(user.getName()).isEqualTo("민석");
    }
}
