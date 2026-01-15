package com.koesc.ci_cd_test_app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// scanBasePackages를 명시하여 모든 하위 패키지의 빈(Bean) 스캔을 강제합니다.
@SpringBootApplication(scanBasePackages = "com.koesc.ci_cd_test_app")
public class CiCdTestAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(CiCdTestAppApplication.class, args);
	}

}
