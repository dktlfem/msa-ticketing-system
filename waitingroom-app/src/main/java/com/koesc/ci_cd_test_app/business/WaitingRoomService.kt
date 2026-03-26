package com.koesc.ci_cd_test_app.business

import com.koesc.ci_cd_test_app.api.response.WaitingRoomResponseDTO
import com.koesc.ci_cd_test_app.global.calculator.WaitingRoomCalculator
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomManager
import com.koesc.ci_cd_test_app.implement.manager.WaitingRoomRateLimiter
import com.koesc.ci_cd_test_app.implement.reader.WaitingRoomReader
import com.koesc.ci_cd_test_app.implement.validator.WaitingRoomValidator
import com.koesc.ci_cd_test_app.implement.writer.WaitingRoomWriter
import io.github.resilience4j.bulkhead.annotation.Bulkhead
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import java.util.Optional

@Service
class WaitingRoomService(

        private val waitingRoomValidator: WaitingRoomValidator,
        private val waitingRoomManager: WaitingRoomManager,
        private val waitingRoomReader: WaitingRoomReader,
        private val waitingRoomWriter: WaitingRoomWriter,
        private val waitingRoomRateLimiter: WaitingRoomRateLimiter,
        private val waitingRoomCalculator: WaitingRoomCalculator
) {

    private val log = LoggerFactory.getLogger(WaitingRoomService::class.java)

    /**
     * [Flow] 대기열 진입 (서킷 브레이커 + 벌크헤드 적용)
     * 1. 검증기(Validator)를 통한 유효성 체크
     * 2. Writer를 통해 Redis(ZSET)에 유저 추가
     * 3. 현재 유저의 순번(Rank) 조회 후 반환
     */
    @CircuitBreaker(name = "waitingRoomService", fallbackMethod = "joinQueueFallback")
    @Bulkhead(name = "waitingRoomService", fallbackMethod = "joinQueueFallback")
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

    // joinQueue 장애 발생 시 Fallback (순번 -1 반환)
    fun joinQueueFallback(eventId: Long, userId: Long, t: Throwable): Mono<Long> {
        log.warn("대기열 진입 Fallback 발동! 원인: ${t.message}")
        return Mono.just(-1L)
    }

    /**
     * [Flow] 상태 조회 및 토큰 전환 (핵심 병목 방어 로직) (서킷 브레이커 적용)
     * 1. Redis에서 현재 순번 조회 (DB 접근 x)
     * 2. 순번이 임계치(MAX_ENTRY_RANK)보다 크면 즉시 대기 응답 (Early Exit)
     * 3. 순번이 통과 범위라면, 그때서야 DB에 Active 토큰 생성/조회 (Write/Read)
     */
    @CircuitBreaker(name = "waitingRoomService", fallbackMethod = "getQueueStatusFallback")
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
            // 4. [NPE 방어] 대기열에 정보가 없으면(Empty):
            //    ADR: 토큰 발급 후 ZREM으로 대기열에서 제거되면 rank가 empty.
            //    기존 ACTIVE 토큰이 있으면 반환, 없으면 대기열 재진입.
            //    기존 joinQueue만 호출하면 validateDuplicateAccess에서
            //    ACTIVE 토큰 중복 검증에 걸려 fallback 발동되는 버그가 있었음.
            .switchIfEmpty(
                Mono.defer {
                    Mono.fromCallable {
                        Optional.ofNullable(waitingRoomReader.findTokenByUser(userId, eventId))
                    }.subscribeOn(Schedulers.boundedElastic())
                        .flatMap { optToken ->
                            val token = optToken.orElse(null)
                            if (token != null && token.isValid) {
                                // 유효한 ACTIVE 토큰이 이미 존재 → 바로 반환
                                Mono.just(WaitingRoomResponseDTO.allowed(token))
                            } else {
                                // ACTIVE 토큰 없음 → 대기열 재진입
                                joinQueue(eventId, userId)
                                    .map { newRank ->
                                        WaitingRoomResponseDTO.waiting(newRank, waitingRoomCalculator.calculate(newRank))
                                    }
                            }
                        }
                }
            )
    }

    // getQueueStatusFallback 장애 발생 시 Fallback
    fun getQueueStatusFallback(eventId: Long, userId: Long, t: Throwable): Mono<WaitingRoomResponseDTO> {
        log.warn("상태 조회 Fallback 발동! 원인: ${t.message}")
        // 사용자에게 현재 시스템이 지연 중임을 알리는 특별한 상태값 반환
        return Mono.just(WaitingRoomResponseDTO.waiting(-1L, 0))
    }
}