package com.koesc.ci_cd_test_app.global.scheduler;

import com.koesc.ci_cd_test_app.domain.WaitingTokenStatus;
import com.koesc.ci_cd_test_app.storage.repository.WaitingTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Profile("!test") // 테스트에서 test 프로필이 활성화될 때 해당 빈들을 로딩하지 않기 위한 목적
@Component
@RequiredArgsConstructor
public class WaitingRoomScheduler {

    private final WaitingTokenRepository waitingTokenRepository;

    @Scheduled(cron = "0 0/5 * * * *") // 5분마다 실행
    @Transactional
    public void cleanupExpiredTokens() {

        // 1차 구현: 단순히 시간이 지난 토큰이나 사용 완료된 토큰 정리
        waitingTokenRepository.deleteByExpiredAtBeforeOrStatusIn(
                LocalDateTime.now(),
                List.of(WaitingTokenStatus.EXPIRED, WaitingTokenStatus.USED)
        );
    }
}
