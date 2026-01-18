package com.koesc.ci_cd_test_app.global.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.TimeUnit;

/**
 * 1. L1 캐시(Caffeine) 활성화: 공연(Event) 정보처럼 자주 변하지 않는 데이터는 Redis까지 갈 필요 없이
 *    WAS 메모리(L1)에서 바로 꺼내와야 함. 이를 위해 CaffeineCacheManager를 등록해야 한다.
 *
 * 2. 캐시 추상화 사용 : 서비스 코드에 @Cacheable, @CacheEvict 같은 어노테이션을 사용하여 비즈니스
 *    로직과 캐시 로직을 분리(관심사 분리)할 수 있다.
 *
 * 3. L1/L2 하이브리드 전략 : CacheConfig가 정의되어야 "L1(Caffeine)에 없으면 -> L2(Redis) 조회
 *    -> 거기서도 없으면 DB 조회라는 하이브리드 시나리오 완성
 */

@Configuration
@EnableCaching // 스프링 캐시 추상화 활성화
public class CacheConfig {

    /**
     * [L1 Cache] Caffeine 설정
     * - 로컬 메모리에 저장되어 응답 속도가 가장 빠름
     * - 각 WAS 노드마다 별도로 존재하므로 데이터 정합성에 주의 필요 (주로 조회 전용으로 사용)
     */
    @Bean
    @Primary // 기본 캐시 매니저로 설정
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES) // 10분 후 만료
                .maximumSize(1000) // 최대 1,000개 객체 저장
                .recordStats());

        return cacheManager;
    }

}
