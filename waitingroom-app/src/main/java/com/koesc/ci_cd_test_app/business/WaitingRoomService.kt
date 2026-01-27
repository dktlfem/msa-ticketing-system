package com.koesc.ci_cd_test_app.business

import com.koesc.ci_cd_test_app.api.response.WaitingRoomResponseDTO
import com.koesc.ci_cd_test_app.domain.WaitingToken
import com.koesc.ci_cd_test_app.global.calculator.WaitingRoomCalculator
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomManager
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomRateLimiter
import com.koesc.ci_cd_test_app.implement.reader.WaitingRoomReader
import com.koesc.ci_cd_test_app.implement.validator.WaitingRoomValidator
import com.koesc.ci_cd_test_app.implement.writer.WaitingRoomWriter
import org.springframework.stereotype.Service

@Service
class WaitingRoomService(

        private val waitingRoomValidator: WaitingRoomValidator,
        private val waitingRoomManager: WaitingRoomManager,
        private val waitingRoomReader: WaitingRoomReader,
        private val waitingRoomWriter: WaitingRoomWriter,
        private val waitingRoomRateLimiter: WaitingRoomRateLimiter,
        private val waitingRoomCalculator: WaitingRoomCalculator
) {

    /**
     * [Flow] 대기열 진입
     * 1. 검증기(Validator)를 통한 유효성 체크
     * 2. Writer를 통해 Redis(ZSET)에 유저 추가
     * 3. 현재 유저의 순번(Rank) 조회 후 반환
     */
    fun joinQueue(eventId: Long, userId: Long): Long {
        waitingRoomValidator.validateJoinRequest(eventId, userId)
        waitingRoomValidator.validateDuplicateAccess(eventId, userId)

        // Redis 진입 (ZADD)
        waitingRoomWriter.addToToken(eventId, userId)

        // 현재 순번 리턴 (Nullable 처리 후 + 1)
        val rank = waitingRoomReader.getRank(eventId, userId) ?: 0L
        return rank + 1
    }

    /**
     * [Flow] 상태 조회 및 토큰 전환 (핵심 병목 방어 로직)
     * 1. Redis에서 현재 순번 조회 (DB 접근 x)
     * 2. 순번이 임계치(MAX_ENTRY_RANK)보다 크면 즉시 대기 응답 (Early Exit)
     * 3. 순번이 통과 범위라면, 그때서야 DB에 Active 토큰 생성/조회 (Write/Read)
     */
    fun getQueueStatus(eventId: Long, userId: Long): WaitingRoomResponseDTO {

        // Redis에서 순번 확인 (Long? 타입으로 변환됨)
        val rank = waitingRoomReader.getRank(eventId, userId)

        // 1. 대기열에 없으면 신규 진입 (멱등성 보장)
        /*if (rank == null) { // 1-1. rank가 null인 것을 확인했음.
            Long newRank = joinQueue(eventId, userId);

            // 1-2. 아래 rank + 1에서 반드시 NullPointException이 발생함.
            // 원인 : rank가 null일 때 진입하는 블록 안에서 rank + 1 연산을 수행하고 있음.
            // TODO 해결책 : 신규 진입 시에는 newRank를 기준으로 계산하거나, 기본값을 사용하도록 방어 코드를 구축해야함.
            return WaitingRoomResponseDTO.waiting(newRank, waitingRoomCalculator.calculate(rank + 1));
        }*/

        // 1. 대기열에 없으면 신규 진입 (멱등성 보장)
        if (rank == null) {
            val newRank = joinQueue(eventId, userId)

            // [NPE 해결] rank 대신 새로 생성된 newRank를 사용하여 계산
            // TODO 해결책: 1. Optional<>으로 Nullable 처리, 2. Kotlin의 Null Safety
            return WaitingRoomResponseDTO.waiting(newRank, waitingRoomCalculator.calculate(newRank))
        }

        // 2. Early Exit: 순번이 100위 밖이면 즉시 대기 응답 (Smart Cast 적용)
        if (rank >= 100) {
            return WaitingRoomResponseDTO.waiting(rank + 1, waitingRoomCalculator.calculate(rank + 1))
        }

        // 3. Rate Limiter: 초당 입장 인원 제한 체크
        if (!waitingRoomRateLimiter.isAllowedToEnter(eventId)) {
            return WaitingRoomResponseDTO.waiting(rank + 1, waitingRoomCalculator.calculate(rank + 1))
        }

        // 4. 관문 통과: 매진 확인 후 토큰 발급
        waitingRoomValidator.validateIssueToken(eventId)
        val token: WaitingToken = waitingRoomManager.createToken(eventId, userId)

        // 발급 성공시 Redis 대기열에서 삭제
        waitingRoomWriter.removeFromQueue(eventId, userId)

        return WaitingRoomResponseDTO.allowed(token)
    }
}