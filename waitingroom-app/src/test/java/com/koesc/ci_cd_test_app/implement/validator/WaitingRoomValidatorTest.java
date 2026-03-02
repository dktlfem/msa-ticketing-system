package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.global.error.exception.BusinessException;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

/**
 * WaitingRoomValidator 단위 테스트 : 빠른 차단 + 표준 에러코드
 *
 * 대규모 시스테메서는 유효하지 않은 요청을 Business Layer까지 올리지 않고
 * Validator 단계에서 빠르게 쳐내는 전략이 성능 최적화의 핵심이다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaitingRoomValidator 단위 테스트 : (차단/에러코드 표준화)")
public class WaitingRoomValidatorTest {

    @Mock
    private WaitingTokenRepository waitingTokenRepository;

    @InjectMocks
    private WaitingRoomValidator waitingRoomValidator;

    @Test
    @DisplayName("필수값 누락이면 INVALID_INPUT_VALUE로 BusinessException 발생")
    void validateJoinRequest_Throws_WhenNull() {

        // 1. when
        BusinessException ex = assertThrows(BusinessException.class,
                () -> waitingRoomValidator.validateJoinRequest(null, 100L));

        // 2. then
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("이미 ACTIVE 토큰이 있으면 ALREADY_HAS_TOKEN로 BusinessException 발생")
    void validateDuplicateAccess_Throws_WhenAlreadyHasToken() {

        // 1. Given
        Long eventId = 1L;
        Long userId = 100L;

        given(waitingTokenRepository.existsByEventIdAndUserIdAndStatus(eventId, userId, WaitingTokenStatus.ACTIVE))
                .willReturn(true);

        // 2. when
        BusinessException ex = assertThrows(BusinessException.class,
                () -> waitingRoomValidator.validateDuplicateAccess(eventId, userId));

        // 3. then
        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.ALREADY_HAS_TOKEN);
    }
}
