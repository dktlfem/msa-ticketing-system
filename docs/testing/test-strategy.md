---
title: 테스트 전략 — 현재 상태와 목표
status: reviewed-draft
last-verified: 2026-04-01
scan-basis: src/test/**/*.java 전수 스캔 (아카이브 디렉토리 제외)
---

# 테스트 전략 — 현재 상태와 목표

## 1. 집계 기준 정의

이 문서의 테스트 분류는 아래 기준을 따른다.
각 파일은 **하나의 분류**에만 속한다 (상호 배타적).

### 분류 정의 (5가지, 상호 배타적)

| 분류 | 기준 | 예시 |
|------|------|------|
| **단위 테스트** | `@ExtendWith(MockitoExtension)`, `@WebMvcTest`, `@WebFluxTest` 사용. Spring 전체 컨텍스트를 로드하지 않고 Mock 기반으로 특정 클래스를 격리 테스트. 동시성 기법(CountDownLatch 등)이나 리액티브 검증(StepVerifier 등)을 포함하더라도, Mock 기반 격리 테스트이면 단위로 분류한다. | `ReservationManagerTest`, `QueueTokenValidationFilterTest` |
| **통합 테스트** | `@SpringBootTest` + 실제 DB(Testcontainers 또는 외부) 사용. 여러 계층을 걸쳐 실제 동작을 검증 | `SeatHolderConcurrencyIT`, `EventScheduleDetailIT` |
| **컨텍스트 로드 테스트** | `*ApplicationTests` 패턴. Spring 컨텍스트가 정상 로드되는지만 확인하며 비즈니스 로직 테스트 메서드 없음 | `ConcertApplicationTests`, `UserApplicationTests` |
| **empty stub** | 테스트 클래스가 존재하나 `@Test` 메서드가 없는 빈 클래스 (`*ApplicationTests` 제외) | `InternalSeatControllerTest`, `WaitingRoomInternalServiceTest` |
| **기반/설정 클래스** | 직접 테스트가 아닌 테스트 인프라. 다른 테스트가 상속하거나 Import하는 클래스 | `AbstractIntegrationTest`, `TestClockConfig` |

> **비고 열 참고:** 동시성(CountDownLatch, ExecutorService)이나 리액티브(StepVerifier, WebTestClient) 기법을 사용하는 파일은
> 모듈별 상세표의 "비고" 열에 해당 기법을 표기한다. 이들은 독립 분류가 아니라 주 분류의 부가 특성이다.

### 집계 범위

- **포함:** `settings.gradle`에 등록된 6개 활성 모듈의 `src/test/**/*.java`
- **제외:** `아카이브/` 디렉토리 (과거 백업 파일, 빌드 대상 아님)

---

## 2. 모듈별 테스트 현황

### 전체 집계

| 모듈 | 단위 | 통합 | 컨텍스트로드 | stub | 기반/설정 | 합계 |
|------|:----:|:----:|:----------:|:----:|:-------:|:----:|
| booking-app | 2 | — | — | — | — | **2** |
| concert-app | 8 | 3 | 1 | 2 | 2 | **16** |
| payment-app | 2 | — | 1 | — | — | **3** |
| scg-app | 4 | — | — | — | — | **4** |
| user-app | 8 | — | 1 | — | — | **9** |
| waitingroom-app | 8 | — | 1 | 1 | — | **10** |
| **합계** | **32** | **3** | **4** | **3** | **2** | **44** |

> 검증: 32 + 3 + 4 + 3 + 2 = **44** (모듈별 합계와 일치)

---

### 모듈별 상세

#### booking-app (2)

| 파일 | 분류 | 비고 |
|------|------|------|
| `ReservationControllerTest` | 단위 | `@WebMvcTest`, MockMvc |
| `ReservationManagerTest` | 단위 | Mockito. `@Nested` 내 CountDownLatch 2스레드 동시성 시나리오 포함 |

#### concert-app (16)

| 파일 | 분류 | 비고 |
|------|------|------|
| `AbstractIntegrationTest` | 기반 클래스 | Testcontainers MySQL 8.0 + `@DynamicPropertySource` |
| `TestClockConfig` | 설정 클래스 | 고정 시각: 2026-02-09T12:00 KST |
| `ConcertApplicationTests` | 컨텍스트 로드 | `extends AbstractIntegrationTest` |
| `EventControllerTest` | 단위 | `@WebMvcTest` |
| `EventScheduleControllerTest` | 단위 | `@WebMvcTest` |
| `InternalSeatControllerTest` | stub | `@Test` 메서드 없음 |
| `SeatInternalServiceTest` | stub | `@Test` 메서드 없음 |
| `EventServiceTest` | 단위 | Mockito |
| `SeatHolderTest` | 단위 | Mockito |
| `EventReaderTest` | 단위 | Mockito |
| `EventScheduleValidatorTest` | 단위 | Mockito |
| `EventValidatorTest` | 단위 | Mockito |
| `EventWriterTest` | 단위 | Mockito |
| `SeatHolderConcurrencyIT` | 통합 | Testcontainers + ExecutorService 2스레드, 낙관적 락 검증 |
| `EventScheduleDetailIT` | 통합 | Testcontainers + MockMvc |
| `EventSchedulePaginationIT` | 통합 | Testcontainers + 페이지네이션 검증 |

