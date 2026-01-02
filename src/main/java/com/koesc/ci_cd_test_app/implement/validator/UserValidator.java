package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Validator : 검증 전용, 단순한 null 체크가 아니라 DB를 뒤져봐야 알 수 있는 검증
 *
 * @Valid, @NotNull, @Email 어노테이션으로 DTO에서 1차 검증하지만,
 * Implement Layer인 Validator에서 한 번 더 로직으로 검증해주는 것이 안전하다.
 * -> Why? : 나중에 API가 아닌 내부의 다른 경(예: 배치 작업, 관리자 기능 등)을 통해 UserValidator가 호출될 때,
 *           DTO 검증 거치지 않은 데이터가 들어올 수도 있기 때문이다.
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

        // 1. 필수값(null 또는 공백) 체크 추가
        // isBlank(): 문자열이 비어있거나 공백 있는지만 체크한다
        // isBlank()는 isEmpty() 상위 호환

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("이름은 필수입니다.");
        }

        // 2. 비즈니스 금칙어
        if (name.contains("admin") || name.contains("관리자")) {
            throw new IllegalArgumentException("사용할 수 없는 이름입니다.");
        }
    }
}
