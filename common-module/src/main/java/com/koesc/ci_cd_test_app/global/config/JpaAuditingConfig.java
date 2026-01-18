package com.koesc.ci_cd_test_app.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * Entity 의 CreatedDate, LastModifiedDate 를 위한 Config
 *
 * @Configuration : 해당 클래스가 Spring 의 설정 클래스임을 명시
 * @EnableJpaAuditing : 데이터베이스 엔터티의 생성 시간/수정 시간 정보를 자동으로 관리
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