#### payment-app (3)

| 파일 | 분류 | 비고 |
|------|------|------|
| `PaymentApplicationTests` | 컨텍스트 로드 | 빈 컨텍스트 로드 |
| `PaymentManagerTest` | 단위 | Saga 3 시나리오 (`@Nested`), Mockito |
| `CancelFailedRetrySchedulerTest` | 단위 | Mockito |

#### scg-app (4)

| 파일 | 분류 | 비고 |
|------|------|------|
| `JwtAuthenticationFilterTest` | 단위 | Mock `GatewayFilterChain`, JWT 생성 헬퍼. 리액티브(`StepVerifier`) |
| `QueueTokenValidationFilterTest` | 단위 | Mock `GatewayFilterChain`. 리액티브(`StepVerifier`) |
| `RequestSanitizeFilterTest` | 단위 | `@ParameterizedTest`. 리액티브(`StepVerifier`) |
| `InternalPathBlockFilterTest` | 단위 | `@ParameterizedTest`. 리액티브(`StepVerifier`) |

#### user-app (9)

| 파일 | 분류 | 비고 |
|------|------|------|
| `UserApplicationTests` | 컨텍스트 로드 | `@SpringBootTest` |
| `ApiCommonTraceTest` | 단위 | `@WebMvcTest`, traceId 전파 검증 |
| `UserControllerTest` | 단위 | `@WebMvcTest`, MockMvc |
| `AuthServiceTest` | 단위 | Mockito |
| `UserServiceTest` | 단위 | Mockito |
| `JwtProviderTest` | 단위 | Mockito |
| `UserReaderTest` | 단위 | Mockito |
| `UserValidatorTest` | 단위 | Mockito |
| `UserWriterTest` | 단위 | Mockito |

#### waitingroom-app (10)

| 파일 | 분류 | 비고 |
|------|------|------|
| `WaitingRoomApplicationTests` | 컨텍스트 로드 | `@SpringBootTest` |
| `WaitingRoomInternalServiceTest` | stub | `@Test` 메서드 없음 |
| `InternalWaitingRoomTokenControllerTest` | 단위 | — |
| `WaitingRoomControllerTest` | 단위 | `@WebFluxTest`, `WebTestClient`. 리액티브 |
| `WaitingRoomResilienceTest` | 단위 | CircuitBreaker + `StepVerifier`. 리액티브 |
| `WaitingRoomServiceTest` | 단위 | `Mono` 흐름 + `StepVerifier`. 리액티브 |
| `WaitingRoomCalculatorTest` | 단위 | 순수 계산 로직 |
| `WaitingRoomReaderTest` | 단위 | Reactive Redis mock. 리액티브 |
| `WaitingRoomValidatorTest` | 단위 | Mockito |
| `WaitingRoomWriterTest` | 단위 | Reactive Redis mock. 리액티브 |

---

## 3. 테스트 인프라 현재 구성

### DB 전략

| 모듈 | DB 전략 | DDL 모드 | 비고 |
|------|---------|---------|------|
| concert-app | **Testcontainers MySQL 8.0** | `update` | `AbstractIntegrationTest` + `@DynamicPropertySource` |
| user-app | H2 in-memory (`MODE=MySQL`) | `create-drop` | H2Dialect 명시 |
| waitingroom-app | H2 in-memory (`MODE=MySQL`) | `create-drop` | H2Dialect, 스키마 자동 생성 |
| booking-app | 미설정 (DDL만 존재) | `create-drop` | DB URL/드라이버 미지정 |
| payment-app | 미설정 (DDL만 존재) | `create-drop` | DB URL/드라이버 미지정 |
| scg-app | — | — | DB 불필요 (stateless gateway) |

> **Assumption AS-1:** booking-app, payment-app의 test resources에 DB URL과 드라이버가
> 미설정되어 있다. Spring Boot auto-config이 H2를 감지하면 동작할 수 있으나,
> H2 의존성 존재 여부와 실제 테스트 실행 가능 여부는 `./gradlew test`로 검증 필요.

### Redis 전략

