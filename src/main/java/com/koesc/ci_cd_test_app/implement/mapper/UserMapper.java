package com.koesc.ci_cd_test_app.implement.mapper;

import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.storage.entity.UserEntity;
import lombok.Builder;
import org.springframework.stereotype.Component;

/**
 * Mapper : Domain <-> Entity 사이의 변환만 전문적으로 수행
 *
 * 추후에 기존 데이터 수정(Update)을 할 때는 toEntity로 아예 새 객체를 만드는 게 아니라,
 * 기존 Entity에 값만 덮어씌우는 메서드가 필요
 */
@Component
public class UserMapper {

    // Entity -> Domain (Reader에서 사용)
    public User toDomain(UserEntity entity) {
        return User.builder()
                .id(entity.getId())
                .email(entity.getEmail())
                .name(entity.getName())
                .password(entity.getPassword())
                .point(entity.getPoint())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    // Domain -> Entity (Writer에서 사용 - 주로 Insert 용)
    public UserEntity toEntity(User user) {
        return UserEntity.builder()
                // ID는 DB가 자동 생성하므로 보통 넣지 않음 (수정이면 필요할 수 있음)
                .email(user.getEmail())
                .name(user.getName())
                .password(user.getPassword())
                .point(user.getPoint())
                // createdAt, updatedAt은 DB가 처리하거나 Entity 내부 로직으로 처리 !!이해 안됨!!
                .build();
    }
}
