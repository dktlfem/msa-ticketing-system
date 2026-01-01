package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validator : 검증 전용, 단순한 null 체크가 아니라 DB를 뒤져봐야 알 수 있는 검증
 *
 * @Valid, @NotNull, @Email : 형식 검증은 DTO에서 검증 -> "이메일 형식이 맞아?", "빈 값 아니야?", "비밀번호 8자 이상이야?"
 * DB 조회, 로직 : 비즈니스 검증 -> "형식은 맞는데... 이미 가입된 이메일 아니야?", "탈퇴한 회원 아니야?"
 */

@Component
@RequiredArgsConstructor
public class UserValidator {

    // 검증은 DB를 직접 찔러보는 게 빠름
    private final UserRepository userRepository;

    /**
     * 1. 이메일 중복 검증
     * - 중복이면 예외를 던진다 (반환값 x)
     * - 통과하면 아무 일도 일어나지 않는다
     */
    public void validateEmail(String email) {
        if (userRepository.findByEmail(email).isPresent()) {
            throw new IllegalArgumentException("이미 가입된 이메일입니다.");
        }
    }

    /**
     * 2. 이름 금칙어 검사 (로직 필요) - @Valid로는 못하는 복잡한 검사
     * - 회원가입 또는 회원정보 수정 시 admin, 관리자가 포함되어있으면 예외를 던진다
     */
    public void validateName(String name) {
        if (name.contains("admin") || name.contains("관리자")) {
            throw new IllegalArgumentException("사용할 수 없는 이름입니다.");
        }
    }
}
