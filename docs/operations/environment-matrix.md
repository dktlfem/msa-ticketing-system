---
title: 환경 구성 매트릭스
status: reviewed-draft
last-verified: 2026-04-01
scope: 환경 구조 설명서 (네트워크 자산 목록 아님)
note: |
  이 문서에 실제 IP, 공인 호스트, 비밀번호, 토큰, 계정명은 포함하지 않는다.
  다만 배포 구조 설명에 필요한 내부 서비스 포트/슬롯 포트는 포함할 수 있다.
  운영 값은 Jenkins Credentials Store 또는 private ops docs에서 관리한다.
---

# 환경 구성 매트릭스

## 1. 문서 범위와 전제

**이 문서가 다루는 것:**
- Local / Home Staging / AWS Deploy 3개 환경의 역할과 구조
- 서비스별 인프라 매핑 (DB schema, Redis 용도, 모니터링)
- 환경변수 관리 전략과 주입 경로
- 배포 전략 (Blue/Green) 및 슬롯 포트 매핑

**이 문서가 다루지 않는 것:**
- 실제 IP, 공인 호스트, 비밀번호, 토큰, 계정명 등 운영 값 → private ops docs 참조
- 모니터링 대시보드 구성 상세 → Grafana/Kibana 별도 문서
- k6 부하테스트 시나리오 → `load-test/scripts/k6/README.md` 참조

**용어 원칙:**
- "production" 표현을 사용하지 않는다. 실제 외부 트래픽을 받지 않는 학습/포트폴리오 환경이다.
- 환경명은 역할 기준으로 명명한다: `local`, `home-staging`, `aws-deploy`.

---

## 2. 환경 역할 개요

| 환경 | 역할 | 용도 | 접근 방식 |
|------|------|------|---------|
| **local** | 개발자 로컬 | IDE(IntelliJ) 기반 개발, 단위 테스트, H2/Embedded 테스트 | 직접 접근 |
| **home-staging** | 통합 테스트 + 모니터링 + CI/CD | Docker Compose 기반 전체 서비스 구동, 모니터링 스택, GitLab, Jenkins | VPN 필수 |
| **aws-deploy** | Blue/Green 배포 검증 | Jenkins 자동 배포 타겟, nginx 프록시 기반 트래픽 전환 | SSH (Jenkins 자동) |

---

## 3. Home Staging 환경 상세

### 3.1 메인 서버 (home-staging)

**하드웨어:** WSL2 기반 Docker Compose, RAM 128GB

**구성 요소:**

| 카테고리 | 서비스 | 비고 |
|---------|--------|------|
| **마이크로서비스** | user, waitingroom, concert, booking, payment (각 Blue/Green) | docker-compose.yml |
| **게이트웨이** | scg-app (Spring Cloud Gateway) | 별도 compose 파일, Netty 기반 |
| **내부 프록시** | nginx_proxy | Blue/Green 전환, 내부 라우팅 |
| **CI/CD** | GitLab (self-hosted), Jenkins | OAuth/SSO + 다단계 인증 |
| **모니터링** | Prometheus, Grafana | 메트릭 수집 + 시각화 |
| **분산 추적** | Jaeger (OTLP) | 100% 샘플링 (개발 환경) |
| **로그 파이프라인** | ELK (Elasticsearch, Logstash/Filebeat, Kibana) | 서비스별 로그 집계 |
| **알림** | AlertManager | 임계치 기반 알림 |
| **인증 프록시** | oauth2-proxy | 내부 서비스 접근 제한 |

**접근 보안 원칙:**
- 사설망 + VPN 기반 접근 통제 (2중 NAT 구조)
- OAuth/SSO + 다단계 인증 강제
- 인증 프록시(oauth2-proxy)를 통한 내부 모니터링 서비스 접근 제한

### 3.2 Redis 전용 노드 (redis-dedicated)

**하드웨어:** 미니PC (저전력 SoC, RAM 16GB)

**분리 이유:** Redis 워크로드(대기열 Sorted Set, 분산락, 캐시, 멱등성 키)를
메인 서버의 JVM/DB 워크로드와 물리적으로 분리하여 자원 경합을 방지한다.

**Redis 용도 (서비스별):**