| 모듈 | test resources Redis 설정 | 비고 |
|------|--------------------------|------|
| concert-app | 외부 Redis 서버 직접 연결 (호스트·비밀번호 하드코딩) | **Known Issue KI-1, KI-2** |
| user-app | localhost:6379 (테스트 전용 비밀번호) | 실서버 미사용, 위험도 낮음 |
| waitingroom-app | test resources에 Redis 설정 없음 | main config 참조 가능성 |
| booking-app | test resources에 Redis 설정 없음 | — |
| payment-app | test resources에 Redis 설정 없음 | — |

### 리액티브 테스트 인프라

scg-app과 waitingroom-app은 WebFlux 기반이므로 `reactor-test`(`StepVerifier`),
`WebTestClient`, Mock `GatewayFilterChain`을 사용한다. 해당 모듈의 단위 테스트 13개 중
9개가 리액티브 기법을 포함한다 (scg-app 4개, waitingroom-app 5개).

### 시간 제어

concert-app에서만 `TestClockConfig`로 고정 `Clock` Bean을 주입한다 (2026-02-09T12:00 KST).
나머지 모듈은 시간 제어가 없다.

---

## 4. CI 파이프라인 현재 상태

**소스:** `Jenkinsfile`

### 현재 빌드 명령어

```
./gradlew :${TARGET_MODULE}:clean :${TARGET_MODULE}:build -x test
```

- **`-x test`로 모든 테스트가 명시적으로 스킵**된다.
- `stage('Test')`는 파이프라인에 존재하지 않는다.
- 즉, CI에서 실행되는 테스트는 **0건**이다.

### 기타 CI 이슈

| 항목 | 현재 상태 | 영향 |
|------|----------|------|
| docker.io 설치 | 매 빌드마다 `apt-get install -y docker.io` 실행 | 빌드 시간 증가, 불필요한 네트워크 의존 |
| Health check | `sleep 20` / `sleep 30` 고정 대기 | 서비스 미기동 시 감지 지연, readiness probe 미사용 |
| 모듈 선택 | `TARGET_MODULE` 파라미터로 단일 모듈만 빌드 | 의존 모듈 변경 감지 불가 |

---

## 5. 보안 이슈 (test resources 한정)

> 아래는 **존재 사실과 영향**만 기록한다. 실제 비밀번호·IP·호스트 값은 포함하지 않는다.

| ID | 모듈 | 위치 | 이슈 | 위험도 |
|----|------|------|------|:------:|
| SEC-1 | concert-app | `src/test/resources/application.properties` | Redis 비밀번호가 평문으로 하드코딩되어 있음 | **HIGH** |
| SEC-2 | concert-app | `src/test/resources/application.properties` | Redis 호스트가 내부 네트워크 IP로 직접 노출되어 있음 | **MEDIUM** |
| SEC-3 | user-app | `src/test/resources/application.properties` | JWT 시크릿과 Redis 비밀번호가 테스트 전용 값으로 하드코딩. 실서비스 비밀번호와 다르므로 위험도 낮음 | **LOW** |

> **참고 (test가 아닌 main config 이슈):**
> waitingroom-app의 `src/main/resources` Redis 호스트가 다른 4개 서비스와 다른 주소로
> 하드코딩되어 있다. 이 이슈는 `environment-matrix.md`에서 다룬다.

---

## 6. 현재 상태 vs 목표 상태

| 항목 | 현재 상태 | 목표 상태 | 우선순위 |
|------|----------|----------|:--------:|
| CI 테스트 실행 | `-x test` (0건 실행) | `./gradlew test` 필수 통과 | **P0** |
| 테스트 커버리지 | 측정 없음 | JaCoCo + 최소 임계값 게이트 | P1 |
| 통합 테스트 DB | concert-app만 Testcontainers | 전 모듈 Testcontainers MySQL 통일 | P1 |
| Redis 테스트 | concert-app: 외부 서버 직접 연결 | Testcontainers Redis 또는 Embedded Redis 통일 | P1 |
| 테스트 비밀번호 | concert-app: 실서버 비밀번호 하드코딩 | 환경변수 또는 test profile 분리 | P1 |
| booking/payment DB | test resources에 DB 설정 미존재 | H2 또는 Testcontainers 설정 보완 | P2 |
| empty stub 정리 | 3개 존재 (concert 2, waitingroom 1) | 삭제 또는 테스트 구현 | P2 |
| 컨텍스트 로드 테스트 | 4개 (비즈니스 검증 없음) | 최소 smoke test 메서드 추가 또는 제거 | P3 |
| 성능 테스트 | k6 11개 시나리오 (CI 외부 수동 실행) | CI 연동 또는 별도 스케줄 파이프라인 | P3 |

---

## 7. 우선순위 로드맵 (제안)

### Phase 1: CI 품질 게이트 복원 (최우선)

