package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.implement.mapper.UserMapper;
import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.*;

/**
 * 초기 : 평문 비밀번호로 DB에 저장이 잘 되는지 확인
 * 테스트 통과 추후 : UserService에서 암호화해서 User.create에 넘긴다.
 */

// JPA 관련 컴포넌트(Entity, Repository), 테스트시 H2 인메모리 DB를 자동으로 사용하여 테스트 진행
@DataJpaTest
@Import({UserWriter.class, UserMapper.class}) // 우리가 만든 Writer와 Mapper는 직접 주입해야 한다
public class UserWriterTest {

    @Autowired private UserWriter userWriter;
    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("유저를 저장하면 ID와 가입일이 자동으로 생성되어야 한다.")
    void save_success() {

        // 1. given
        // 도메인 객체를 DB에 넣으면, 알아서 Entity로 변환해주는지 테스트하기 위해 User.create 메서드 사용
        User newUser = User.create("test@toss.im", "민석", "encrypted_pw");

        // 2. when
        // userWriter.save() 잘 동작하는지 테스트
        // 여기서 도메인 객체를 저장해달라고 요청하면 Entity로 잘 변환해서 DB에 저장하는지 확인
        User savedUser = userWriter.save(newUser);

        // 3. then
        assertThat(savedUser.getId()).isNotNegative();
        assertThat(savedUser.getEmail()).isEqualTo("test@toss.im");
        assertThat(savedUser.getName()).isEqualTo("민석");
        assertThat(savedUser.getCreatedAt()).isNotNull();
        assertThat(savedUser.getPoint()).isEqualByComparingTo("0");
    }
}
