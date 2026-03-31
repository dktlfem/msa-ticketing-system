# 기술 스택 및 시스템 아키텍처

## 1. 기술 스택 정의

### 1.1 애플리케이션 스택

- 언어: Java 21 (Eclipse Temurin), Kotlin 1.9.22 (scg-app, waitingroom-app)
- 프레임워크: Spring Boot 3.4.0, Spring Cloud 2024.0.0
- 빌드 도구: Gradle 9.2.1
- ORM: Spring Data JPA + Hibernate 6.5.2.Final (Jakarta 패키지)
- DB 드라이버: MySQL Connector/J (Spring BOM 관리)

### 1.2 인프라 및 CI/CD 스택

- 프로덕션: AWS EC2 + AWS RDS (MySQL 8.0)
- 스테이징: WSL2 Docker Compose (RAM 128GB, 192.168.124.100) + Redis 전용 서버 (N100, RAM 16GB, 192.168.124.101)
- CI/CD 오케스트레이션: GitLab → Jenkins Pipeline (Declarative) → Docker Hub → Nginx Blue/Green
- 컨테이너: Docker (eclipse-temurin:21-jre-jammy 기반), Docker Compose v3.8
- 네트워크 보안: OpenVPN (1194 UDP), 2중 NAT, oauth2-proxy

### 1.3 관측성 스택

- 메트릭: Prometheus + Grafana (Micrometer Prometheus 2.1.0)
- 분산 추적: Jaeger 1.76.0 (OpenTelemetry Java Agent 직접 주입, OTLP HTTP/protobuf 프로토콜)
- 로그: ELK 9.3.0 (Filebeat → Elasticsearch → Kibana), Logstash Logback Encoder 8.0
- 알림: AlertManager 0.25.0
- 부하테스트 저장: InfluxDB 1.8 (k6 결과 time-series 저장)

---

## 2. MSA 서비스 구성

### 2.1 모듈 구조

본 프로젝트는 6개의 독립 마이크로서비스와 1개의 공통 모듈로 구성됩니다.

- **scg-app** (Spring Cloud Gateway, Kotlin/WebFlux): API 라우팅, JWT 인증, Rate Limiting, Circuit Breaker, Bulkhead, Retry, 보안 헤더 세정
- **user-app** (Spring MVC, Java): 회원 관리, JWT 발급/갱신 (JJWT 0.12.6)
- **waitingroom-app** (Spring WebFlux, Kotlin/Coroutines): 대기열 토큰 발급 및 검증, DB 기반 ACTIVE 토큰 관리
- **concert-app** (Spring MVC, Java): 공연/좌석 조회, Redis + Caffeine 2-tier 캐싱, 낙관적 락(@Version)
- **booking-app** (Spring MVC, Java): 좌석 예매, Redisson 분산락(3.27.2) 동시성 제어, Saga 보상 트랜잭션 발행
- **payment-app** (Spring MVC, Java): 결제 처리, PG 연동, 멱등성 보장, CancelFailedRetryScheduler (보상 최종 안전망)
- **common-module** (Java Library): JPA 엔티티, 공통 예외 처리(GlobalExceptionHandler + ErrorCode Enum), Micrometer 메트릭

### 2.2 Database per Service

각 마이크로서비스는 독립된 MySQL 데이터베이스를 사용합니다:

- user-app → `ticketing_user`
- waitingroom-app → `ticketing_waitingroom`
- concert-app → `ticketing_concert`
- booking-app → `ticketing_booking`
- payment-app → `ticketing_payment`

### 2.3 서비스 간 통신

- 동기 통신: REST API (서비스 간 직접 HTTP 호출)
  - booking-app → concert-app: `INTERNAL_CLIENTS_CONCERT_BASE_URL`
  - booking-app → waitingroom-app: `INTERNAL_CLIENTS_WAITINGROOM_BASE_URL`
  - payment-app → booking-app: `BOOKING_APP_URL`
  - payment-app → concert-app: `CONCERT_APP_URL`
- 트랜잭션 전략: Saga 패턴 — Choreography 방식 (별도 오케스트레이터 없이 각 서비스가 보상 트랜잭션 직접 수행)
- 분산락: Redisson (booking-app, 좌석 단위 락)
- 낙관적 락: JPA @Version (concert-app, 재고 차감)

---

## 3. 배포 아키텍처

### 3.1 CI/CD 파이프라인

전체 배포 과정은 Jenkins Declarative Pipeline으로 자동화되어 있으며, 모듈별 선택 빌드를 지원합니다.

