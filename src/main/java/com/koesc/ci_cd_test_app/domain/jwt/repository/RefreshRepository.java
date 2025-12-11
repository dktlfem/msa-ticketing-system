package com.koesc.ci_cd_test_app.domain.jwt.repository;

import com.koesc.ci_cd_test_app.domain.jwt.entity.RefreshEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface RefreshRepository extends JpaRepository<RefreshEntity, Long> {

    // Custom Query : refreshToken 존재 여부
    Boolean existsByRefresh(String refreshToken);

    // Custom Query : refreshToken 삭제
    @Transactional
    void deleteByRefresh(String refresh);

    // Custom Query : 특정 username으로 관련된 refreshToken 기반 삭제 (회원탈퇴)
    @Transactional
    void deleteByUsername(String username);
}
