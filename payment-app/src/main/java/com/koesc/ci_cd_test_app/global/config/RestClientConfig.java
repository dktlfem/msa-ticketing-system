package com.koesc.ci_cd_test_app.global.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    /**
     * 모든 RestClient 인스턴스에 공통 타임아웃을 적용한다.
     * connect: 3s, read: 10s (TossPayments PG 응답은 통상 2~5s 이내)
     */
    @Bean
    public RestClientCustomizer restClientCustomizer() {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(3_000);
            factory.setReadTimeout(10_000);
            builder.requestFactory(factory);
        };
    }
}