| 서비스 | Redis 용도 | 비고 |
|--------|-----------|------|
| waitingroom-app | 대기열 Sorted Set + 토큰 저장 | 가장 높은 Redis 부하 |
| booking-app | 분산락 (Redisson) | `reservation:lock:seat:{seatId}` |
| payment-app | 멱등성 키 (24h TTL) | `payment:idempotency:{key}` |
| concert-app | 캐시 (Cache-Aside) | 공연/좌석 정보 |
| user-app | 리프레시 토큰 저장 | — |
| scg-app | Rate Limiting Token Bucket | 라우트별 요청 제한 |

**접근:** VPN 연결 후, 제한된 관리 경로를 통해 내부 노드에 접근.

> **Known Issue KI-1:** waitingroom-app의 `src/main/resources` Redis 호스트가
> 다른 5개 서비스와 다른 주소로 하드코딩되어 있다. 의도된 분리인지 설정 오류인지 확인 필요.
> — 소스: `waitingroom-app/src/main/resources/application.properties`

---

## 4. AWS Deploy 환경 상세

### 4.1 배포 구조

```
Jenkins (home-staging)
  ↓ Docker Hub push
  ↓ SCP (docker-compose.yml, nginx.conf, .env)
  ↓ SSH 원격 실행
AWS EC2 인스턴스
  ├─ docker-compose up (Blue 또는 Green)
  ├─ nginx_proxy (트래픽 전환)
  └─ Health check 후 Old slot 중지
```

### 4.2 Blue/Green 배포 전략

**소스:** `Jenkinsfile`, `docker-compose.yml`, `nginx.conf`

| 단계 | 동작 | 비고 |
|------|------|------|
| 1 | 새 이미지를 Docker Hub에 push | `{registry}/{module}-{buildNumber}` |
| 2 | `.env` + `docker-compose.yml` + `nginx.conf`를 EC2에 SCP | 환경변수는 `.env`로 주입 |
| 3 | 새 슬롯(Blue 또는 Green) `docker-compose up -d` | 기존 슬롯은 아직 활성 |
| 4 | 고정 대기 후 컨테이너 존재 확인 | `docker ps --format` |
| 5 | nginx upstream을 새 슬롯으로 `sed` 교체 | `nginx -s reload` (무중단) |
| 6 | 이전 슬롯 `docker-compose stop` | — |

**서비스별 슬롯 포트 매핑 (docker-compose.yml 기준):**

| 서비스 | Blue 포트 | Green 포트 | 컨테이너 내부 |
|--------|:---------:|:----------:|:----------:|
| user-app | 8081 | 8082 | 8080 |
| waitingroom-app | 8085 | 8086 | 8080 |
| concert-app | 8087 | 8088 | 8080 |
| booking-app | 8089 | 8090 | 8080 |
| payment-app | 8091 | 8092 | 8080 |

> **Known Issue KI-2:** Health check가 `sleep 20`(EC2) / `sleep 30`(staging) 고정 대기 후
> `docker ps` 확인만 수행한다. HTTP readiness probe를 사용하지 않으므로
> 서비스가 기동 중이지만 아직 요청을 받을 수 없는 상태에서 트래픽이 전환될 수 있다.
> — 소스: `Jenkinsfile`

### 4.3 scg-app 배포 (별도 경로)

scg-app은 AWS EC2가 아닌 home-staging 서버에 직접 배포된다.
Docker Hub에서 이미지를 pull한 후 로컬 compose로 기동한다.

> **Assumption AS-1:** scg-app이 home-staging에 배포되는 것이 의도된 아키텍처인지,
> 향후 AWS로 통합할 계획인지 확인 필요.

---

## 5. 서비스별 인프라 매핑

| 서비스 | DB Schema | Redis 용도 | SCG 타임아웃 | 특이사항 |
|--------|-----------|-----------|:----------:|---------|
| user-app | `ticketing_user` | 리프레시 토큰 | 10s (기본) | JWT 시크릿이 scg-app과 반드시 일치해야 함 |
| concert-app | `ticketing_concert` | 캐시 (Cache-Aside) | 10s (기본) | — |
| booking-app | `ticketing_booking` | 분산락 (Redisson) | **15s** | 분산락 대기 + 대기열 검증으로 기본보다 긴 타임아웃 |
| payment-app | `ticketing_payment` | 멱등성 키 (24h TTL) | **10s** | TossPayments 외부 PG 연동 |
| waitingroom-app | `ticketing_waitingroom` | 대기열 Sorted Set | 10s (기본) | WebFlux(reactive), Redis 호스트 불일치 (KI-1) |
| scg-app | 없음 (stateless) | Rate Limiting | — | Spring Cloud Gateway (Netty), Config Server 연동 (선택) |

