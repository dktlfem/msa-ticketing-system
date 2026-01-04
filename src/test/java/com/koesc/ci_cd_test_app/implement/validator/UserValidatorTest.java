package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

/**
 * assertThatThrownBy : 원하는 에러가 발생해야 성공
 * assertThatCode : 반대로 아무일도 없어야 성공
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("UserValidator 단위 테스트 (Mock)")
public class UserValidatorTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserValidator userValidator;

    @Test
    @DisplayName("이미 가입된 이메일이면 예외가 발생한다.")
    void validateEmail_fail() {

        // 1. given
        String email = "duplicate@toss.im";
        given(userRepository.existsByEmail(email)).willReturn(true); // DB에 있다고 가정

        // 2. when & then
        assertThatThrownBy(() -> userValidator.validateEmail(email))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 가입된 이메일입니다.");
    }

    @Test
    @DisplayName("가입되지 않은 이메일이면 통과한다.")
    void validateEmail_success() {

        // 1. given
        String newEmail = "new@toss.im";
        given(userRepository.existsByEmail(newEmail)).willReturn(false); // DB에 없다고 가정

        // 2. when & then
        assertThatCode(() -> userValidator.validateEmail(newEmail))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("이름에 'admin'이 포함되면 예외가 발생한다.")
    void validateName_fail_admin() {

        // 1. given
        String adminName = "super_admin";

        // 2. when & then
        assertThatThrownBy(() -> userValidator.validateName(adminName))
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
    @DisplayName("금칙어가 없는 정상적인 이름은 통과한다.")
    void validateName_success() {

        // 1. given
        String validName = "최민석";

        // 2. when & then
        assertThatCode(() -> userValidator.validateName(validName))
                .doesNotThrowAnyException();
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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이름은 필수입니다.");
    }
}
