package com.koesc.ci_cd_test_app.storage.repository;

import com.koesc.ci_cd_test_app.storage.entity.EventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EventRepository extends JpaRepository<EventEntity, Long> {

    // 기본적인 save(), findById(), delete() 등은 이미 만들어져 있음
    // 필요한 경우 QueryDSL이나 커스텀 쿼리 추가 예정

    // 제목으로 존재 여부 확인
    boolean existsByTitle(String title);
}