---

## 6. 환경변수 관리 전략

### 6.1 주입 경로

```
Jenkins Credentials Store
  ↓ credentials() 바인딩
Jenkinsfile
  ↓ .env 파일 생성
EC2 (SCP 전송)
  ↓ docker-compose --env-file .env
컨테이너 환경변수
  ↓ Spring Boot 외부화 설정 우선순위
application.properties 오버라이드
```

### 6.2 공통 환경변수 (docker-compose `x-common-env`)

| 변수 | 용도 | 주입 방식 |
|------|------|---------|
| `SPRING_PROFILES_ACTIVE` | 활성 프로필 | Jenkinsfile (현재 `dev` 고정) |
| `SPRING_DATASOURCE_URL` | MySQL 접속 URL | Jenkins Credentials |
| `SPRING_DATASOURCE_USERNAME` | DB 사용자 | Jenkins Credentials |
| `SPRING_DATASOURCE_PASSWORD` | DB 비밀번호 | Jenkins Credentials |
| `SPRING_DATA_REDIS_HOST` | Redis 호스트 | Jenkins Credentials |
| `SPRING_DATA_REDIS_PORT` | Redis 포트 | Jenkins Credentials |
| `SPRING_DATA_REDIS_PASSWORD` | Redis 비밀번호 | Jenkins Credentials |
| `TZ` | 타임존 | 하드코딩 (`Asia/Seoul`) |
| `BUILD_NUMBER` | 빌드 번호 | Jenkins 자동 |

### 6.3 서비스 전용 환경변수

| 변수 | 서비스 | 비고 |
|------|--------|------|
| `JWT_SECRET` / `JWT_SECRET_KEY` | user-app, scg-app | **두 서비스 간 반드시 동일해야 함** |
| `TOSS_PAYMENTS_SECRET_KEY` | payment-app | PG 연동 시크릿 |
| `TOSS_PAYMENTS_BASE_URL` | payment-app | PG API 엔드포인트 |
| `BOOKING_APP_URL`, `CONCERT_APP_URL` | payment-app | 내부 HTTP 클라이언트 대상 |
| `CONFIG_SERVER_URI` | scg-app | Spring Cloud Config (선택) |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | scg-app | Jaeger 트레이스 수집 |
| `QUEUE_TOKEN_VALIDATION_ENABLED` | scg-app | 대기열 토큰 검증 on/off |

> **Known Issue KI-3:** `application.properties`에 fallback default가 포함되어 있다.
> 환경변수가 주입되지 않을 경우 약한 기본값(테스트용 비밀번호, placeholder 시크릿)이
> 적용될 수 있다. `.env.example` 파일이 존재하지 않아 로컬 개발 환경 셋업 시
> 어떤 변수를 설정해야 하는지 문서화되어 있지 않다.
> — 소스: 각 서비스 `src/main/resources/application.properties`

> **Known Issue KI-4:** profile 분리가 없다. `application-dev.yml`, `application-staging.yml` 등
> 환경별 설정 파일이 존재하지 않으며, 단일 `application.properties` + 환경변수 오버라이드만
> 사용한다. 환경 간 설정 차이가 명시적이지 않아 실수 여지가 있다.

---

## 7. 네트워크 보안 구조 (토폴로지)

> 아래는 통제 원칙과 구조만 기술한다. 실제 주소, 포트, 계정은 private ops docs 참조.

```
[외부 인터넷]
     │
     ▼
[사설망 진입점] ─── 다단계 NAT ───┐
                                  │
                          [VPN 게이트웨이]
                                  │
                    ┌─────────────┼──────────────┐
                    ▼             ▼              ▼
             [home-staging]  [redis-dedicated]  [CI/CD 서비스]
                                                    │
                                              자동화된 배포 경로
                                                    │
                                               [aws-deploy]
```

**통제 원칙:**

