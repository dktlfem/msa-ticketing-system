package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.implement.reader.WaitingRoomReader;
import com.koesc.ci_cd_test_app.implement.writer.WaitingRoomWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class WaitingRoomManager {

    private final WaitingRoomReader waitingRoomReader;
    private final WaitingRoomWriter waitingRoomWriter;
    // ADR: TimeConfig에서 Clock(Asia/Seoul)을 주입받아 시간 일관성 보장
    // WaitingRoomInternalService.validateUsable()도 동일 Clock 사용 → timezone 불일치로 인한 즉시 만료 버그 방지
    private final Clock clock;

    /**
     * [핵심 기능] 대기열 통과 토큰 생성
     * Service는 "토큰 만들어줘"라고만 요청하고,
     * Manager가 "어떤 ID로, 언제까지 유효하게" 만들지를 결정함 (관심사 분리)
     */
    public WaitingToken createToken(Long eventId, Long userId) {

        // 1. 이미 발급된 유효 토큰이 있는지 확인 (중복 발급 방지)
        // ADR: 만료 여부(expiredAt)도 함께 체크. ACTIVE 상태라도 expiredAt이 지난 토큰은 재사용 금지.
        // 재사용 시 validateUsable()에서 410 WAITING_TOKEN_EXPIRED 발생 → 예약 불가 버그.
        LocalDateTime checkNow = LocalDateTime.now(clock);
        WaitingToken existingToken = waitingRoomReader.findTokenByUser(userId, eventId);
        if (existingToken != null
                && existingToken.getStatus() == WaitingTokenStatus.ACTIVE
                && existingToken.getExpiredAt() != null
                && existingToken.getExpiredAt().isAfter(checkNow)) {
            return existingToken;
        }

        // 2. 새로운 토큰 생성 (UUID 사용) — 기존 만료 토큰은 새 토큰으로 대체
        LocalDateTime now = checkNow;
        WaitingToken newToken = WaitingToken.builder()
                .tokenId(UUID.randomUUID().toString()) // 예측 불가능한 ID
                .userId(userId)
                .eventId(eventId)
                .status(WaitingTokenStatus.ACTIVE)
                .issuedAt(now)
                .expiredAt(now.plusMinutes(10)) // 10분간 유효 (Clock 기준 — validateUsable()과 동일 기준 사용)
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
        if (token.getExpiredAt().isBefore(LocalDateTime.now(clock))) return false;
        return token.getStatus() == WaitingTokenStatus.ACTIVE;
    }

}
