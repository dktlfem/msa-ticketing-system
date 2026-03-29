package com.koesc.ci_cd_test_app.global.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ADR: Redisson 분산락 설정
 *
 * [채택 배경]
 * booking-app은 동일 좌석에 대한 동시 예약 요청이 발생할 수 있다.
 * concert-app의 낙관적 락(@Version)이 1차 방어선이지만, 재시도 없이 즉시 실패하므로
 * 사용자 경험 저하와 불필요한 HOLD 시도가 반복된다.
 * 분산락을 booking-app 레벨에서 걸어 동일 좌석에 대한 동시 접근을 직렬화한다.
 *
 * [Lettuce 스핀락 vs Redisson pub/sub]
 * - Lettuce 스핀락: 락 획득 전까지 Redis에 지속적으로 SETNX 폴링 → CPU/네트워크 낭비
 * - Redisson pub/sub: 락 해제 이벤트 구독 후 대기 → 불필요한 폴링 없음
 * - watchdog 기능: 작업이 leaseTime 내 완료되지 않을 때 자동 연장 (명시적 leaseTime 설정 시 비활성)
 *
 * [설정 근거]
 * - SingleServer: Redis 전용 서버(192.168.124.101)는 현재 standalone 구성
 * - spring.data.redis.* 와 별도 관리: Redisson 내부 커넥션 풀을 독립적으로 운용하기 위함
 * - destroyMethod="shutdown": 애플리케이션 종료 시 Netty 스레드 풀 정상 반납
 */
@Configuration
public class RedissonConfig {

    private static final String REDIS_URL_FORMAT = "redis://%s:%d";

    @Value("${spring.data.redis.host}")
    private String redisHost;

    @Value("${spring.data.redis.port}")
    private int redisPort;

    @Value("${spring.data.redis.password:}")
    private String redisPassword;

    /**
     * RedissonClient Bean 등록.
     *
     * redisson-spring-boot-starter가 클래스패스에 있어도,
     * @Bean 으로 직접 RedissonClient를 등록하면 AutoConfiguration이 back-off하여
     * 중복 빈 충돌 없이 이 설정만 사용된다.
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();

        config.useSingleServer()
                .setAddress(String.format(REDIS_URL_FORMAT, redisHost, redisPort))
                // 패스워드가 빈 문자열이면 인증 없이 연결 (로컬 개발 환경 대응)
                .setPassword(redisPassword.isBlank() ? null : redisPassword)
                // 커넥션 유휴 시간 초과 감지 (Redis 서버 측 timeout과 맞춤)
                .setIdleConnectionTimeout(10_000)
                // TCP keep-alive 핑 주기
                .setPingConnectionInterval(3_000)
                // 분산락 전용 커넥션 풀 (lettuce 풀과 독립 운용)
                .setConnectionPoolSize(10)
                .setConnectionMinimumIdleSize(2);

        return Redisson.create(config);
    }
}
