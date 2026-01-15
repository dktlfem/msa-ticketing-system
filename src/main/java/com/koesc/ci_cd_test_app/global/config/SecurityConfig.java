package com.koesc.ci_cd_test_app.global.config;

import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                // 테스트를 위해 CSRF 비활성화 (로컬 테스트 및 API 서버에서는 세션 기반이 아니므로 반드시 꺼야 POST 요청이 허용됨.)
                .csrf(csrf -> csrf.disable())

                // 세션 정책을 STATELESS로 설정 : 서버에서 세션을 유지하지 않도록 설정
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                /**
                * 경로별 권한 설정
                * 1. 모니터링 엔드포인트(/actuator/**) 허용
                * 2. 부하 테스트용 API(/api/v1/**) 허용
                * 3. Swagger 문서 허용
                */
                .authorizeHttpRequests(auth -> auth
                        // DispatcherType.ERROR를 허용하여 내부 에러 발생 시 403 대신 진짜 에러(500 등)를 보게함.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // 1. Actuator 및 API 경로 전체 허용
                        .requestMatchers(new AntPathRequestMatcher("/actuator/**")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/api/v1/**")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/error")).permitAll()
                        .requestMatchers(new AntPathRequestMatcher("/swagger-ui/**"),
                                         new AntPathRequestMatcher("/v3/api-docs/**")).permitAll()
                        // 그 외 모든 요청은 인증 필요
                        .anyRequest().authenticated()
                )
                // 현재는 테스트 단계이므로 기본 로그인 폼은 유지하되, 위 경로는 필터를 통과함
                // Refactor: 2. 로그인 폼 및 기본 인증 비활성화 (로그인 페이지 리다이렉트 방지)
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable());

        return http.build();
    }
}
