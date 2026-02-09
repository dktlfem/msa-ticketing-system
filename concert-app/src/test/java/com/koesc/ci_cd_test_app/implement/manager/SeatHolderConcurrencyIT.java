package com.koesc.ci_cd_test_app.implement.manager;

import com.koesc.ci_cd_test_app.AbstractIntegrationTest;
import com.koesc.ci_cd_test_app.domain.SeatStatus;
import com.koesc.ci_cd_test_app.storage.entity.SeatEntity;
import com.koesc.ci_cd_test_app.storage.repository.SeatRepository;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

/**
 * SeatHolderConcurrencyIT -> 동시성 통합 테스트 (SpringBootTest + Testcontainers(MySQL)
 *                                          + ExecutorService + CountDownLatch)
 *
 * Testcontainers MySQL을 사용하여 실제 MySQL 동작 기반에서
 * ExecutorService로 두 스레드를 동시에 시작해 SeatHolder.hold()를 호출하고,
 * 최종적으로 한 명은 성공·한 명은 실패(낙관적 락 예외 또는 상태 검사 실패)하는지 검증
 */

@DisplayName("SeatHolder 동시성 통합 테스트 : 실제 MySQL 기반")
public class SeatHolderConcurrencyIT extends AbstractIntegrationTest {

    @Autowired
    private SeatHolder seatHolder;

    @Autowired
    private SeatRepository seatRepository;

    @Test
    @DisplayName("실제 MySQL 환경에서 2명의 동시 점유 시도 시, 낙관적 락으로 1명만 성공해야 한다.")
    void realConcurrencyTest() throws InterruptedException {

        // 1. given : 테스트 데이터 준비
        SeatEntity seat = seatRepository.saveAndFlush(SeatEntity.builder()
                .scheduleId(100L)
                .seatNo(55)
                .price(new BigDecimal("50000"))
                .status(SeatStatus.AVAILABLE)
                .build());

        Long seatId = seat.getSeatId();

        // 2. when : 2개의 스레드가 동시에 hold 시도
        int threadCount = 2;
        ExecutorService executorService = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            executorService.execute(() -> {
                try {
                    seatHolder.hold(seatId);
                    successCount.incrementAndGet();
                } catch (ObjectOptimisticLockingFailureException e) {
                    failCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }
        latch.await();

        // 3. then : 검증
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(failCount.get()).isEqualTo(1);

        // 최종 버전 확인 (버전이 1번만 상승했어야 함)
        SeatEntity finalSeat = seatRepository.findById(seatId).orElseThrow();
        assertThat(finalSeat.getVersion()).isEqualTo(1L);
    }
}
