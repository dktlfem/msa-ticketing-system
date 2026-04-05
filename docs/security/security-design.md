---
title: "Security Design: MSA 티켓팅 플랫폼 보안 설계"
last_updated: 2026-03-22
author: "민석"
reviewer: ""
---

## 목차
- [Background](#background)
- [Problem](#problem)
- [Current Design](#current-design)
- [Risks](#risks)
- [Controls / Mitigations](#controls-mitigations)
- [Planned Improvements](#planned-improvements)
- [Trade-offs](#trade-offs)
- [Failure Scenarios](#failure-scenarios)
- [Observability](#observability)

# Security Design: MSA 티켓팅 플랫폼 보안 설계

> **범위**: 이 문서는 현재 구현된 보안 통제 항목과 그 근거, 알려진 한계, 개선 계획을 다룬다.
> Rate Limiting 확장 전략은 이 문서 하단 섹션에 통합되어 있다.
> 서비스 경계별 통제 현황과 리스크 분석에 집중한다.

---

## Background

이 시스템의 공격 표면은 세 층위로 나뉜다.

1. **외부 진입점**: 인터넷에서 접근 가능한 유일한 포트 — OpenVPN(1194/UDP)
2. **운영 도구 계층**: Jenkins, Grafana, Prometheus, GitLab — VPN 내부에 위치
3. **서비스 계층**: scg-app(외부), 5개 서비스(내부), Redis, MySQL — 내부망 전용

티켓팅 플랫폼은 두 가지 보안 요구가 충돌한다.

- **결제 정합성**: 중복 승인·이중 취소·금액 위변조를 막아야 한다.
- **고가용성**: 보안 통제가 정상 결제 흐름을 차단해서는 안 된다.

현재 구현은 "복잡한 보안 제품보다 노출면 최소화"를 우선한다.

---

## Problem

현재 보안 설계에서 해결해야 할 문제:

1. **서비스 레벨 인증 없음**: SCG가 `Auth-Passport` 헤더를 downstream에 전달하지만, 각 서비스는 이 헤더가 SCG에서 온 것인지 검증하지 않는다. 내부망에서 직접 HTTP 호출 시 임의의 `Auth-Passport` 값을 주입할 수 있다. <!-- 2026-03-22 ADR-0007 Phase 3 완료 반영 -->
2. **actuator 무인증 노출**: 모든 서비스의 `/actuator/**`가 `permitAll()`로 열려있다. Prometheus 수집을 위한 것이나, 내부망에서 누구나 `/actuator/prometheus`, `/actuator/health`에 접근 가능하다.
3. **Swagger 무인증 노출**: `/swagger-ui/**`와 `/v3/api-docs/**`가 `permitAll()`이다. 내부 API 구조가 VPN 접속만으로 누구에게나 노출된다.
4. **MySQL 기본 계정 사용**: `application.properties`의 기본값이 `root / 1234`다. 환경변수 미설정 시 이 값으로 동작한다.
5. **SCG 레벨 rate limiting 없음**: waitingroom-app에만 이벤트 단위 rate limiting이 구현됐다. `/api/v1/payments/confirm` 같은 고가치 엔드포인트에 대한 IP/user 기준 rate limiting이 없다.
6. **JWT 검증 없음**: 현재 헤더 기반 사용자 식별은 신뢰 경계를 SCG로 한정하는 구조이나, SCG 자체도 JWT를 검증하지 않는다.

---

## Current Design

### 1. 네트워크 경계 (구현됨)

```
인터넷
  └── 공유기 이중 NAT
        └── 1194/UDP (OpenVPN only)
              └── VPN 터널
                    ├── 192.168.124.100 (스테이징, WSL2 + Docker Compose)
                    │     ├── admin-gateway (Nginx:8080) — 모든 외부 요청의 단일 진입점
                    │     │     ├── /api/**        → scg-app (oauth2-proxy 미적용)
                    │     │     ├── /jenkins/      → Jenkins (oauth2-proxy 적용)
                    │     │     ├── /grafana/      → Grafana (oauth2-proxy 적용)
                    │     │     ├── /prometheus/   → Prometheus (oauth2-proxy 적용)
                    │     │     ├── /alertmanager/ → Alertmanager (oauth2-proxy 적용)
                    │     │     ├── /kibana/       → Kibana (oauth2-proxy 적용)
                    │     │     └── /jaeger/       → Jaeger UI (oauth2-proxy 미적용 ⚠️)
                    │     └── MySQL (Docker 내부 네트워크 전용, 호스트 포트 미노출)
                    └── 192.168.124.101 (Redis:6379)
```

**외부에 직접 노출된 포트**: OpenVPN 1194/UDP 단 하나.

운영 도구(Jenkins, Grafana, Prometheus, Alertmanager, Kibana)는 인터넷 직접 노출이 없다. VPN 없이 접근하려면 공유기 NAT를 통과해야 하므로 IP 레벨에서 원천 차단된다.

### 1-A. Docker 네트워크 격리 구조 (구현됨)

docker-compose.yml은 3개의 Docker 네트워크를 정의해 서비스 접근 범위를 분리한다.

| 네트워크 | 목적 | 소속 컨테이너 |
|---------|------|------------|
| `dev-network` | 마이크로서비스 간 통신 전용 | scg-app, waitingroom-app, concert-app, booking-app, payment-app, user-app, mysql |
| `monitoring-network` | 관측성 스택 통신 | prometheus, grafana, jaeger, elasticsearch, kibana, filebeat, alertmanager, 각 앱(scraping용) |
| `ticketing-network` | alertmanager ↔ prometheus 전용 | alertmanager, prometheus |

```
dev-network:
  scg-app ──── waitingroom-app ──── concert-app
     │              │
  booking-app ── payment-app ──── user-app ──── mysql

monitoring-network:
  prometheus ──── grafana ──── jaeger ──── elasticsearch ──── kibana
      │
  filebeat ──── alertmanager
      │
  (각 앱 scraping 연결)

ticketing-network:
  alertmanager ──── prometheus
```

**격리 효과**: `dev-network`의 서비스들은 `monitoring-network`에 직접 참여하지 않으며, 각 앱은 두 네트워크 모두에 소속되어 Prometheus scraping과 서비스 트래픽을 분리한다. MySQL은 `dev-network` 전용이므로 monitoring 컨테이너에서 직접 접근 불가하다.

### 2. 운영 도구 인증 (구현됨)

```
VPN 접속
  └── admin-gateway (Nginx:8080)
        ├── oauth2-proxy 인증 적용 (GitLab OIDC)
        │     ├── /jenkins/      → Jenkins
        │     ├── /grafana/      → Grafana
        │     ├── /prometheus/   → Prometheus
        │     ├── /alertmanager/ → Alertmanager
        │     └── /kibana/       → Kibana
        └── oauth2-proxy 인증 미적용
              ├── /api/**        → scg-app (서비스 API, 인증 대상 아님)
              └── /jaeger/       → Jaeger UI ⚠️ (아래 주의사항 참조)
```

- **GitLab MFA**: GitLab 계정에 2FA 강제 적용
- **oauth2-proxy**: GitLab OAuth2/OIDC 플로우로 Jenkins/Grafana/Prometheus/Alertmanager/Kibana 접근 통제
- **Bitwarden**: GitLab recovery code 저장
- **OTP**: Google Authenticator / Bitwarden OTP로 관리

GitLab identity가 운영 도구 접근의 단일 진입점이다. 서비스별 로컬 계정이 없다.

#### ⚠️ Jaeger UI 인증 미적용 — 알려진 위험

`/jaeger/` 경로는 gateway.conf에서 oauth2-proxy `auth_request`를 적용하지 않는다. VPN에 접속된 사용자라면 GitLab 인증 없이 Jaeger UI에 직접 접근할 수 있다.

**위험**: Jaeger trace에는 서비스 간 요청 경로, HTTP 헤더 (Auth-Passport, Correlation-Id), 내부 API 구조, 응답 시간 등 민감한 운영 정보가 포함될 수 있다. VPN = 신뢰 경계로 수용한 트레이드오프이나, 내부 위협(insider threat) 시나리오에서는 취약하다.

**완화 방안 (planned)**: Jaeger UI에도 oauth2-proxy `auth_request` 적용. 현재 미적용 사유는 OTel Agent → Jaeger에 대한 push 경로(`/api/traces`)와 UI 경로(`/jaeger/`)를 분리해야 하는 복잡성 때문이다.

### 3. SCG 경계 통제 (구현됨)

**`InternalPathBlockGlobalFilter`**: `/internal/**` 경로 차단

```
외부 요청 → scg-app
  ├── /api/v1/** → 라우팅 허용 (user/concert/waitingroom/booking/payment 서비스)
  └── /internal/** → 404 반환 (InternalPathBlockGlobalFilter)
```

내부 API (`/internal/v1/reservations/{id}`, `/internal/v1/seats/{id}` 등)는 SCG에서 차단된다. 서비스 간 direct 호출만 허용된다.

**라우팅 규칙** (`scg-app/src/main/resources/application.properties`):

| route id | 대상 URI | predicate |
|----------|---------|-----------|
| user-service | http://user-app:8080 | /api/v1/users/** |
| concert-service | http://concert-app:8080 | /api/v1/events/**, /api/v1/schedules/**, /api/v1/seats/** |
| waitingroom-service | http://waitingroom-app:8080 | /api/v1/waiting-room/** |
| payment-service | http://payment-app:8080 | /api/v1/payments/** |

booking-app이 SCG 라우팅에 없는 것은 의도적이다. 예약은 대기열 토큰 검증 후 진입하므로 별도 경로 설계가 필요하다 (planned).

### 4. 서비스 레벨 보안 현황 (payment-app 기준)

`payment-app/global/config/SecurityConfig.java` 현재 상태:

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers(
        "/api/**",
        "/internal/**",
        "/error",
        "/actuator/**",
        "/swagger-ui/**",
        "/v3/api-docs/**"
    ).permitAll()
    .anyRequest().authenticated()
)
```

**모든 엔드포인트가 `permitAll()`이다.** Spring Security는 설치됐지만 인증 강제가 없다. 사용자 식별은 SCG가 전달하는 `Auth-Passport` 헤더에만 의존한다 (ADR-0007 Phase 2에서 `Auth-User-Id` → `Auth-Passport` 전환 완료). <!-- 2026-03-22 ADR-0007 Phase 3 완료 반영 -->

이 구조가 현재 선택된 이유: JWT/OIDC Resource Server를 모든 서비스에 동시 도입하면 SecurityConfig, 테스트, API 계약이 한꺼번에 변경된다. 현재는 SCG를 신뢰 경계로 두고, 서비스 레벨 인증은 다음 단계로 미뤄진 상태다.

### 5. 결제 보안 통제 (구현됨)

| 통제 | 구현 방식 | 목적 |
|-----|---------|------|
| 금액 위변조 방지 | `/confirm` 시 저장된 amount와 요청 amount 비교 (P003 에러) | 클라이언트가 amount를 수정해서 재전송하는 공격 차단 |
| 이중 결제 방지 (1차) | Redis idempotency: `SETNX payment:idempotency:{key} PROCESSING` | 동일 Idempotency-Key 동시 처리 차단 |
| 이중 결제 방지 (2차) | DB UNIQUE KEY `uk_reservation_id`, `uk_order_id` | Redis 장애 시에도 DB에서 최종 차단 |
| PG secret 관리 | `${TOSS_PAYMENTS_SECRET_KEY}` 환경변수 주입 | 코드 저장소에 secret 미포함 |
| TossPayments 인증 | `Authorization: Basic base64(secretKey + ":")` | TossPayments Basic Auth 규격 준수 |
| orderId 서버 생성 | `RES{reservationId}_{epochMilli}` 형식, 서버에서 생성 | 클라이언트가 orderId를 조작하는 공격 차단 |

### 6. Secret 관리 현황

| Secret | 관리 방식 | 기본값 (주의) |
|--------|---------|------------|
| `TOSS_PAYMENTS_SECRET_KEY` | 환경변수 | `test_sk_placeholder` (테스트용) |
| `SPRING_DATASOURCE_PASSWORD` | 환경변수 | **`1234`** (위험 — 아래 Risks 참조) |
| `REDIS_PASSWORD` | 환경변수 | 기본값 없음 (미설정 시 Redis 접속 불가) |
| GitLab recovery code | Bitwarden | — |
| OTP seed | Bitwarden / Google Authenticator | — |

---

## Risks

### R1: MySQL 기본 비밀번호 (높음)

`application.properties`에 `${SPRING_DATASOURCE_PASSWORD:1234}`가 설정돼 있다. 환경변수가 주입되지 않은 상태로 서비스가 기동되면 `root / 1234`로 MySQL에 접속한다. 스테이징 환경이 내부망에 있으므로 직접 인터넷 노출 위험은 낮지만, VPN 접속만 되면 MySQL에 루트 계정으로 접근 가능한 상태다.

### R2: actuator 무인증 노출 (중간)

`/actuator/prometheus`는 Prometheus 스크레이핑에 필요하다. 그러나 `/actuator/env`, `/actuator/beans` 같은 진단 엔드포인트도 내부망에서 인증 없이 접근 가능하다. 현재 `management.endpoints.web.exposure.include=prometheus,health`로 노출 범위가 제한돼 있어 실질적 위험은 낮다.

### R3: Swagger 무인증 노출 (낮음)

`/swagger-ui/**`가 `permitAll()`이다. VPN 접속자라면 누구나 내부 API 구조를 볼 수 있다. VPN = 신뢰 경계로 수용한 트레이드오프이나, 운영 환경에서는 profile 기반 비활성화 필요.

### R4: 서비스 간 Auth-Passport 신뢰 (낮음, 내부망 한정) <!-- 2026-03-22 ADR-0007 Phase 3 완료 반영 -->

내부망에서 직접 HTTP 요청 시 임의의 `Auth-Passport` 헤더를 넣어 서비스를 호출할 수 있다. `Auth-Passport`는 Base64url(JSON) 인코딩이므로 구조를 알면 위조 가능하다 (HMAC 서명 없음 — ADR-0007에서 내부망 신뢰 경계를 근거로 서명 미적용 결정). 현재 모든 서비스가 `permitAll()`이므로 application 레벨에서 차단되지 않는다. VPN 접속이 전제이므로 외부 공격 위험은 낮지만, 내부 통제가 없는 상태다.

### R5: SCG 레벨 rate limiting 없음 (중간)

`/api/v1/payments/confirm`에 대한 IP/user 기준 rate limiting이 없다. 동일 사용자가 confirm을 반복 호출할 경우 Redis idempotency가 1차 방어하지만, idempotency-key를 매번 바꾸면 무제한 호출이 가능하다. TossPayments orderId UNIQUE 제약이 실질적 2차 방어다.

### R6: user-app 비밀번호 평문 저장 (높음)

`user-app/UserService.java`의 `signUp()` 메서드에서 `User.create(request.email(), request.name(), request.password())`를 호출할 때 비밀번호를 **인코딩 없이 그대로 저장**한다. `UserEntity.password` 컬럼은 `VARCHAR NOT NULL`이며 bcrypt 해시가 아닌 평문 문자열이 DB에 기록된다.

```java
// UserService.java (현재 구현)
User user = User.create(request.email(), request.name(), request.password());
// TODO: BCryptPasswordEncoder 적용 예정 — 코드 주석에 미구현 명시됨
```

**위험**: 스테이징 DB(`ticketing_user.users`)가 노출되면 사용자 비밀번호가 즉시 평문 확인된다. MySQL 기본 비밀번호(R1)와 결합되면 VPN 접속자가 루트 계정으로 전체 비밀번호를 덤프할 수 있다.

**완화 방안 (planned)**: `BCryptPasswordEncoder`로 해싱 후 저장. Spring Security의 `PasswordEncoder` 빈을 주입하면 기존 코드 변경이 최소화된다. 단, 기존 평문 비밀번호와 해시값이 혼재하므로 마이그레이션 전략(전체 재설정 또는 최초 로그인 시 재해싱)이 필요하다.

### R7: user-app JWT 발급 미구현 (중간)

시스템 아키텍처 문서(`architecture/why-msa.md`)에서 user-app이 JWT를 발급하고 SCG의 `JwtAuthenticationFilter`가 이를 검증하는 구조로 설계되어 있다. 그러나 **현재 user-app에는 JWT 발급 로직이 없다.**

현재 상태:
- user-app: signup / getInfo / ai-test 3개 엔드포인트만 있음. `/api/v1/users/login` 없음
- SCG `JwtAuthenticationFilter`: JWT 검증 필터가 코드에 있으나, 실제 토큰을 발급하는 서비스가 없어 인증 흐름이 완성되지 않음
- 결과적으로 `Auth-Passport` 헤더가 SCG에서 신뢰할 수 있는 방식으로 주입되지 않는 상태

**위험**: 현재 booking-app, payment-app에서 `Auth-Passport` 헤더로 사용자를 식별하지만 (ADR-0007 Phase 2 완료), 이 헤더의 출처가 검증된 JWT가 아닌 외부 직접 주입에 취약하다 (R4 참조).

**완화 방안 (planned)**: user-app에 `POST /api/v1/users/login` 엔드포인트 추가 → 이메일/비밀번호 검증 후 JWT(AccessToken + RefreshToken) 발급 → SCG JwtAuthenticationFilter에서 검증 후 userId 추출 → `Auth-Passport` 헤더로 주입.

---

## Controls / Mitigations

### 현재 적용된 통제

| 위협 | 통제 | 구현 위치 |
|-----|-----|---------|
| 외부 직접 접근 | VPN-only ingress (1194/UDP) | 공유기 NAT |
| 운영 도구 무단 접근 | oauth2-proxy + GitLab OIDC | oauth2-proxy |
| GitLab 계정 탈취 | GitLab MFA (2FA 강제) | GitLab 설정 |
| internal API 외부 노출 | InternalPathBlockGlobalFilter | scg-app |
| 결제 금액 위변조 | 서버 측 amount 교차 검증 | PaymentValidator.java |
| 이중 결제 (Redis) | SETNX atomic idempotency | IdempotencyManager.java |
| 이중 결제 (DB) | UNIQUE KEY (reservation_id, order_id) | PaymentEntity.java |
| PG secret 노출 | 환경변수 주입 | application.properties |
| 결제 보상 실패 감지 | CANCEL_FAILED 상태 + CRITICAL 로그 | PaymentManager.java |

### MySQL 기본 비밀번호 완화책

현재 스테이징 환경에서 즉시 적용 가능한 완화:
- 배포 시 `SPRING_DATASOURCE_PASSWORD` 환경변수 반드시 주입 (Jenkins 파이프라인 credential binding)
- MySQL 접속 계정을 root에서 전용 계정으로 변경 (planned)

---

## Planned Improvements

### P1: user-app JWT 발급 구현 + SCG 레벨 JWT 검증 (planned)

현재 user-app에 JWT 발급 기능이 없어 인증 흐름이 완성되지 않은 상태다 (R7 참조). 아래 두 단계가 함께 구현되어야 한다.

**1단계: user-app JWT 발급**
- `POST /api/v1/users/login`: 이메일/비밀번호(BCrypt 검증) → JWT AccessToken + RefreshToken 발급
- 의존: R6(BCrypt 미적용) 선행 해결 필요
- JWT payload: `{ userId, email, roles, iat, exp }`

**2단계: SCG JWT 검증**
- SCG `JwtAuthenticationFilter`에서 `Authorization: Bearer {token}` 헤더 검증
- 검증된 userId를 `Auth-Passport`로 주입 (ADR-0007 Phase 2에서 downstream 소비를 Auth-Passport로 일원화 완료), 외부 유입 헤더는 strip (현재 `RequestSanitizeFilter`에서 처리 중)
- `/internal/**`는 서비스 간 호출이므로 JWT 검증 대상에서 제외
- Spring Cloud Gateway `TokenRelayGatewayFilterFactory` 또는 custom filter 활용

### P2: SCG 레벨 rate limiting (planned)

Spring Cloud Gateway `RequestRateLimiterGatewayFilterFactory` (Redis token bucket):
- IP 기준: 전체 API 분당 60 req
- userId 기준: `/api/v1/payments/confirm` 분당 5 req

### P3: MySQL 전용 계정 + 최소 권한 (planned)

```sql
CREATE USER 'payment_app'@'%' IDENTIFIED BY '...';
GRANT SELECT, INSERT, UPDATE ON ticketing_payment.* TO 'payment_app'@'%';
-- 스키마별 전용 계정으로 격리
```

현재 root 계정 공유가 서비스 간 격리 원칙에 어긋난다.

### P4: actuator 엔드포인트 IP 제한 (planned)

```yaml
management.endpoints.web.exposure.include=prometheus,health
# 추가: actuator path를 /internal/actuator로 이동하고 SCG에서 차단
```

또는 Prometheus scraping을 내부 전용 포트로 분리.

### P5: Swagger 프로파일 기반 비활성화 (planned)

`spring.profiles.active=prod`일 때 `springdoc.swagger-ui.enabled=false` 설정.

### P6: Jenkins credential binding 표준화 (planned)

현재 일부 환경변수가 Jenkins 파이프라인에 직접 기입된 상태일 수 있다. `withCredentials` 블록으로 표준화하고, AWS SSM Parameter Store 또는 Secrets Manager 연동 검토.

---

## Trade-offs

| 결정 | 얻은 것 | 잃은 것 |
|------|---------|---------|
| VPN-only ingress | 외부 노출면 단일화, 운영 도구 직접 노출 없음 | VPN 장애 시 운영 접근 불가 |
| GitLab identity 중앙 통제 | 서비스별 계정 분산 없음, MFA 단일 적용 | GitLab 장애 시 oauth2-proxy 인증 불가 |
| SCG 신뢰 경계 (현재) | 서비스 레벨 인증 구현 없이 빠른 개발 | 내부망에서 header spoofing 가능 |
| /internal 404 반환 | 내부 API 존재 자체를 숨김 | 디버깅 시 오해 가능성 (404 vs 403 구분 불가) |
| permitAll() 전체 (현재) | 개발/테스트 마찰 없음 | 서비스 레벨 인증 없음 — JWT 전환까지 한시적 상태 |
| actuator 노출 범위 제한 (prometheus,health만) | 진단 정보 최소 노출 | Prometheus 스크레이핑이 인증 없이 가능 |

---

## Failure Scenarios

### VPN 장애 시
- 외부에서 운영 도구 접근 불가
- 서비스 자체는 내부망에서 계속 동작 (scg-app은 내부 요청 처리 가능)
- 대응: 물리적으로 내부망에 접속하거나 VPN 서버(192.168.124.100) 직접 접근

### GitLab 장애 시
- oauth2-proxy 인증 불가 → Jenkins, Grafana 로그인 불가
- 서비스 자체는 영향 없음
- 대응: oauth2-proxy bypass 설정 또는 Grafana 로컬 admin 계정 (planned: 비상 계정 문서화)

### MySQL root 비밀번호 노출 시
- 환경변수 미설정 상태에서 `root / 1234` 접속 가능
- 즉시 조치: MySQL root 비밀번호 변경, 서비스 재배포로 새 자격증명 주입

---

## Observability

현재 보안 이벤트 로그:
- P006 (Idempotency conflict): `[WARN]` 로그 — 동일 요청 동시 처리 감지
- P002 (이중 결제): `DataIntegrityViolationException` → GlobalExceptionHandler P002 변환
- `[CRITICAL]` 로그: CANCEL_FAILED 상태 전이 시 (PaymentManager)

**현재 없는 것 (planned)**:
- 인증 실패 이벤트 중앙 수집 (SIEM 연동)
- oauth2-proxy 로그인 실패 횟수 Prometheus 추적
- 비정상 Auth-Passport 헤더 패턴 감지 (위조된 Base64 payload 등) <!-- 2026-03-22 ADR-0007 Phase 3 완료 반영 -->

---
