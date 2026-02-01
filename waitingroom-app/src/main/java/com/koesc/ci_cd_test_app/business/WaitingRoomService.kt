package com.koesc.ci_cd_test_app.business

import com.koesc.ci_cd_test_app.api.response.WaitingRoomResponseDTO
import com.koesc.ci_cd_test_app.global.calculator.WaitingRoomCalculator
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomManager
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomRateLimiter
import com.koesc.ci_cd_test_app.implement.reader.WaitingRoomReader
import com.koesc.ci_cd_test_app.implement.validator.WaitingRoomValidator
import com.koesc.ci_cd_test_app.implement.writer.WaitingRoomWriter
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

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
    fun joinQueue(eventId: Long, userId: Long): Mono<Long> {
        // 동기 검증 로직은 필요 시 Mono.fromCallable 등으로 감싸서 실행 가능
        waitingRoomValidator.validateJoinRequest(eventId, userId)
        waitingRoomValidator.validateDuplicateAccess(eventId, userId)

        // Redis 진입 (ZADD)
        // 현재 순번 리턴 (Nullable 처리 후 + 1)
        return waitingRoomWriter.addToToken(eventId, userId)
            .then(waitingRoomReader.getRank(eventId, userId))
            .map { it + 1 }
            .defaultIfEmpty(1L) // 값이 비어있다면 1번으로 진입
    }

    /**
     * [Flow] 상태 조회 및 토큰 전환 (핵심 병목 방어 로직)
     * 1. Redis에서 현재 순번 조회 (DB 접근 x)
     * 2. 순번이 임계치(MAX_ENTRY_RANK)보다 크면 즉시 대기 응답 (Early Exit)
     * 3. 순번이 통과 범위라면, 그때서야 DB에 Active 토큰 생성/조회 (Write/Read)
     */
    fun getQueueStatus(eventId: Long, userId: Long): Mono<WaitingRoomResponseDTO> {

        return waitingRoomReader.getRank(eventId, userId)
            .flatMap { rank ->
                val currentRank = rank + 1

                // 1. Early Exit: 순번이 100위 밖이면 즉시 대기 응답 (Smart Cast 적용)
                if (rank > 100) {
                    return@flatMap Mono.just(
                        WaitingRoomResponseDTO.waiting(currentRank, waitingRoomCalculator.calculate(currentRank))
                    )
                }

                // 2. Rate Limiter 체크 (Mono<Boolean>을 flatMap으로 연결)
                waitingRoomRateLimiter.isAllowedToEnter(eventId)
                    .flatMap { isAllowed ->
                        if (!isAllowed) {
                            // 허용되지 않은 경우 대기 응답 반환
                            Mono.just(WaitingRoomResponseDTO.waiting(currentRank, waitingRoomCalculator.calculate(currentRank)))
                        } else {
                            // 3. 관문 통과: JPA(DB) 작업은 별도 스레드(boundedElastic)에서 실행하여 Netty 보호
                            Mono.fromCallable {
                                waitingRoomValidator.validateIssueToken(eventId)
                                waitingRoomManager.createToken(eventId, userId)
                            }.subscribeOn(Schedulers.boundedElastic())
                                .flatMap { token ->
                                    waitingRoomWriter.removeFromQueue(eventId, userId)
                                        .thenReturn(WaitingRoomResponseDTO.allowed(token))
                                }
                        }
                    }
            }
            // 4. [NPE 방어] 대기열에 정보가 없으면(Empty) 신규 진입 시도
            .switchIfEmpty(
                Mono.defer {
                    joinQueue(eventId, userId)
                        .map { newRank ->
                            WaitingRoomResponseDTO.waiting(newRank, waitingRoomCalculator.calculate(newRank))
                        }
                }
            )
    }
}