| 원칙 | 적용 |
|------|------|
| 사설망 기반 접근 통제 | 다단계 NAT + VPN 강제. 외부에서 직접 접근 불가 |
| OAuth/SSO + 다단계 인증 | CI/CD(GitLab, Jenkins) 접근 시 필수 |
| 인증 프록시 | 내부 모니터링 서비스(Prometheus, Grafana, Jaeger, Kibana) 접근 제한 |
| 제한된 관리 경로 | redis-dedicated는 home-staging 경유로만 접근 가능 |
| 자동화된 배포 경로 | aws-deploy 접근은 Jenkins 파이프라인을 통한 SSH 자동화만 허용 |

---

## 8. Dockerfile 및 컨테이너 구성

**소스:** `Dockerfile`

| 항목 | 값 | 비고 |
|------|-----|------|
| 베이스 이미지 | `eclipse-temurin:21-jre-jammy` | Java 21 런타임 전용 |
| 빌드 방식 | 단일 스테이지 (JAR 복사) | `ARG JAR_PATH`로 주입 |
| 노출 포트 | 8080 (컨테이너 내부) | docker-compose에서 외부 포트 매핑 |
| JVM 옵션 | 미설정 | 힙 사이즈, GC 튜닝 없음 |
| 재시작 정책 | `unless-stopped` | docker-compose.yml |

> **Assumption AS-2:** JVM 옵션(힙 사이즈, GC 알고리즘)이 미설정이므로
> 컨테이너 메모리 제한에 따라 기본값이 적용된다. 부하테스트 시 OOM이 발생할 경우
> `JAVA_OPTS` 환경변수 추가가 필요할 수 있다.

---

## 9. Known Issues & Assumptions 종합

### Known Issues

| ID | 항목 | 영향 | 소스 |
|----|------|------|------|
| KI-1 | waitingroom-app Redis 호스트 불일치 | 다른 5개 서비스와 다른 주소로 하드코딩. 의도된 분리인지 설정 오류인지 불명확 | `waitingroom-app/src/main/resources/application.properties` |
| KI-2 | Health check가 sleep 기반 | readiness probe 미사용. 서비스 미기동 상태에서 트래픽 전환 가능 | `Jenkinsfile` |
| KI-3 | fallback default에 약한 기본값 포함 | 환경변수 미주입 시 테스트용 비밀번호/placeholder가 적용됨. `.env.example` 미존재 | 각 서비스 `application.properties` |
| KI-4 | profile 분리 부재 | `application-{env}.yml` 없음. 단일 properties + 환경변수 오버라이드만 사용 | 모든 서비스 `src/main/resources/` |
| KI-5 | Jenkins 매 빌드 docker.io 설치 | `apt-get install -y docker.io` 매번 실행. 빌드 시간 증가 + 네트워크 의존 | `Jenkinsfile` |

### Assumptions

| ID | 항목 | 검증 필요 사항 |
|----|------|--------------|
| AS-1 | scg-app home-staging 배포가 의도된 구조인지 | AWS 통합 계획 여부 확인 필요 |
| AS-2 | JVM 옵션 미설정 | 부하테스트 시 OOM 발생 가능. `JAVA_OPTS` 추가 필요 여부 검토 |
| AS-3 | nginx_proxy 단일 인스턴스 | nginx 장애 시 전체 서비스 불가. 이중화 계획 여부 미확인 |

---

## 10. 검증 소스 파일 목록

| 파일 | 검증 내용 |
|------|----------|
| `docker-compose.yml` | 서비스 구성, Blue/Green 포트 매핑, `x-common-env`, 네트워크 |
| `Dockerfile` | 베이스 이미지, JAR 주입, 포트, JVM 옵션 미설정 확인 |
| `nginx.conf` | upstream 정의, 내부/외부 라우팅, 타임아웃 |
| `Jenkinsfile` | 배포 파이프라인, credentials 바인딩, health check, docker.io 설치 |
| `booking-app/src/main/resources/application.properties` | DB schema, Redis, fallback default |
| `concert-app/src/main/resources/application.properties` | DB schema, Redis, 모니터링 |
| `payment-app/src/main/resources/application.properties` | TossPayments 연동, 내부 클라이언트 URL |
| `user-app/src/main/resources/application.properties` | JWT 설정, Resilience4j, Redis |
| `waitingroom-app/src/main/resources/application.properties` | Redis 호스트 불일치 확인 (KI-1), reactive 설정 |
| `scg-app/src/main/resources/application.yml` | 라우트, Rate Limit, CB, JWT, OTEL, Config Server |
| `settings.gradle` | 활성 모듈 목록 확인 |
