package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.implement.reader.WaitingRoomReader;
import com.koesc.ci_cd_test_app.implement.writer.WaitingRoomWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class WaitingRoomInternalService {

    private final WaitingRoomReader waitingRoomReader;
    private final WaitingRoomWriter waitingRoomWriter;
    private final Clock clock;

    @Transactional(readOnly = true)
    public WaitingToken validateOrThrow(String tokenId, Long userId, Long eventId) {
        WaitingToken token = readTokenOrThrow(tokenId);

        validateOwnerAndEvent(token, userId, eventId);
        validateUsable(token);

        return token;
    }

    @Transactional
    public WaitingToken consumeOrThrow(String tokenId) {
        LocalDateTime now = LocalDateTime.now(clock);
        WaitingToken token = readTokenOrThrow(tokenId);

        if (token.getStatus() == WaitingTokenStatus.USED) {
            throw new IllegalStateException("WAITING_TOKEN_ALREADY_USED");
        }

        if (isExpired(token, now)) {
            waitingRoomWriter.markExpiredIfActive(tokenId, now);
            throw new IllegalStateException("WAITING_TOKEN_EXPIRED");
        }

        if (token.getStatus() != WaitingTokenStatus.ACTIVE) {
            throw new IllegalStateException("WAITING_TOKEN_INVALID");
        }

        boolean consumed = waitingRoomWriter.consumeIfActive(tokenId, now);
        if (!consumed) {
            WaitingToken latest = readTokenOrThrow(tokenId);

            if (latest.getStatus() == WaitingTokenStatus.USED) {
                throw new IllegalStateException("WAITING_TOKEN_ALREADY_USED");
            }

            if (isExpired(latest, now)) {
                waitingRoomWriter.markExpiredIfActive(tokenId, now);
                throw new IllegalStateException("WAITING_TOKEN_EXPIRED");
            }

            throw new IllegalStateException("WAITING_TOKEN_INVALID");
        }

        return readTokenOrThrow(tokenId);
    }

    private WaitingToken readTokenOrThrow(String tokenId) {
        WaitingToken token = waitingRoomReader.findToken(tokenId);
        if (token == null) {
            throw new IllegalStateException("WAITING_TOKEN_INVALID");
        }
        return token;
    }

    private void validateOwnerAndEvent(WaitingToken token, Long userId, Long eventId) {
        if (!Objects.equals(token.getUserId(), userId) || !Objects.equals(token.getEventId(), eventId)) {
            throw new IllegalStateException("WAITING_TOKEN_INVALID");
        }
    }

    private void validateUsable(WaitingToken token) {
        LocalDateTime now = LocalDateTime.now(clock);

        if (token.getStatus() == WaitingTokenStatus.USED) {
            throw new IllegalStateException("WAITING_TOKEN_ALREADY_USED");
        }

        if (isExpired(token, now)) {
            throw new IllegalStateException("WAITING_TOKEN_EXPIRED");
        }

        if (token.getStatus() != WaitingTokenStatus.ACTIVE) {
            throw new IllegalStateException("WAITING_TOKEN_INVALID");
        }
    }

    private boolean isExpired(WaitingToken token, LocalDateTime now) {
        return token.getExpiredAt() != null && !token.getExpiredAt().isAfter(now);
    }
}