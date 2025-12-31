package com.koesc.ci_cd_test_app.storage.repository;

import com.koesc.ci_cd_test_app.storage.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// JpaRepository<엔터티 타입, PK 타입>
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    // 기본적인 save(), findById(), delete() 등은 이미 만들어져 있음

    // 커스텀 쿼리 메서드 (이름만 잘 지으면 SQL 자동 생성)
    // select * from users where email = ?
    Optional<UserEntity> findByEmail(String email);

    // 만약 복잡한 쿼리가 필요하면 @Query 사용 가능
    // @Query("SELECT u FROM UserEntity u WHERE u.point > :minPoint")
    // List<UserEntity> findRichUsers(BigDecimal minPoint);
}
