# 🎟️ 대규모 트래픽 대응 MSA 티켓팅 시스템

> Spring Cloud Gateway 기반 API Gateway + 6개 마이크로서비스로 구성된 고가용성 티켓팅 플랫폼
>
> Redisson 분산락으로 좌석 동시성을 제어하고, Saga 패턴(Choreography)으로 예매-결제 간 보상 트랜잭션을 보장합니다.

---

## 1. 핵심 성과

- **Gateway Hardening**: Resilience4j Circuit Breaker / Bulkhead / Retry + Redis Rate Limiter를 SCG에 통합 적용하여 장애 전이 차단
- **동시성 제어**: Redisson 분산락(booking-app)으로 50 VUs 동시 좌석 예매 시 정확히 1건만 성공하도록 보장
- **Saga 보상 트랜잭션**: 결제 실패 시 PG 취소 API 호출 → 좌석 상태 자동 롤백 (CancelFailedRetryScheduler로 최종 안전망 구현)
- **k6 부하테스트 11개 시나리오**: Rate Limiter, Circuit Breaker, Bulkhead, JWT 공격 방어, Soak, E2E 풀플로우 등 체계적 검증 (8 PASS / 3 FAIL)
- **관측성**: Prometheus + Grafana + Jaeger (OTel Agent) + ELK 9.3.0 + InfluxDB 1.8로 전 구간 실시간 모니터링 및 분산 추적

---

## 2. 기술 스택

| 계층 | 기술 | 버전 |
|------|------|------|
| Language | Java 21, Kotlin 1.9.22 | Eclipse Temurin 21 |
| Framework | Spring Boot, Spring Cloud Gateway | 3.4.0 / 2024.0.0 |
| Build | Gradle | 9.2.1 |
| Database | MySQL 8.0 (AWS RDS) | — |
| Cache & Lock | Redis, Redisson | 3.27.2 |
| Resilience | Resilience4j (CB, Bulkhead, Retry) | 2.1.0 |
| Auth | Spring Security + JJWT | 0.12.6 |
| Observability | Prometheus, Grafana, Jaeger 1.76.0, ELK 9.3.0, InfluxDB 1.8 | — |
| CI/CD | GitLab → Jenkins → Docker → Nginx (Blue/Green) | — |
| Load Test | k6 | v1.5.0 |
| Container | Docker (Compose v3.8) | Temurin 21-jre-jammy |

---

## 3. 시스템 아키텍처

```
                    ┌───────────────────────────────┐
                    │       Spring Cloud Gateway    │
                    │          (scg-app)            │
                    │  Rate Limit · CB · Bulkhead   │
                    │  JWT Auth · Queue Token Check │
                    └─────────┬─────────────────────┘
                              │
          ┌───────────────────┼────────────────────┐
          │                   │                    │
    ┌─────▼─────┐    ┌────────▼───────┐   ┌────────▼───────┐
    │ user-app  │    │ concert-app    │   │waitingroom-app │
    │ JWT 발급   │    │ 공연 조회/캐싱     │  │ 대기열 관리       │
    │ (MVC)     │    │ (MVC + Redis)  │   │ (WebFlux)      │
    └───────────┘    └────────────────┘   └────────────────┘
                               │
                    ┌──────────▼──────────┐
                    │    booking-app      │
                    │  예매 + Redisson 락   │
                    │  Saga Choreography  │
                    └──────────┬──────────┘
                               │ HTTP (보상 트랜잭션)
                    ┌──────────▼──────────┐
                    │    payment-app      │
                    │  결제 + PG 연동       │
                    │  멱등성 + 보상 재시도   │
                    └─────────────────────┘
```

### 3.1 서비스별 역할

| 서비스 | 기술 스택 | 핵심 역할 |
|--------|-----------|-----------|
| **scg-app** | Spring Cloud Gateway (WebFlux, Kotlin) | API 라우팅, JWT 인증, Rate Limiting, Circuit Breaker, Bulkhead, Retry, 보안 헤더 세정 |
| **user-app** | Spring MVC (Java) | 회원 관리, JWT 발급/갱신 |
| **waitingroom-app** | Spring WebFlux (Kotlin, Coroutines) | 대기열 토큰 발급/검증, Redis Sorted Set 기반 순번 처리 |
| **concert-app** | Spring MVC (Java) | 공연/좌석 조회, Redis + Caffeine 2-tier 캐싱, 낙관적 락(@Version) |
| **booking-app** | Spring MVC (Java) | 좌석 예매, Redisson 분산락 동시성 제어, Saga 보상 트랜잭션 발행 |
| **payment-app** | Spring MVC (Java) | 결제 확인/취소, PG 연동, 멱등성 보장, CancelFailedRetryScheduler |
| **common-module** | Java Library | JPA 엔티티, 공통 예외 처리, Micrometer Prometheus 메트릭 |

### 3.2 서비스 간 통신

