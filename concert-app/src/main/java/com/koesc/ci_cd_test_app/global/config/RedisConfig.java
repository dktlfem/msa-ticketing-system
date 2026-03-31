package com.koesc.ci_cd_test_app.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Value("${spring.data.redis.host}")
    private String host;

    @Value("${spring.data.redis.port}")
    private int port;

    @Value("${spring.data.redis.password}")
    private String password;

    /**
     * Why? Jedis 대신 Lettuce를 사용하는 이유
     * - Netty 기반의 비동기 이벤트 처리 지원
     * - 멀티 스레드 환경에서 Thread-Safe함 (Jedis는 Thread-Safe 하지 않음)
     * - 성능이 우수하고 하드웨어 자원 효율적
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 2. Standalone 설정을 통해 패스워드를 명시적으로 주입함.
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        config.setPassword(password);

        return new LettuceConnectionFactory(config);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(ObjectMapper objectMapper) {
        // ADR: Spring Boot 자동 구성 ObjectMapper를 주입받는 이유
        // - GenericJackson2JsonRedisSerializer의 기본 생성자는 JavaTimeModule이 없는 ObjectMapper를 사용.
        //   Event 도메인의 LocalDateTime 필드 직렬화 시 InvalidDefinitionException 발생.
        // - Spring Boot가 구성한 ObjectMapper는 JavaTimeModule(ISO-8601 형식),
        //   비어있지 않은 클래스 처리, null 처리 등이 이미 설정되어 있음.
        // - @Primary가 없으면 Spring이 자동 구성한 ObjectMapper가 기본으로 주입됨.
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(redisConnectionFactory());

        // Key는 문자열로 직렬화
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());

        // ADR: GenericJackson2JsonRedisSerializer 사용 이유
        // - StringRedisSerializer는 String 타입만 직렬화 가능.
        //   EventReader.read()에서 Event 도메인 객체를 Redis L2 캐시에 저장할 때
        //   ClassCastException 발생 (Event cannot be cast to String).
        // - GenericJackson2JsonRedisSerializer는 타입 정보(@class)를 JSON에 포함하여
        //   직렬화/역직렬화 시 타입 안전성을 보장한다.
        // - Jackson2JsonRedisSerializer<Event>와 달리 제네릭으로 모든 Object 타입에 대응 가능.
        // Trade-off: JSON에 @class 필드가 추가되어 저장 공간이 소폭 증가하나,
        //            캐시 데이터이므로 무시 가능한 수준.
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer(objectMapper);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        return template;
    }
}
