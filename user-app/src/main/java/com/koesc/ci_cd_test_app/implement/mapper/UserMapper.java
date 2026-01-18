package com.koesc.ci_cd_test_app.implement.mapper;

import com.koesc.ci_cd_test_app.domain.User;
import com.koesc.ci_cd_test_app.storage.entity.UserEntity;
import org.springframework.stereotype.Component;

/**
 * Mapper : Domain <-> Entity 사이의 변환만 전문적으로 수행
 *
 * 추후에 기존 데이터 수정(Update)을 할 때는 toEntity로 아예 새 객체를 만드는 게 아니라,
 * 기존 Entity에 값만 덮어씌우는 메서드가 필요
 */
@Component
public class UserMapper {

    // Entity -> Domain (Reader)
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

    // 신규 생성용 (Insert)
    public UserEntity toEntity(User user) {
        return UserEntity.builder()
                .email(user.getEmail())
                .name(user.getName())
                .password(user.getPassword())
                .point(user.getPoint())
                .build();
    }

    // 기존 엔티티 수정용 (Update) - Dirty Checking 활용
    public void updateEntityFromDomain(User user, UserEntity entity) {
        // ID나 Email은 변경 불가가 원칙인 경우가 많음
        entity.updateInfo(user.getName(), user.getPassword());
        entity.updatePoint(user.getPoint());
    }
}