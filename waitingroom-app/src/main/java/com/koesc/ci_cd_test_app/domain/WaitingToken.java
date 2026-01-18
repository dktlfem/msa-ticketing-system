package com.koesc.ci_cd_test_app.domain;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * WaitingToken : 대기열 토큰
 * * 역할 : DB 테이블(Entity)과 비즈니스 로직(Service) 사이의 연결고리
 */

@Getter
@Builder
public class WaitingToken {

    private final String tokenId;
    private final Long userId;
    private final Long eventId;
    private WaitingTokenStatus status; // 상태는 변경 가능해야 하므로 final 제외
    private final LocalDateTime issuedAt; // 토큰 발행 날짜
    private final LocalDateTime expiredAt; // 토큰 만료 날짜

    /**
     * 토큰 만료 여부 검증
     * Service 계층에서 if(...) 하지 않고,
     * 객체에게 직접 "너 만료됐니?"라고 물어보는 객체지향적 설계
     */
    public boolean isValid() {

        // 1. 상태가 ACTIVE 인지 확인
        if (this.status != WaitingTokenStatus.ACTIVE) {
            return false;
        }

        // 2. 현재 시간이 만료 시간 이전인지 확인
        return LocalDateTime.now().isBefore(this.expiredAt);
    }

    /**
     * 토큰 만료 처리
     * 외부에서 setter로 status를 바꾸는 게 아니라, 의미 있는 메서드를 통해 상태를 변경함.
     */
    public void expire() {
        if (this.status == WaitingTokenStatus.ACTIVE) {
            this.status = WaitingTokenStatus.EXPIRED;
        }
    }

    /**
     * 토큰 사용 완료 처리 (예매 진입 성공 시)
     */
    public void use() {
        if (this.isValid()) {
            this.status = WaitingTokenStatus.USED;
        } else {
            throw new IllegalStateException("유효하지 않은 토큰은 사용할 수 없습니다.");
        }
    }
}
