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

    // ADR: findByUserIdAndEventId → findFirst...AndStatus 변경
    // 사유: 같은 userId+eventId로 USED/EXPIRED 토큰이 복수 존재할 때
    //       Optional 반환 쿼리가 NonUniqueResultException 발생.
    //       createToken()에서 ACTIVE 토큰 재사용 여부만 확인하므로
    //       status=ACTIVE 필터 + findFirst로 안전하게 단일 결과 보장.
    Optional<WaitingTokenEntity> findFirstByUserIdAndEventIdAndStatusOrderByIssuedAtDesc(
            Long userId, Long eventId, WaitingTokenStatus status);

    // 중복 진입 방지용 검증 메서드
    boolean existsByEventIdAndUserIdAndStatus(Long eventId, Long userId, WaitingTokenStatus status);

    /**
     * 벌크 삭제 (스케줄러용)
     * 사유: 대규모 트래픽 시 레코드를 하나씩 지우면 성능이 안 나옴. @Modifying을 이용해 쿼리 한 번으로 처리.
     */
    @Modifying
    @Query("DELETE FROM WaitingTokenEntity w WHERE w.expiredAt < :now OR w.status IN :statuses")
    void deleteByExpiredAtBeforeOrStatusIn(@Param("now") LocalDateTime now,
                                           @Param("statuses") List<WaitingTokenStatus> statuses);

    /**
     * ACTIVE 이고 아직 만료되지 않았을 때만 상태 변경
     * consume 용도
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update WaitingTokenEntity w
           set w.status = :nextStatus
        where w.tokenId = :tokenId
           and w.status = :currentStatus
           and w.expiredAt > :now
    """)
    int updateStatusIfCurrentAndNotExpired(@Param("tokenId") String tokenId,
                                        @Param("currentStatus") WaitingTokenStatus currentStatus,
                                        @Param("nextStatus") WaitingTokenStatus nextStatus,
                                        @Param("now") LocalDateTime now);

    /**
     * ACTIVE 이고 이미 만료되었을 때만 상태 변경
     * expired 마킹 용도
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update WaitingTokenEntity w
           set w.status = :nextStatus
        where w.tokenId = :tokenId
           and w.status = :currentStatus
           and w.expiredAt <= :now
    """)
    int updateStatusIfCurrentAndExpired(@Param("tokenId") String tokenId,
                                        @Param("currentStatus") WaitingTokenStatus currentStatus,
                                        @Param("nextStatus") WaitingTokenStatus nextStatus,
                                        @Param("now") LocalDateTime now);
}
