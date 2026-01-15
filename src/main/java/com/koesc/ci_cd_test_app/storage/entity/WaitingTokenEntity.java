package com.koesc.ci_cd_test_app.storage.entity;

import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * 대기열을 통과한 토큰 정보를 저장하는 Entity
 */
@Entity
@Table(
        name = "active_tokens",
        indexes = {
            @Index(name = "idx_token_id", columnList = "token_id"), // 조회 성능 최적화
            @Index(name = "idx_user_event", columnList = "user_id, event_id") // 특정 유저의 토큰 보유 여부 확인
        },
        schema = "ticketing_waitingroom"
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WaitingTokenEntity {

    /**
     * PK를 Long(Auto Increment)이 아닌 String(UUID)로 설정한 이유 :
     * 대기열 토큰은 클라이언트에게 노출되는 값이다.
     * 순차적인 Long 값은 예측이 가능하여 해커가 토큰을 위조하여 부정 입장할 가능성이 있음.
     * 따라서 난수화된 UUID를 사용한다.
     */
    @Id
    @Column(name = "token_id", length = 36)
    private String tokenId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private WaitingTokenStatus status; // ACTIVE, EXPIRED, USED, CANCELED

    @Column(name = "issued_at", nullable = false)
    private LocalDateTime issuedAt;

    @Column(name = "expired_at", nullable = false)
    private LocalDateTime expiredAt;

    // 만료 처리 비즈니스 로직 (Setter 대신 도메인 메서드 사용)
    public void expire() {
        this.status = WaitingTokenStatus.EXPIRED;
    }
}
