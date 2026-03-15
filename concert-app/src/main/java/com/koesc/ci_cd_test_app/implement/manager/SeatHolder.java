package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.domain.Seat;
import com.koesc.ci_cd_test_app.implement.reader.SeatReader;
import com.koesc.ci_cd_test_app.implement.validator.SeatValidator;
import com.koesc.ci_cd_test_app.implement.writer.SeatWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실제 서비스 로직에서 동시성을 제어하는 별도의 관리자 객체를 만듦.
 * 클래스 명 : 1. SeatHolder, 2. ConcurrencyManager 중 1번 채택
 *
 * 클래스 명을 왜 1번을 채택했냐?
 * -> SeatHolder를 SeatManager로부터 더 세분화해서 분리해
 *    추후에 Redis 분산 락(Redisson)으로 교체해야하거나,
 *    테스트·재사용성·모니터링·정책(재시도, backoff, metrics)을 한곳에서 관리하면
 *    이후 Kafka/분산락/캐시 도입할 때 훨씬 수월하기 때문.
 *
 * SeatHolder 역할 : 좌석 점유(Hold)에 대한 구체적인 동시성 제어 및 상태 변경 정책 담당
 *            장점 : 추후 Redis 분산락(Redisson) 도입 시 클래스 내부만 수정하면 Business Service는 영향받지 않음
 *
 * SeatHolder : 동시성 제어 (낙관적 락) + 상태 변경 책임을 가진 컴포넌트
 * read -> domain 변환 -> validate -> domain 변경(hold/release) -> entity에 반영 -> saveAndFlush
 */

@Slf4j
@Component
@RequiredArgsConstructor
public class SeatHolder {

    private final SeatReader seatReader;
    private final SeatWriter seatWriter;
    private final SeatValidator seatValidator;

    /**
     * 좌석 임시 점유 (낙관적 락 활용)
     * 트랜잭션 경계는 이 메서드가 책임짐.
     */
    @Transactional
    public Seat hold(Long seatId) {
        log.info("[SeatHolder] 좌석 점유 시도 - seatId: {}", seatId);

        Seat seat = seatReader.read(seatId);
        seatValidator.validateAvailable(seat);

        Seat updatedSeat = seat.hold();

        try {
            Seat result = seatWriter.updateWithFlush(updatedSeat, seat.getVersion());
            log.info("[SeatHolder] 좌석 점유 성공 - seatId: {}", seatId);
            return result;
        } catch (OptimisticLockingFailureException e) {
            log.warn("[SeatHolder] 동시성 충돌 발생 - 다른 사용자가 먼저 점유함. seatId: {}", seatId);
            throw e; // 상위에서 재시도나 사용자 메시지 처리하도록 전파
        }
    }

    /**
     * 점유 해제 (예약 만료 또는 취소 시)
     */
    @Transactional
    public Seat release(Long seatId) {
        log.info("[SeatHolder] 좌석 점유 해제 - seatId: {}", seatId);

        Seat seat = seatReader.read(seatId);
        Seat updatedSeat = seat.release();

        return seatWriter.update(updatedSeat, seat.getVersion());
    }
}
