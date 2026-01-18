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
     * 회원 가입 (Create)
     * Validator로 검증하고 Writer로 저장
     */
    public User register(User user) {

        // 1. 이메일 중복 검증 (Validator)
        userValidator.validateEmail(user.getEmail());

        // 2. 이름 금칙어 검증 (Validator)
        userValidator.validateName(user.getName());

        // 3. 저장 (Writer)
        return userWriter.create(user);
    }

    /**
     * 사용자 정보 및 포인트 수정 (Update)
     * Dirty Checking을 활용하여 별도의 save 호출 없이 상태를 변경
     */
    /*public void update(User user) {
        // 1. 기존 엔티티를 영속성 컨텍스트에 로드 (Reader 활용)
        // UserWriter.update가 엔티티를 필요로 하므로, 여기서 Reader를 통해 가져옴
        UserEntity entity = userReader.readEntity(user.getId());

        // 2. 비즈니스 검증 (수정 시에도 이름 정책 등 검증 필요할 수 있음)
        userValidator.validateName(user.getName());

        // 3. 변경 감지(Dirty Checking)를 통한 업데이트
        userWriter.update(user, entity);
    }*/

    /**
     * 단일 사용자 조회 (Read)
     */
    public User getUser(Long userId) {
        return userReader.read(userId);
    }

    public User getUserByEmail(String email) {
        return userReader.readByEmail(email);
    }
}
