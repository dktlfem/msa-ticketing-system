# Security Design: MSA 티켓팅 플랫폼 보안 설계

> **범위**: 이 문서는 현재 구현된 보안 통제 항목과 그 근거, 알려진 한계, 개선 계획을 다룬다.
> 보안 개요(VPN, oauth2-proxy, /internal 차단, 결제 멱등성, Rate Limiting 기본 개념)는 [`docs/04-security-auth-rate-limiting.md`](../04-security-auth-rate-limiting.md)를 참조한다.
> 이 문서는 그 내용을 반복하지 않고, 서비스 경계별 통제 현황과 리스크 분석에 집중한다.

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

1. **서비스 레벨 인증 없음**: SCG가 `X-User-Id` 헤더를 downstream에 전달하지만, 각 서비스는 이 헤더가 SCG에서 온 것인지 검증하지 않는다. 내부망에서 직접 HTTP 호출 시 임의의 `X-User-Id` 값을 주입할 수 있다.
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
                    │     ├── scg-app:8080 (서비스 진입점)
                    │     ├── Jenkins, Grafana, Prometheus, Kibana
                    │     └── MySQL:33066
                    └── 192.168.124.101 (Redis:6379)
```

**외부에 직접 노출된 포트**: OpenVPN 1194/UDP 단 하나.

운영 도구(Jenkins, Grafana, Prometheus)는 인터넷 직접 노출이 없다. VPN 없이 접근하려면 공유기 NAT를 통과해야 하므로 IP 레벨에서 원천 차단된다.

### 2. 운영 도구 인증 (구현됨)

```
VPN 접속
  └── oauth2-proxy (GitLab OIDC)
        ├── Jenkins
        ├── Grafana
        └── Prometheus
```

- **GitLab MFA**: GitLab 계정에 2FA 강제 적용
- **oauth2-proxy**: GitLab OAuth2/OIDC 플로우로 Jenkins/Grafana/Prometheus 접근 통제
- **Bitwarden**: GitLab recovery code 저장
- **OTP**: Google Authenticator / Bitwarden OTP로 관리

GitLab identity가 운영 도구 접근의 단일 진입점이다. 서비스별 로컬 계정이 없다.

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

**모든 엔드포인트가 `permitAll()`이다.** Spring Security는 설치됐지만 인증 강제가 없다. 사용자 식별은 SCG가 전달하는 `X-User-Id` 헤더에만 의존한다.

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

### R4: 서비스 간 X-User-Id 신뢰 (낮음, 내부망 한정)

내부망에서 직접 HTTP 요청 시 임의의 `X-User-Id` 헤더를 넣어 서비스를 호출할 수 있다. 현재 모든 서비스가 `permitAll()`이므로 application 레벨에서 차단되지 않는다. VPN 접속이 전제이므로 외부 공격 위험은 낮지만, 내부 통제가 없는 상태다.

### R5: SCG 레벨 rate limiting 없음 (중간)

`/api/v1/payments/confirm`에 대한 IP/user 기준 rate limiting이 없다. 동일 사용자가 confirm을 반복 호출할 경우 Redis idempotency가 1차 방어하지만, idempotency-key를 매번 바꾸면 무제한 호출이 가능하다. TossPayments orderId UNIQUE 제약이 실질적 2차 방어다.

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

### P1: SCG 레벨 JWT 검증 (planned)

SCG에서 JWT를 검증하고, 검증된 userId만 `X-Auth-User-Id`로 downstream에 전달.
- Spring Cloud Gateway `TokenRelayGatewayFilterFactory` 또는 custom filter
- 각 서비스의 `SecurityConfig`에서 `X-Auth-User-Id`를 신뢰 헤더로 처리
- `/internal/**`는 서비스 간 호출이므로 JWT 검증 대상에서 제외

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
- 비정상 X-User-Id 헤더 패턴 감지

---

## Interview Explanation (90s version)

> 이 시스템의 보안 설계 핵심은 공격면 최소화입니다. 인터넷에 노출된 포트는 OpenVPN 1194/UDP 하나이고, 운영 도구인 Jenkins, Grafana, Prometheus는 VPN 내부에서 GitLab OAuth2/OIDC + MFA로만 접근할 수 있습니다. SCG에서 `/internal/**` 경로를 404로 차단해 서비스 간 내부 API가 외부에 노출되지 않습니다. 결제 보안은 서버 측 amount 교차 검증, Redis SETNX 기반 멱등성, DB UNIQUE KEY 이중 방어로 구현됩니다.
>
> 현재 한계도 명확합니다. 서비스 레벨에서 JWT 검증이 없고, SecurityConfig가 전체 `permitAll()` 상태입니다. SCG가 신뢰 경계이나 SCG 자체도 JWT를 검증하지 않아, VPN 접속 후 X-User-Id 헤더를 조작할 수 있습니다. MySQL 기본 비밀번호가 `1234`인 것도 환경변수 미주입 시 위험합니다. 다음 단계는 SCG 레벨 JWT 검증과 MySQL 전용 최소 권한 계정 분리입니다.

---

*최종 업데이트: 2026-03-16 | SecurityConfig.java, application.properties 기준*
