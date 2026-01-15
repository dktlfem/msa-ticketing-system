package com.koesc.ci_cd_test_app.implement.calculator;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("WaitingRoomCalculator 단위 테스트 : 대기 시간 계산 로직 검증")
public class WaitingRoomCalculatorTest {

    private final WaitingRoomCalculator calculator = new WaitingRoomCalculator();

    @ParameterizedTest
    @CsvSource({
            "1, 1",         // 1등 -> 0.01초(올림) -> 1초
            "100, 1",        // 100등 -> 1초
            "1000, 10",      // 1000등 -> 10초
            "5555, 56"      // 5555등 -> 55.55초 -> 56초
    })
    @DisplayName("대기 순번에 따른 예상 대기 시간(초)을 정확히 계산한다.")
    void calculate_Success(Long rank, Long expectedSeconds) {

        // 1. when
        Long result = calculator.calculate(rank);

        // 2. then
        assertThat(result).isEqualTo(expectedSeconds);
    }

    @Test
    @DisplayName(("순번이 null이거나 0 이하일 겨웅 0초를 반환한다"))
    void calculate_EdgeCase() {
        assertThat(calculator.calculate(null)).isZero();
        assertThat(calculator.calculate(0L)).isZero();
        assertThat(calculator.calculate(-1L)).isZero();
    }
}
