package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.api.response.WaitingRoomResponseDTO;
import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.global.calculator.WaitingRoomCalculator;
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
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * WaitingRoomServiceTest 단위 테스트
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("WaitingRoomService 단위 테스트 (Reactive Flow 검증)")
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
    @DisplayName("rank가 100위 밖인 경우, DB(Manager)를 조회하지 않고 즉시 waiting 응답을 반환한다 (Early Exit)")
    void getQueueStatus_EarlyExit_WhenRankIsHigh() {

        // Service -> Reader(순위 확인) -> 100위 이상 확인 -> 즉시 Return
        // 1. given
        Long eventId = 1L;
        Long userId = 100L;

        // Redis rank는 0-based 라고 가정 -> 150이면 실제 순번은 151
        given(waitingRoomReader.getRank(eventId, userId)).willReturn(Mono.just(150L)); // 151번째
        given(waitingRoomCalculator.calculate(151L)).willReturn(15L);

        // 2. when
        Mono<WaitingRoomResponseDTO> mono = waitingRoomService.getQueueStatus(eventId, userId);

        // 3. then
        StepVerifier.create(mono)
                .assertNext(res -> {
                    assertThat(res.isAllowed()).isFalse();
                    assertThat(res.rank()).isEqualTo(151L);
                    assertThat(res.estimatedSeconds()).isEqualTo(15L);
                })
                .verifyComplete();


        // [잠재적 장애 포인트 검증]
        // 이 상황에서 DB 조회(waitingRoomManager.createToken)가 일어나면 안 됨
        verify(waitingRoomManager, never()).createToken(anyLong(), anyLong());
        verify(waitingRoomRateLimiter, never()).isAllowedToEnter(anyLong());
    }

    @Test
    @DisplayName("RateLimit 거절: rank가 100위 안쪽이어도 rateLimiter가 false면 waiting 응답으로 떨어진다")
    void getQueueStatus_Waiting_WhenRateLimited() {

        // 1. given
        Long eventId = 1L;
        Long userId = 200L;

        given(waitingRoomReader.getRank(eventId, userId)).willReturn(Mono.just(10L)); // 실제 11번째
        given(waitingRoomRateLimiter.isAllowedToEnter(eventId)).willReturn(Mono.just(false));
        given(waitingRoomCalculator.calculate(11L)).willReturn(1L);

        // 2. when
        Mono<WaitingRoomResponseDTO> mono = waitingRoomService.getQueueStatus(eventId, userId);

        // 3. then
        StepVerifier.create(mono)
                .assertNext(res -> {
                    assertThat(res.isAllowed()).isFalse();
                    assertThat(res.rank()).isEqualTo(11L);
                    assertThat(res.estimatedSeconds()).isEqualTo(1L);
                    assertThat(res.tokenId()).isNull();
                })
                .verifyComplete();

        verify(waitingRoomManager, never()).createToken(anyLong(), anyLong());
        verify(waitingRoomWriter, never()).removeFromQueue(anyLong(), anyLong());
    }

    @Test
    @DisplayName("통과: rank가 100위 안쪽 & rateLimiter true면 token 발급 + queue 제거 + allowed 응답")
    void getQueueStatus_Allowed_WhenGatePass() {

        // 1. given
        Long eventId = 1L;
        Long userId = 300L;

        given(waitingRoomReader.getRank(eventId, userId)).willReturn(Mono.just(0L)); // 실제 1번째
        given(waitingRoomRateLimiter.isAllowedToEnter(eventId)).willReturn(Mono.just(true));

        WaitingToken token = WaitingToken.builder()
                .tokenId("token-123")
                .eventId(eventId)
                .userId(userId)
                .status(WaitingTokenStatus.ACTIVE)
                .issuedAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().plusMinutes(10))
                .build();

        given(waitingRoomManager.createToken(eventId, userId)).willReturn(token);
        given(waitingRoomWriter.removeFromQueue(eventId, userId)).willReturn(Mono.just(1L));

        // 2. when
        Mono<WaitingRoomResponseDTO> mono = waitingRoomService.getQueueStatus(eventId, userId);

        // 3. then
        StepVerifier.create(mono)
                .assertNext(res -> {
                    assertThat(res.isAllowed()).isTrue();
                    assertThat(res.rank()).isEqualTo(0L);
                    assertThat(res.tokenId()).isEqualTo("token-123");
                    assertThat(res.expiredAt()).isNotNull();
                })
                .verifyComplete();

        verify(waitingRoomValidator).validateIssueToken(eventId);
        verify(waitingRoomWriter).removeFromQueue(eventId, userId);
    }
}
