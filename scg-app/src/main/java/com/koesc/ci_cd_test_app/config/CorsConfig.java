package com.koesc.ci_cd_test_app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * 브라우저의 CORS 정책을 Gateway 레벨에서 처리하는 설정 클래스
 *
 * 설정 내용:
 * - 허용할 도메인: yml에서 주입(코드 수정 없이 환경별로 수정 가능)
 * - 허용할 메서드: GET, POST, PUT, DELETE, OPTIONS
 * - 쿠키/인증 정보 포함 허용: allowCredentials: true
 */
@Configuration
public class CorsConfig {

    @Value("${gateway.security.cors.allowed-origin-patterns:*}")
    private String allowedOriginPatternsRaw;

    @Bean
    public CorsWebFilter corsWebFilter() {
        List<String> patterns = Arrays.stream(allowedOriginPatternsRaw.split(","))
                .map(String::trim)
                .toList();

        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOriginPatterns(patterns);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        config.addAllowedHeader("*");


        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
