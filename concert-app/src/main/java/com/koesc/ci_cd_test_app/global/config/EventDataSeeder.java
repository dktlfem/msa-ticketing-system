package com.koesc.ci_cd_test_app.global.config;

import com.koesc.ci_cd_test_app.storage.entity.EventEntity;
import com.koesc.ci_cd_test_app.storage.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @Profile("!prod") : 실제 운영(Prod) DB에 연결되어도 앱이 뜨자마자 테스트 데이터를 밀어 넣게 되므로
 *                     어노테이션을 붙여 개발/테스트 환경에서만 작동하게 하자.
 */

@Slf4j
@Profile("!prod")
@Component
@RequiredArgsConstructor
public class EventDataSeeder implements CommandLineRunner {

    private final EventRepository eventRepository;
    private final Faker faker = new Faker(new Locale("ko")); // 한국어 데이터 생성

    @Override
    @Transactional
    public void run(String... args) throws Exception {

        // 1. 중복 방지: 이미 데이터가 있으면 실행하지 않음
        if (eventRepository.count() > 0) {
            log.info("이미 데이터가 존재하여 시딩을 건너뜁니다.");
            return;
        }

        log.info("[SCRUM-35] 대규모 부하 테스트를 위한 데이터 시딩을 시작합니다... (목표: 공연 30개)");

        // 2. 테스트용 이벤트 데이터 30개 생성 (k6 부하 테스트 타겟)
        List<EventEntity> events = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            EventEntity event = EventEntity.builder()
                    .title(faker.music().genre() + " 콘서트 - " + i + "회차")
                    .description(faker.lorem().sentence())
                    .posterUrl("https://image.toss.im/test/" + i)
                    .build();
            events.add(event);
        }

        eventRepository.saveAll(events);

        log.info("데이터 시딩 완료! (Events: 30)");
    }
}
