package com.koesc.ci_cd_test_app.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 테스트를 위해 CSRF 비활성화 (로컬 테스트 및 API 서버에서는 보통 끔)
                .csrf(csrf -> csrf.disable())

                // 경로별 권한 설정
                .authorizeHttpRequests(auth -> auth
                        // 회원가입 API와 Swagger 관련 경로는 로그인 없이 허용
                        .requestMatchers("/api/v1/users/signup", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        // 그 외 모든 요청은 인증 필요
                        .anyRequest().authenticated()
                )
                // 기본 로그인 폼 사용 (필요시)
                .formLogin(Customizer.withDefaults());

        return http.build();
    }
}
