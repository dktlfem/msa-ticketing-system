package com.koesc.ci_cd_test_app.implement.validator;

import com.koesc.ci_cd_test_app.global.error.CustomException;
import com.koesc.ci_cd_test_app.global.error.ErrorCode;
import com.koesc.ci_cd_test_app.storage.repository.EventRepository;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("WaitingRoomValidator 단위 테스트 : 예외 케이스 검증 (Mock)")
public class WaitingRoomValidatorTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private WaitingTokenRepository waitingTokenRepository;

    @InjectMocks
    private WaitingRoomValidator waitingRoomValidator;

    @Test
    @DisplayName("존재하지 않는 공연 ID로 진입 시 EVENT_NOT_FOUND 예외가 발생한다.")
    void validateJoinRequest_ThrowsException_WhenEventNotFound() {

        // 1. Given
        Long invalidEventId = 999L;
        given(eventRepository.existsById(invalidEventId)).willReturn(false);

        // 2. when & then
        CustomException exception = assertThrows(CustomException.class, () ->
                waitingRoomValidator.validateJoinRequest(invalidEventId, 100L)
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EVENT_NOT_FOUND);

        // 대규모 시스테메서는 유효하지 않은 요청을 Business Layer까지 올리지 않고
        // Validator 단계에서 빠르게 쳐내는 전략이 성능 최적화의 핵심이다.
    }
}
