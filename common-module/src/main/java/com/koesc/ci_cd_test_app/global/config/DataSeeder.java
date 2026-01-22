package com.koesc.ci_cd_test_app.global.config;

import com.koesc.ci_cd_test_app.storage.entity.EventEntity;
import com.koesc.ci_cd_test_app.storage.entity.UserEntity;
import com.koesc.ci_cd_test_app.storage.repository.EventRepository;
import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final Faker faker = new Faker(new Locale("ko")); // 한국어 데이터 생성

    @Override
    @Transactional
    public void run(String... args) {

        // 1. 중복 방지: 이미 데이터가 있으면 실행하지 않음
        if (userRepository.count() > 0) {
            log.info("이미 데이터가 존재하여 시딩을 건너뜁니다.");
            return;
        }

        log.info("[SCRUM-35] 대규모 부하 테스트를 위한 데이터 시딩을 시작합니다... (목표: 1,000명)");

        // 2. 테스트용 이벤트 데이터 10개 생성 (k6 부하 테스트 타겟)
        for (int i = 1; i <= 10; i++) {
            EventEntity event = EventEntity.builder()
                    .title(faker.music().genre() + " 콘서트 - " + i + "회차")
                    .description(faker.lorem().sentence())
                    .posterUrl("https://image.toss.im/test/" + i)
                    .build();
            eventRepository.save(event);
        }

        // 3. 테스트용 유저 데이터 1,000명 생성 (대규모 접속 시뮬레이션용)
        // 아래 코드는 루프 안에서 매번 save() : 매우 느림
        List<UserEntity> users = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) {
            UserEntity user = UserEntity.builder()
                    .name(faker.name().fullName())
                    .email(faker.internet().emailAddress())
                    .password("password123!") // 테스트용 공통 비밀번호
                    .point(BigDecimal.valueOf(100000)) // 기본 포인트 10만점
                    .build();
            users.add(user); // 리스트에 담기
        }

        userRepository.saveAll(users); // 단 한 번의 호출로 1,000건 저장 시도

        log.info("데이터 시딩 완료! (Events: 10, Users: 1000)");
    }
}