- Jenkinsfile에서 `-x test` 제거
- `stage('Test')` 추가: `./gradlew :${TARGET_MODULE}:test`
- 테스트 실패 시 빌드 실패 처리

> **포인트:** "프로젝트 초기에는 빠른 배포를 위해 테스트를 스킵했으나,
> 서비스 간 계약 위반 이슈가 발생한 후 CI 품질 게이트를 복원했습니다"

### Phase 2: 테스트 인프라 정비

- booking-app, payment-app test resources에 H2 DB 설정 추가
- concert-app test resources에서 외부 Redis 의존 제거 → Testcontainers Redis 도입
- 비밀번호 하드코딩 제거 → 환경변수 또는 `@DynamicPropertySource`
- docker.io 매 빌드 설치 → Jenkins 에이전트 이미지에 사전 설치

### Phase 3: 테스트 커버리지 확대

- JaCoCo 플러그인 추가 + 최소 라인 커버리지 임계값 설정
- empty stub 3개: 삭제 또는 실제 테스트 구현
- booking-app, payment-app 통합 테스트 추가 (Testcontainers MySQL)
- 서비스 간 계약 테스트 도입 (Spring Cloud Contract 또는 Pact)

### Phase 4: 성능 테스트 자동화

- k6 시나리오를 CI 파이프라인 또는 별도 스케줄 잡으로 편입
- 임계값 기반 성능 게이트 (p95 응답시간 등)

---

## 8. Known Issues & Assumptions 종합

### Known Issues

| ID | 항목 | 영향 | 소스 |
|----|------|------|------|
| KI-1 | concert-app test resources에 실서버 Redis 비밀번호 평문 하드코딩 | 소스 코드 리포지토리에 민감 정보 노출 | `concert-app/src/test/resources/application.properties` |
| KI-2 | concert-app test resources에 내부 네트워크 Redis IP 직접 노출 | 네트워크 구조 정보 유출 | 동일 파일 |
| KI-3 | Jenkinsfile `-x test` → CI에서 테스트 0건 실행 | 코드 품질 검증 없이 배포 가능 | `Jenkinsfile` |
| KI-4 | empty stub 3개 존재 | 테스트 누락 착각 가능, 코드 정리 필요 | concert-app 2개, waitingroom-app 1개 |

### Assumptions

| ID | 항목 | 검증 필요 사항 |
|----|------|--------------|
| AS-1 | booking-app, payment-app test DB 미설정 | `./gradlew :booking-app:test` 실행 시 H2 auto-config으로 동작하는지, 또는 실패하는지 확인 필요 |
| AS-2 | waitingroom-app `@SpringBootTest` 시 Redis 연결 | test resources에 Redis 미설정 → main config fallback 여부 확인 필요 |
| AS-3 | PaymentApplicationTests 컨텍스트 로드 성공 여부 | test resources에 DB/Redis 최소 설정만 존재 → 실제 로드 가능한지 미검증 |

---

## 9. 검증 소스 파일 목록

| 파일 | 검증 내용 |
|------|----------|
| `booking-app/src/test/java/**/*.java` (2개) | 단위 테스트 분류, 동시성 시나리오 확인 |
| `concert-app/src/test/java/**/*.java` (16개) | 통합 테스트 인프라, Testcontainers, stub 확인 |
| `payment-app/src/test/java/**/*.java` (3개) | Saga 시나리오 테스트, 스케줄러 테스트 확인 |
| `scg-app/src/test/java/**/*.java` (4개) | 리액티브 필터 테스트 분류 |
| `user-app/src/test/java/**/*.java` (9개) | WebMvcTest, Mockito 분류 |
| `waitingroom-app/src/test/java/**/*.java` (10개) | 리액티브, stub 확인 |
| `booking-app/src/test/resources/application.properties` | DDL만 존재, DB URL 미설정 확인 |
| `concert-app/src/test/resources/application.properties` | Redis 하드코딩 확인 (SEC-1, SEC-2) |
| `payment-app/src/test/resources/application.properties` | DDL만 존재, DB URL 미설정 확인 |
| `user-app/src/test/resources/application.properties` | H2 설정, 테스트 전용 비밀번호 확인 (SEC-3) |
| `waitingroom-app/src/test/resources/application.properties` | H2 설정, Redis 미설정 확인 |
| `concert-app/src/test/java/.../AbstractIntegrationTest.java` | Testcontainers MySQL 설정 확인 |
| `concert-app/src/test/java/.../testconfig/TestClockConfig.java` | 고정 Clock 설정 확인 |
| `Jenkinsfile` | `-x test`, `stage('Test')` 미존재 확인 |
| `build.gradle` (루트) | JUnit Platform, spring-security-test 의존성 확인 |
| `concert-app/build.gradle` | Testcontainers MySQL 1.19.3 의존성 확인 |
