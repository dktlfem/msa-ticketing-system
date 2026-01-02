package com.koesc.ci_cd_test_app.implement.validator;

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

/**
 * assertThatThrownBy : 원하는 에러가 발생해야 성공
 * assertThatCode : 반대로 아무일도 없어야 성공
 */

@DataJpaTest
@Import({UserValidator.class})
public class UserValidatorTest {

    @Autowired private UserValidator userValidator;
    @Autowired private UserRepository userRepository; // 이메일 중복 테스트용

    @Test
    @DisplayName("이미 가입된 이메이면 예외가 발생한다.")
    void validateEmail_fail() {

        // 1. given
        UserEntity savedEntity = UserEntity.builder()
                .email("duplicate@toss.im")
                .name("기존유저")
                .password("pw")
                .point(BigDecimal.ZERO)
                .build();

        UserEntity userEntity = userRepository.save(savedEntity);

        // 2. when & then
        // duplicate@toss.im으로 검증 시 -> 예외 발생시 성공
        assertThatThrownBy(() -> userValidator.validateEmail("duplicate@toss.im"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 가입된 이메일입니다.");
    }

    @Test
    @DisplayName("가입되지 않은 이메일이면 통과한다.")
    void validateEmail_success() {

        // 1. given
        String newEmail = "new@toss.im";

        // 2. when & then
        // 예외가 발생하지 않아야 함
        assertThatCode(() -> userValidator.validateEmail(newEmail))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이름에 'admin'이 포함되면 예외가 발생한다.")
    void validateName_fail_admin() {

        // 1. given
        String forbiddenName = "super_admin";

        // 2. when & then
        assertThatThrownBy(() -> userValidator.validateName(forbiddenName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용할 수 없는 이름입니다.");
    }

    @Test
    @DisplayName("이름에 '관리자'가 포함되면 예외가 발생한다.")
    void validateName_fail_관리자() {

        // 1. given
        String forbiddenName = "시스템관리자";


        // 2. when & then
        assertThatThrownBy(() -> userValidator.validateName(forbiddenName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("사용할 수 없는 이름입니다.");
    }

    @Test
    @DisplayName("회원가입시 필수값을 누락하면 예외가 발생한다.")
    void validateValueRequired_fail() {

        // 1. given
        // 이름이 null을 가정
        String invalidName = null;

        // 2. when & then
        assertThatThrownBy(() -> userValidator.validateName(invalidName))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("이름은 필수입니다.");
    }

    @Test
    @DisplayName("이름이 빈 값이거나 공백이면 예외가 발생한다.")
    void validateName_EmptyOrBlank_fail() {

        // 1. given
        String emptyName = "";
        String blankName = "   ";

        // 2. when & then
        assertThatThrownBy(() -> userValidator.validateName(emptyName))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> userValidator.validateName(blankName))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("금칙어가 없는 정상적인 이름은 통과한다.")
    void validateName_success() {

        // 1. given
        String validName = "최민석";

        // 2. when & then
        assertThatCode(() -> userValidator.validateName(validName))
                .doesNotThrowAnyException();
    }
}
