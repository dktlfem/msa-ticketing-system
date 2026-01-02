package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.api.request.UserRequestDTO;
import com.koesc.ci_cd_test_app.api.response.UserResponseDTO;
import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.implement.manager.UserManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service : 트랜잭션의 경계를 설정하고, DTO를 도메인 모델로 변환하여 Manager에게 전달한다.
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserManager userManager;

    @Transactional
    public UserResponseDTO signUp(UserRequestDTO request) {

        // 1. RequestDTO -> Domain (도메인 모델의 정적 팩토리 메서드 사용)
        User user = User.create(request.email(), request.name(), request.password());

        // 2. Manager를 통해 비즈니스 프로세스 실행
        User savedUser = userManager.register(user);

        // 3. Domain -> ResponseDTO 변환 (간단한 변환은 여기서 수행)
        return new UserResponseDTO(
                savedUser.getId(),
                savedUser.getEmail(),
                savedUser.getName(),
                savedUser.getPoint()
        );
    }

    @Transactional(readOnly = true)
    public UserResponseDTO getInfo(Long userId) {
        User user = userManager.getUser(userId);
        return new UserResponseDTO(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getPoint()
        );
    }
}
