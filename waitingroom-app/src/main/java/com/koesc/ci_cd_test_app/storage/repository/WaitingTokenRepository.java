package com.koesc.ci_cd_test_app.storage.repository;

import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.storage.entity.WaitingTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
// Spring Data JPA @Query 바인딩에는 io.lettuce.core.dynamic.annotation.Param 대신 아래 임포트로 변경
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface WaitingTokenRepository extends JpaRepository<WaitingTokenEntity, String> {

    // 복합 인덱스(idx_user_event)를 타게 됨 -> 고속 조회 가능
    Optional<WaitingTokenEntity> findByUserIdAndEventId(Long userId, Long eventId);

    // 중복 진입 방지용 검증 메서드
    boolean existsByEventIdAndUserIdAndStatus(Long eventId, Long userId, WaitingTokenStatus status);

    /**
     * 벌크 삭제 (스케줄러용)
     * 사유: 대규모 트래픽 시 레코드를 하나씩 지우면 성능이 안 나옴. @Modifying을 이용해 쿼리 한 번으로 처리.
     */
    @Modifying
    @Query("DELETE FROM WaitingTokenEntity w WHERE w.expiredAt < :now OR w.status IN :statuses")
    void deleteByExpiredAtBeforeOrStatusIn(@Param("now")LocalDateTime now,
                                           @Param("statuses") List<WaitingTokenStatus> statuses);
}
