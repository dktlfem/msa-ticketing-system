package com.koesc.ci_cd_test_app.domain.user.repository;

import com.koesc.ci_cd_test_app.domain.user.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Boolean existsByUsername(String username);

    // Custom Query : 회원 정보 수정시 자체 로그인 여부, 잠김 여부를 확인해야 한다.
    Optional<UserEntity> findByUsernameAndIsLockAndIsSocial(String username, Boolean isLock, Boolean isSocial);
}