- **동기 통신**: REST API (서비스 간 직접 HTTP 호출)
- **트랜잭션 전략**: Saga 패턴 — Choreography 방식 (별도 오케스트레이터 없이 각 서비스가 보상 트랜잭션 직접 수행)
- **동시성 제어**: Redisson 분산락 (booking-app) + JPA @Version 낙관적 락 (concert-app)

---

## 4. Gateway Resilience 설정 (scg-app)

### Rate Limiting (Redis 기반)

| 서비스 | replenishRate | burstCapacity | 비고 |
|--------|---------------|---------------|------|
| user | 30 req/s | 50 | — |
| concert | 30 req/s | 50 | — |
| booking | 20 req/s | 40 | — |
| waitingroom | 100 req/s | 200 | 대기열 특성상 높은 허용 |
| payment | 5 req/s | 10 | PG SLA 보호 |

### Circuit Breaker / Retry / Bulkhead

- **Circuit Breaker**: 서비스별 독립 정책 (500/502/503/504 감지, booking은 500 제외 — 비즈니스 예외 보호)
- **Retry**: GET/HEAD만, 3회 재시도, 지수 백오프 50ms→500ms
- **Bulkhead**: 기본 20 동시 호출, payment는 10으로 제한
- **Timeout**: 기본 10s, booking은 15s (분산락 대기 고려)

---

## 5. 인프라 및 배포

### 5.1 CI/CD 파이프라인

```
GitLab (Push/MR)
  → Jenkins Pipeline (Declarative, 모듈 선택 파라미터)
    → Gradle Build (모듈별 선택 빌드, -x test)
      → Docker Build & Push (Docker Hub: dktlfem/ci-cd-test:{module}-{build})
        → AWS EC2: Blue/Green 전환 (Nginx -s reload)
        → Home Staging: docker compose up -d (전체 스택)
```

### 5.2 Blue/Green 무중단 배포

| 서비스 | Blue 포트 | Green 포트 |
|--------|-----------|------------|
| user-app | 8081 | 8082 |
| waitingroom-app | 8085 | 8086 |
| concert-app | 8087 | 8088 |
| booking-app | 8089 | 8090 |
| payment-app | 8091 | 8092 |

- Nginx upstream 설정 기반으로 Active 서버 자동 판별
- 새 버전 배포 후 Health Check (20s) 통과 시 `nginx -s reload`로 무중단 전환

### 5.3 홈 스테이징 서버

- **메인 서버**: WSL2 기반 Docker Compose (RAM 128GB, IP: 192.168.124.100)
- **Redis 전용**: N100 미니PC (RAM 16GB, IP: 192.168.124.101)
- **네트워크**: OpenVPN (1194 UDP) 강제 접속, 2중 NAT
- **보안**: GitLab OAuth2 + 2중 MFA, oauth2-proxy(v7.6.0)로 Grafana/Prometheus/Kibana/Jenkins/AlertManager 접근 제한
- **단일 진입점**: admin-gateway (Nginx Alpine, 포트 8080) → `/api/**`는 SCG로, `/grafana/**` 등은 관측성 도구로 라우팅
- **Docker 네트워크**: 3중 분리 — `dev-network` (앱/CI), `monitoring-network` (관측성), `ticketing-network` (Prometheus↔AlertManager)
- **분산 추적**: OpenTelemetry Java Agent를 전 서비스에 주입 (`-javaagent:/otel/opentelemetry-javaagent.jar`) → Jaeger OTLP로 전송
- **Database per Service**: `ticketing_user`, `ticketing_waitingroom`, `ticketing_concert`, `ticketing_booking`, `ticketing_payment`

---

## 6. k6 부하테스트 결과 (2026-03-31)

| # | 시나리오 | 검증 항목 | 결과 | 비고 |
|---|---------|-----------|------|------|
| S1 | Rate Limiter | Redis 기반 요청 제한 동작 | ✅ PASS | 86.3% 정상 제한 |
| S2 | Circuit Breaker | 장애 시 CB OPEN → Fallback | ✅ PASS | Fallback 70.2% |
| S3 | Bulkhead | 동시 호출 수 제한 | ✅ PASS | BH거절 P95: 105.8ms |
| S4 | Filter Latency | Gateway 필터 체인 지연 | ✅ PASS | — |
| S5 | JWT Attack | 위조 JWT 100% 거절 | ❌ FAIL | 401율 100% (로직 정상), P95 임계값 초과 |
| S6 | Soak (10분) | 장기 부하 안정성 | ❌ FAIL | 에러율 2.17%, concert-app CB 9분 14초에 개방 |
| S7 | Internal Block | 내부 API 외부 차단 | ✅ PASS | — |
| S8 | Seat Concurrency | 분산락 동시성 (50VUs→1성공) | ❌ FAIL | Bulkhead 30건 정상 보호, 대기열 토큰 만료 이슈 |
| S9 | E2E Ticketing | 풀플로우 부하 | ✅ PASS | — |
| S10 | Payment Idempotency | 결제 멱등성 보장 | ✅ PASS | — |
| S11 | Saga Compensation | 보상 트랜잭션 검증 | ✅ PASS | — |

