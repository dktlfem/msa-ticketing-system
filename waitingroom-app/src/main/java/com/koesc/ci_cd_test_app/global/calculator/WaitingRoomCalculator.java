package com.koesc.ci_cd_test_app.global.calculator;

import org.springframework.stereotype.Component;

/**
 * Calculator : 반복되는 비즈니스 계산 로직을 공용
 */
@Component
public class WaitingRoomCalculator {

    // 초당 100명 처리 가정 시, 1인당 0.01초 소요 (수치 조정 가능)
    private static final double TIME_PER_USER = 0.01;

    public Long calculate(Long rank) {
        if (rank == null || rank <= 0) return 0L;
        return (long) Math.ceil(rank * TIME_PER_USER);
    }
}
