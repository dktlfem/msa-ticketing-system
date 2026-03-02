package com.koesc.ci_cd_test_app.testconfig;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 어디선가 LocalDateTime.now(clock)을 호출하면
 * clock의 Instant를 가져가
 * clock의 ZoneId로 변환해서 그 지역(서울) 기준의 LocalDateTime으로 만듦
 *
 * 그럼 실제 now 값은?
 * 2026-02-09 03:00:00Z (UTC)
 * -> 서울(UTC + 9)로 바꾸면 2026-02-09 12:00:00 (Asia/Seoul)
 */

@TestConfiguration
public class TestClockConfig {

    @Bean
    public Clock clock() {
        return Clock.fixed(
                Instant.parse("2026-02-09T03:00:00Z"), // Z = UTC (UTC 기준의 절대시간)
                ZoneId.of("Asia/Seoul") // ZoneId.of("Asia/Seoul")은 UTC + 9
        );
    }
}