### FAIL 시나리오 분석 및 개선 방향

- **S5 (JWT Attack)**: 보안 로직은 100% 정상 동작 (위조 JWT 전수 거절). Gateway의 JWT 검증 P95 응답시간(90.5ms)이 임계값(50ms)을 초과. HMAC 검증 연산 최적화 또는 임계값 현실 조정 필요
- **S6 (Soak)**: 9분 14초 시점에서 concert-app의 Circuit Breaker가 개방되며 503 응답 발생. 장기 부하 시 JVM 힙 메모리 증가 또는 Redis 연결 풀 고갈 추정. GC 튜닝 및 커넥션 풀 사이즈 조정 계획
- **S8 (Seat Concurrency)**: Redisson 분산락 + Bulkhead 보호 로직은 정상 동작 확인. 대기열 토큰 만료 시간(3시간)이 테스트 반복 실행 중 경과하여 `consumeIfActive` 실패. 토큰 TTL 연장 또는 동적 갱신 로직 도입 필요

---

## 7. 프로젝트 구조

```
ci-cd-test/
├── scg-app/              # Spring Cloud Gateway (Kotlin, WebFlux)
├── user-app/             # 회원 관리 + JWT
├── waitingroom-app/      # 대기열 관리 (Kotlin, WebFlux)
├── concert-app/          # 공연/좌석 조회 + 캐싱
├── booking-app/          # 예매 + 분산락 + Saga
├── payment-app/          # 결제 + PG + 멱등성
├── common-module/        # 공통 엔티티/유틸/메트릭
├── load-test/            # k6 부하테스트 스크립트 + 결과
├── perf-analyzer/        # 성능 분석 도구
├── docs/                 # 아키텍처/API/운영/보안 문서 (30+)
├── Jenkinsfile           # CI/CD 파이프라인 정의
├── docker-compose.yml    # Blue/Green 배포 구성
├── nginx.conf            # 리버스 프록시 + 업스트림 라우팅
├── ARCHITECTURE.md       # 기술 스택 및 배포 아키텍처
└── POLICY.md             # Git/배포/코드 품질 정책
```

---

## 8. 문서 가이드

상세 설계 문서는 `docs/` 디렉토리에 체계적으로 구성되어 있습니다.

| 카테고리 | 주요 문서 | 설명 |
|----------|-----------|------|
| **아키텍처** | `architecture/system-overview.md` | 시스템 다이어그램 (Mermaid 6종) |
| **API** | `api/api-spec.md` | 전체 외부/내부 API 명세 |
| **서비스 설계** | `services/booking/seat-locking-design.md` | Redisson 분산락 설계 |
| | `services/payment/payment-architecture.md` | Saga + 보상 트랜잭션 설계 |
| | `services/scg/gateway-design.md` | Gateway 필터 체인 설계 |
| **데이터** | `data/database-cache-design.md` | ERD, 인덱스, 캐싱 전략 |
| **보안** | `security/security-design.md` | 인증/인가, 위협 분석 |
| **관측성** | `observability/observability.md` | 로그/메트릭/트레이스 전략 |
| **운영** | `operations/incident-runbook.md` | 18개 장애 시나리오 대응 |
| **성능** | `performance/sli-slo.md` | SLI/SLO 정의 |

---

## 9. 실행 방법

### 사전 요구사항

- JDK 21 (Eclipse Temurin 권장)
- Docker & Docker Compose
- MySQL 8.0 (또는 AWS RDS)
- Redis 6+

### 로컬 빌드

```bash
# 전체 빌드 (테스트 제외)
./gradlew clean build -x test

# 특정 모듈만 빌드
./gradlew :booking-app:clean :booking-app:build -x test
```

### k6 부하테스트 실행

```bash
cd load-test/scripts/k6

# 전체 시나리오 실행
./run-tests.sh all

# 단일 시나리오 실행
./run-tests.sh 8    # S8: 좌석 동시성 테스트
```

---

## 10. ADR (Architecture Decision Records)

주요 아키텍처 의사결정 기록:

| ID | 결정 사항 | 요약 |
|----|-----------|------|
| ADR-0003 | LocalRateLimiter → RedisRateLimiter | 단일 인스턴스 한계 극복, Gateway 레벨 분산 Rate Limiting |
| ADR-0007 | Security Headers Phase 3 | Auth-Passport, Auth-User-Id, Queue-Token, Internal-Token 세정 |
| ADR-0008 | Queue Token Validation 이원화 | Gateway: UUID 포맷 검증 / booking-app: 상태(ACTIVE/만료) 검증 |
| ADR-0009 | Spring Cloud Config Client | scg-app 설정 외부화 (fail-fast: false로 독립 기동 보장) |
| ADR-0010 | HTTP Timeout 서비스별 차등 | booking 15s (분산락 대기), payment 10s (PG SLA), 기본 10s |
