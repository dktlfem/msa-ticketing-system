package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.implement.reader.UserReader;
import com.koesc.ci_cd_test_app.implement.validator.UserValidator;
import com.koesc.ci_cd_test_app.implement.writer.UserWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Reader, Writer, Validator를 조립하는 역할
 * -> 따라서 비즈니스 로직(Service)이 어떻게 저장하고 검증하는지에 대한 세부 구현을 몰라도 되게끔 캡슐화
 */
@Component
@RequiredArgsConstructor
public class UserManager {

    private final UserReader userReader;
    private final UserWriter userWriter;
    private final UserValidator userValidator;

    /**
     * 회원 가입을 위한 구현체 조립
     * Validator로 검증하고 Writer로 저장
     */
    public User register(User user) {

        // 1. 이메일 중복 검증 (Validator)
        userValidator.validateEmail(user.getEmail());

        // 2. 이름 금칙어 검증 (Validator)
        userValidator.validateName(user.getName());

        // 3. 저장 (Writer)
        return userWriter.save(user);
    }

    /**
     * 사용자 조회
     * Reader를 통해 조회
     */
    public User getUser(Long userId) {
        return userReader.read(userId);
    }
}
