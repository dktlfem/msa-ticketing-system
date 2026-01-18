package com.koesc.ci_cd_test_app.implement.writer;

import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.implement.mapper.UserMapper;
import com.koesc.ci_cd_test_app.storage.entity.UserEntity;
import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Writer : 데이터를 저장, 수정, 삭제하는 것만 전문적으로 수행
 */

@Component
@RequiredArgsConstructor
public class UserWriter {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    // 생성 전용
    public User create(User user) {
        UserEntity entity = userMapper.toEntity(user);
        return userMapper.toDomain(userRepository.save(entity));
    }

    // 수정 전용 (Dirty Checking 활용)
    public void update(User user, UserEntity entity) {
        userMapper.updateEntityFromDomain(user, entity);
        // 여기서 userRepository.save()를 호출하지 않아도
        // @Transactional이 걸린 Service 계층에서 종료 시점에 자동으로 Update 쿼리 발생
    }

}
