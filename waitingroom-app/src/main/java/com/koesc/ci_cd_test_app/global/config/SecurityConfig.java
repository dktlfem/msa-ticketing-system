package com.koesc.ci_cd_test_app.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * Tomcat(MVC) : @EnableWebSecurity / SecurityFilterChain / SecurityFilterChain
 * Netty(Reactive) : @EnableWebFluxSecurity / SecurityWebFilterChain / SecurityWebFilterChain
 *
 *
 */
@Slf4j
@Configuration
@EnableWebFluxSecurity // 1. MVC의 @EnableWebSecurity 대신 반드시 이걸 써야 함
public class SecurityConfig {

    public SecurityConfig() {
        log.info("[DEBUG] Webflux SecurityConfig Bean is being created!");
    }

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {

        // 2. Webflux에서는 HttpSecurity대신 ServerHttpSecurity를 주입받음
        log.info("[DEBUG] SecurityWebFilterChain (Reactive) is being configured!");

        return http
                // CSRF 비활성화 (Stateless API 서버이므로 필수)
                .csrf(csrf -> csrf.disable())

                // 3. Webflux는 기본적으로 세션을 사용하지 않는 Stateless 방식임
                .authorizeExchange(exchanges -> exchanges // 4. authorizeHttpRequests 대신 authorizeExchange 사용 (서버와 클라이언트 간의 데이터 교환)
                        .pathMatchers(
                                "/api/v1/waiting-room/**",
                                "/api/v1/**",
                                "/error",
                                "/actuator/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**"
                        ).permitAll()
                        // 그 외 모든 요청은 인증 필요
                        .anyExchange().authenticated()
                )
                // 6. 폼 로그인 및 기본 인증 비활성화 (401 Unauthorized realm="Realm" 방지)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .build();
    }
}
