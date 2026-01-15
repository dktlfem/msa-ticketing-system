package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.api.response.WaitingRoomResponseDTO;
import com.koesc.ci_cd_test_app.implement.calculator.WaitingRoomCalculator;
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomManager;
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomRateLimiter;
import com.koesc.ci_cd_test_app.implement.reader.WaitingRoomReader;
import com.koesc.ci_cd_test_app.implement.validator.WaitingRoomValidator;
import com.koesc.ci_cd_test_app.implement.writer.WaitingRoomWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WaitingRoomService 비즈니스 흐름 테스트 (Mock)")
public class WaitingRoomServiceTest {

    @Mock
    private WaitingRoomReader waitingRoomReader;

    @Mock
    private WaitingRoomWriter waitingRoomWriter;

    @Mock
    private WaitingRoomValidator waitingRoomValidator;

    @Mock
    private WaitingRoomRateLimiter waitingRoomRateLimiter;

    @Mock
    private WaitingRoomManager waitingRoomManager;

    @Mock
    private WaitingRoomCalculator waitingRoomCalculator;

    @InjectMocks
    private WaitingRoomService waitingRoomService;

    @Test
    @DisplayName("순번이 100위 밖인 경우, DB를 조회하지 않고 즉시 대기 응답을 반환한다 (Early Exit)")
    void getQueueStatus_EarlyExit_WhenRankIsHigh() {

        // Service -> Reader(순위 확인) -> 100위 이상 확인 -> 즉시 Return
        // 1. given
        Long eventId = 1L;
        Long userId = 100L;
        given(waitingRoomReader.getRank(eventId, userId)).willReturn(150L); // 151번째
        given(waitingRoomCalculator.calculate(151L)).willReturn(15L);

        // 2. when
        WaitingRoomResponseDTO response = waitingRoomService.getQueueStatus(eventId, userId);

        // 3. then
        assertThat(response.isAllowed()).isFalse();
        assertThat(response.rank()).isEqualTo(151L);

        // [잠재적 장애 포인트 검증]
        // 이 상황에서 DB 조회(waitingRoomManager.createToken)가 일어나면 안 됨
        verify(waitingRoomManager, never()).createToken(anyLong(), anyLong());

    }
}
