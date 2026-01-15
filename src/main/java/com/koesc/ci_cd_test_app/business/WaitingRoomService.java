package com.koesc.ci_cd_test_app.business;

import com.koesc.ci_cd_test_app.api.response.WaitingRoomResponseDTO;
import com.koesc.ci_cd_test_app.domain.WaitingToken;
import com.koesc.ci_cd_test_app.implement.calculator.WaitingRoomCalculator;
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomManager;
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomRateLimiter;
import com.koesc.ci_cd_test_app.implement.reader.WaitingRoomReader;
import com.koesc.ci_cd_test_app.implement.validator.WaitingRoomValidator;
import com.koesc.ci_cd_test_app.implement.writer.WaitingRoomWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class WaitingRoomService {

    private final WaitingRoomValidator waitingRoomValidator;
    private final WaitingRoomManager waitingRoomManager;
    private final WaitingRoomReader waitingRoomReader;
    private final WaitingRoomWriter waitingRoomWriter;
    private final WaitingRoomRateLimiter waitingRoomRateLimiter;
    private final WaitingRoomCalculator waitingRoomCalculator;


    /**
     * [Flow] 대기열 진입
     * 1. 검증기(Validator)를 통한 유효성 체크
     * 2. Writer를 통해 Redis(ZSET)에 유저 추가
     * 3. 현재 유저의 순번(Rank) 조회 후 반환
     */
    public Long joinQueue(Long eventId, Long userId) {
        waitingRoomValidator.validateJoinRequest(eventId, userId);
        waitingRoomValidator.validateDuplicateAccess(eventId, userId);

        // 1. Redis 진입 (ZADD)
        waitingRoomWriter.addToToken(eventId, userId);

        // 2. 현재 순번 리턴 (0부터 시작하므로 +1)
        return waitingRoomReader.getRank(eventId, userId) + 1;
    }

    /**
     * [Flow] 상태 조회 및 토큰 전환 (핵심 병목 방어 로직)
     * 1. Redis에서 현재 순번 조회 (DB 접근 x)
     * 2. 순번이 임계치(MAX_ENTRY_RANK)보다 크면 즉시 대기 응답 (Early Exit)
     * 3. 순번이 통과 범위라면, 그때서야 DB에 Active 토큰 생성/조회 (Write/Read)
     */
    public WaitingRoomResponseDTO getQueueStatus(Long eventId, Long userId) {

        // Redis에서 순번 확인
        Long rank = waitingRoomReader.getRank(eventId, userId);

        // 1. 대기열에 없으면 신규 진입 (멱등성 보장)
        if (rank == null) {
            Long newRank = joinQueue(eventId, userId);
            return WaitingRoomResponseDTO.waiting(newRank, waitingRoomCalculator.calculate(rank + 1));
        }

        // 2. Early Exit: 순번이 아직 멀었다면 (예: 100위 밖) 즉시 대기 응답
        if (rank >= 100) {
            return WaitingRoomResponseDTO.waiting(rank + 1, waitingRoomCalculator.calculate(rank + 1));
        }

        // 3. Rate Limiter: 순번은 다 됐지만, 초당 입장 인원 제한에 걸린 경우
        if (!waitingRoomRateLimiter.isAllowedToEnter(eventId)) {
            return WaitingRoomResponseDTO.waiting(rank + 1, waitingRoomCalculator.calculate(rank + 1));
        }

        // 4. 관문 통과: 최종 매진 여부확인 후 토큰 발급
        waitingRoomValidator.validateIssueToken(eventId);
        WaitingToken token = waitingRoomManager.createToken(eventId, userId);

        // 토큰 발급 성공 시 Redis 대기열에서 삭제 (ZREM)
        waitingRoomWriter.removeFromQueue(eventId, userId);


        // TODO: 내 순서가 되지않았다면 DB 조회를 하지 않음

        return WaitingRoomResponseDTO.allowed(token);
    }
}
