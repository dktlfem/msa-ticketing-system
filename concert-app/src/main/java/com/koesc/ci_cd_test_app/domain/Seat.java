package com.koesc.ci_cd_test_app.domain;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Seat {

    private final Long seatId;
    private final Long scheduleId;
    private final Integer seatNo;
    private final BigDecimal price;
    private final SeatStatus status;
    private final Long version;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    // --- 비즈니스 로직 ---

    /**
     * 좌석 점유 (hold)
     * 새로운 상태를 가진 객체를 반환하여 사이드 이펙트 방지
     */
    public Seat hold() {
        if (this.status != SeatStatus.AVAILABLE) {
            throw new IllegalStateException("이미 선택되었거나 판매된 좌석입니다.");
        }
        return this.toBuilder()
                .status(SeatStatus.HOLD)
                .updatedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 점유 해제 (release)
     */
    public Seat release() {
        return this.toBuilder()
                .status(SeatStatus.AVAILABLE)
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
