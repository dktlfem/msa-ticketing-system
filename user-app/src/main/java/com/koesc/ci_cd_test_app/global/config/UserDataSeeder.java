package com.koesc.ci_cd_test_app.global.config;

import com.koesc.ci_cd_test_app.storage.entity.UserEntity;
import com.koesc.ci_cd_test_app.storage.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.datafaker.Faker;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.IntStream;

@Slf4j
// @Profile("!prod") : 프로필 dev 강제 지정을 위한 주석처리
@Profile("dev") // docker-compose.yml에 SPRING_PROFILES_ACTIVE: dev 설정과의 맞춤
@Component
@RequiredArgsConstructor
public class UserDataSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final Faker faker = new Faker(new Locale("ko"));

    @Override
    @Transactional
    public void run(String... args) {

        // 1. 중복 방지: 이미 데이터가 있으면 실행하지 않음
        if (userRepository.count() > 0) {
            log.info("이미 데이터가 존재하여 시딩을 건너뜁니다.");
            return;
        }

        log.info("[SCRUM-35] 대규모 부하 테스트를 위한 데이터 시딩 시작... (목표: 1,000명)");

        // 2. 테스트용 유저 데이터 1,000개 생성 (k6 부하 테스트 타겟)
        //    Set을 활용한 메모리 내에서 이메일 중복 제거
        Set<String> uniqueEmails = new HashSet<>();
        List<UserEntity> allUsers = new ArrayList<>();

        while (uniqueEmails.size() < 1000) {
            String email = faker.internet().emailAddress();

            // Set.add()는 중복된 값이 없을 때만 true를 반환함
            if (uniqueEmails.add(email)) {
                allUsers.add(UserEntity.builder()
                        .name(faker.name().fullName())
                        .email(email)
                        .password("password123!")
                        .point(BigDecimal.valueOf(100000))
                        .build());
            }
        }

        // 전체 저장 (한 번의 인서트로 성능 최적화)
        userRepository.saveAll(allUsers);

        // 3. @gmail.com 유저만 필터링하는 로직 (Stream API 실습)
        // allUsers.stream(): 이미 만들어진 1,000명의 유저 리스트를 가공 라인(Stream)에 올린다.
        // .filter(user -> ...): 공정 라인 중간에 검수원(filter)을 배치하는 단계. 이메일이 @gmail.com으로 끝나지 않는 데이터는 라인 밖으로 폐기.
        // .toList(): 검수를 통과한 알짜배기 데이터들만 다시 상자(List)에 담아 완성품을 만든다.

        // 테스트 데이터 관리(Data Parameterization)를 위해 필요
        List<UserEntity> gmailUsers = allUsers.stream()
                .filter(user -> user.getEmail().endsWith("@gmail.com"))
                .toList();

        log.info("데이터 시딩 완료! (전체: 1000명, Gmail 사용자: {}명)", gmailUsers.size());
    }
}