package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.storage.repository.EventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;

/**
 * DB를 찌르는 로직이 포함되어있으므로, @Mock을 사용하여 DB 응답을 시뮬레이션하는 단위 테스트를 작성
 */

@ExtendWith(MockitoExtension.class)
@DisplayName("EventValidator 단위 테스트 : DB 연동 검증 (Mock)")
public class EventValidatorTest {

    @Mock
    private EventRepository eventRepository;

    @InjectMocks
    private EventValidator eventValidator;

    @Test
    @DisplayName("이미 존재하는 공연 제목으로 등록 시도 시 예외가 발생한다")
    void validateDuplicateTitle_fail() {

        // 1. given
        String title = "이미 있는 공연;";
        given(eventRepository.existsByTitle(title)).willReturn(true);

        // 2. when & then
        assertThatThrownBy(() -> eventValidator.validateDuplicateTitle(title))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("이미 동일한 제목의 공연이 존재합니다.");
    }

    @Test
    @DisplayName("존재하지 않는 공연 ID 조회 시 예외가 발생한다")
    void validateIdExists_fail() {

        // 1. given
        given(eventRepository.existsById(999L)).willReturn(false);

        // 2. when & then
        assertThatThrownBy(() -> eventValidator.validateExists(999L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("존재하지 않는 공연 정보입니다.");

    }

    @Test
    @DisplayName("제목이나 설명이 공백이면 예외가 발생한다")
    void validateBasicInfo_fail() {

        // when & then
        assertThatThrownBy(() -> eventValidator.validateBasicInfo("", "설명"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("공연 제목은 필수입니다.");
    }

    @Test
    @DisplayName("공연 제목이 100자를 초과하면 예외를 발생한다.")
    void validate_fail_title_length() {

        // 1. given
        String longTitle = "A".repeat(101);

        // 2. when & then
        assertThatThrownBy(() -> eventValidator.validateBasicInfo(longTitle, "상세 설명"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("공연 제목은 100자를 초과할 수 없습니다.");
    }
}
