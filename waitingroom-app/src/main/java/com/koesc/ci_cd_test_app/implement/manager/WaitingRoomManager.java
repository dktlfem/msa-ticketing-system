package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.implement.reader.WaitingRoomReader;
import com.koesc.ci_cd_test_app.implement.writer.WaitingRoomWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WaitingRoomManager {

    private final WaitingRoomReader waitingRoomReader;
    private final WaitingRoomWriter waitingRoomWriter;

    /**
     * [핵심 기능] 대기열 통과 토큰 생성
     * Service는 "토큰 만들어줘"라고만 요청하고,
     * Manager가 "어떤 ID로, 언제까지 유효하게" 만들지를 결정함 (관심사 분리)
     */
    public WaitingToken createToken(Long eventId, Long userId) {

        // 1. 이미 발급된 유효 토큰이 있는지 확인 (중복 발급 방지)
        WaitingToken existingToken = waitingRoomReader.findTokenByUser(userId, eventId);
        if (existingToken != null && existingToken.getStatus() == WaitingTokenStatus.ACTIVE) {
            return existingToken;
        }

        // 2. 새로운 토큰 생성 (UUID 사용)
        WaitingToken newToken = WaitingToken.builder()
                .tokenId(UUID.randomUUID().toString()) // 예측 불가능한 ID
                .userId(userId)
                .eventId(eventId)
                .status(WaitingTokenStatus.ACTIVE)
                .issuedAt(LocalDateTime.now())
                .expiredAt(LocalDateTime.now().plusMinutes(10)) // 10분간 유효
                .build();

        // 3. 저장
        return waitingRoomWriter.save(newToken);
    }

    /**
     * 토큰 검증 로직
     * 예약(Reservation) 도메인에서 "이 사람 입장시켜도 돼?"라고 물어볼 때 사용
     */
    public boolean verifyToken(String tokenId) {
        WaitingToken token = waitingRoomReader.findToken(tokenId);

        // 토큰이 없거나, 만료되었거나, 활성 상태가 아니면 false 리턴
        if (token == null) return false;
        if (token.getExpiredAt().isBefore(LocalDateTime.now())) return false;
        return token.getStatus() == WaitingTokenStatus.ACTIVE;
    }

}