1. **Source**: 개발자가 feature/ 브랜치에서 작업 후 GitLab에 MR 생성 → main 병합 시 Jenkins 빌드 트리거
2. **Build**: `./gradlew :${TARGET_MODULE}:clean :${TARGET_MODULE}:build -x test` → Docker 이미지 빌드 → Docker Hub 푸시 (`dktlfem/ci-cd-test:${MODULE}-${BUILD_NUMBER}`)
3. **Deploy (AWS EC2)**: SSH로 EC2 접속 → docker-compose.yml/nginx.conf 전송 → Blue/Green 전환
4. **Deploy (Staging)**: SSH로 홈 서버 접속(192.168.124.100:2222) → Docker Hub pull → docker compose up -d

### 3.2 Blue/Green 무중단 배포

EC2 인스턴스에서 Docker Compose를 사용하여 2개의 동일한 환경을 운영하고, Nginx 리버스 프록시를 통해 트래픽을 전환합니다.

- **Active 서버 식별**: nginx.conf의 upstream 설정을 파싱하여 현재 Active 서버(Blue 또는 Green)를 자동 결정
- **Next 서버 구동**: 비활성 환경에 새 버전을 배포하고, 20초 Health Check 통과 확인
- **무중단 전환**: `docker exec nginx_proxy nginx -s reload` 명령으로 Nginx를 재시작 없이 트래픽 전환
- **이전 버전 정리**: 전환 완료 후 이전 Active 컨테이너 중지

### 3.3 환경별 아키텍처 차이

**프로덕션 (AWS EC2)**: 앱 서비스만 운영, Nginx가 Blue/Green 라우팅 담당

| 서비스 | Blue 포트 | Green 포트 |
|--------|-----------|------------|
| user-app | 8081 | 8082 |
| waitingroom-app | 8085 | 8086 |
| concert-app | 8087 | 8088 |
| booking-app | 8089 | 8090 |
| payment-app | 8091 | 8092 |

**스테이징 (홈 서버)**: 앱 서비스 + 관측성 스택 + CI/CD 도구 전체 운영

- admin-gateway (Nginx Alpine, 포트 8080): 단일 진입점
- oauth2-proxy (v7.6.0): GitLab OAuth 인증 게이트 (Grafana, Prometheus, Kibana, Jenkins, AlertManager 보호)
- scg-app: 포트 8090 (host) → 8080 (container)
- Docker 네트워크 3중 분리: `dev-network` (앱/CI), `monitoring-network` (관측성), `ticketing-network` (Prometheus/AlertManager)

### 3.4 Nginx 라우팅

**프로덕션 (AWS EC2)**:

- 외부 라우트: `/user/**`, `/waitingroom/**`, `/concert/**`, `/booking/**`, `/payment/**` → 각 서비스 upstream (300s timeout, WebSocket 지원)
- 내부 라우트: `/internal/v1/seats`, `/internal/v1/waiting-room`, `/internal/v1/reservations` → 서비스 간 직접 호출용 (60s timeout)
- 기본 응답: 404
- HTTP 설정: HTTP/1.1, keepalive 32

**스테이징 (홈 서버 gateway.conf)**:

- `/api/**` → Spring Cloud Gateway (scg:8080)
- `/grafana/**`, `/prometheus/**`, `/kibana/**`, `/jenkins/**`, `/alertmanager/**` → 각 관측성/CI 도구 (oauth2-proxy 인증 필수)
- `/jaeger/**` → Jaeger UI (인증 없이 접근 가능)
- `/oauth2/**` → oauth2-proxy 인증 엔드포인트

---

## 4. Gateway Resilience 아키텍처 (scg-app)

### 4.1 Rate Limiting

Redis 기반 분산 Rate Limiter로 서비스별 차등 적용:

- user/concert: 30 req/s, burst 50
- booking: 20 req/s, burst 40
- waitingroom: 100 req/s, burst 200 (대기열 특성)
- payment: 5 req/s, burst 10 (PG SLA 보호)

### 4.2 Circuit Breaker

서비스별 독립 CB 정책 적용:

- 감시 상태코드: 500, 502, 503, 504 (booking은 500 제외 — 비즈니스 예외 보호)
- Fallback Method로 안정적인 응답(Fail-fast) 보장

### 4.3 Retry / Bulkhead / Timeout

- Retry: GET/HEAD만, 3회, 지수 백오프 50ms→500ms (factor 2)
- Bulkhead: 기본 20 동시 호출, payment 10으로 제한
- Timeout: 기본 10s, booking 15s (분산락 대기 고려), payment 10s (PG SLA)

### 4.4 보안

- JWT 인증: 모든 요청에 JWT 검증 적용 (제외: `/actuator/**`, `/fallback/**`)
- 보안 헤더 세정 (ADR-0007): `Auth-Passport`, `Auth-User-Id`, `Internal-Token`, `Auth-Queue-Token` 제거
- 대기열 토큰 이원 검증 (ADR-0008): Gateway에서 UUID 포맷 검증, booking-app에서 상태(ACTIVE/만료/userId-eventId 일치) 검증
