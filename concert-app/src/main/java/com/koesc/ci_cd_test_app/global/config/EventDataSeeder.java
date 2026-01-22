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

import java.util.*;

/**
 * @Profile("!prod") : 실제 운영(Prod) DB에 연결되어도 앱이 뜨자마자 테스트 데이터를 밀어 넣게 되므로
 *                     어노테이션을 붙여 개발/테스트 환경에서만 작동하게 하자.
 */

@Slf4j
// @Profile("!prod") : 프로필 dev 강제 지정을 위한 주석처리
@Profile("dev") // docker-compose.yml에 SPRING_PROFILES_ACTIVE: dev 설정과의 맞춤
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
        //    Set을 활용한 메모리 내에서 이메일 중복 제거
        Set<String> uniqueTitles = new HashSet<>();
        List<EventEntity> events = new ArrayList<>();

        while (uniqueTitles.size() < 30) {
            String title = faker.music().genre() + " 콘서트 - " + (uniqueTitles.size() + 1) + "회차";

            if (uniqueTitles.add(title)) {
                events.add(EventEntity.builder()
                        .title(title)
                        .description(faker.lorem().sentence())
                        .posterUrl("https://image.toss.im/test/" + (uniqueTitles.size()))
                        .build());
            }
        }

        // 전체 저장 (한 번의 인서트로 성능 최적화)
        eventRepository.saveAll(events);

        log.info("데이터 시딩 완료! (Events: 30)");
    }
}
