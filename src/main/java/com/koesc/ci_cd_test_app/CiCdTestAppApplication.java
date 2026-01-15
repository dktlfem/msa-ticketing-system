package com.koesc.ci_cd_test_app;

import com.koesc.ci_cd_test_app.global.config.SecurityConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

// scanBasePackages를 명시하여 모든 하위 패키지의 빈(Bean) 스캔을 강제합니다.
@SpringBootApplication(scanBasePackages = "com.koesc.ci_cd_test_app")
@Import(SecurityConfig.class) // 설정을 강제로 가져오라고 명시
public class CiCdTestAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(CiCdTestAppApplication.class, args);
	}

}
