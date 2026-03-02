package com.koesc.ci_cd_test_app.storage.repository;

import com.koesc.ci_cd_test_app.storage.entity.EventScheduleEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EventScheduleRepository extends JpaRepository<EventScheduleEntity, Long> {

    // 페이징 처리
    Page<EventScheduleEntity> findByEventId(Long eventId, Pageable pageable);
}
