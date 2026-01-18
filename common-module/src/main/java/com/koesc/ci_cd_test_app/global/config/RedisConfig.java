package com.koesc.ci_cd_test_app.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    /**
     * Why? Jedis 대신 Lettuce를 사용하는 이유
     * - Netty 기반의 비동기 이벤트 처리 지원
     * - 멀티 스레드 환경에서 Thread-Safe함 (Jedis는 Thread-Safe 하지 않음)
     * - 성능이 우수하고 하드웨어 자원 효율적
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        return new LettuceConnectionFactory(host, port);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate() {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());

        // Key는 문자열로 직렬화
        template.setKeySerializer(new StringRedisSerializer());

        // Value도 간단한 테스트를 위해 String으로 직렬화 (실무에선 Jackson2JsonRedisSerializer 고려)
        template.setValueSerializer(new StringRedisSerializer());

        return template;
    }
}
