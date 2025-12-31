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

    /**
     * 사용자 생성 및 수정 (Create & Update)
     */
    public User save(User user) {
        // 1. Domain -> Entity 변환 (Mapper 역할)
        UserEntity entity = userMapper.toEntity(user);

        // 2. DB 저장 (Repository 역할)
        // save()는 ID가 없으면 Insert, 있으면 Update를 수행한다.
        UserEntity savedEntity = userRepository.save(entity);

        // 3. Entity -> Domain 변환 (Mapper 역할)
        // 저장되면서 생성된 ID나 createdAt 등의 최신 정보를 담아서 반환
        return userMapper.toDomain(savedEntity);
    }
}
