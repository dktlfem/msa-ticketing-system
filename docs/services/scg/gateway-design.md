# Gateway 설계: scg-app

> **관련 문서**:
> - 시스템 전체 서비스 경계: [`docs/architecture/overview.md`](../../architecture/overview.md)
> - 헤더 설계 결정: [`docs/architecture/adr/0002-internal-header-design.md`](../../architecture/adr/0002-internal-header-design.md)
> - API 라우팅 규칙: [`docs/api/api-spec.md`](../../api/api-spec.md)
> - 보안 통제: [`docs/security/security-design.md`](../../security/security-design.md)
>
> **코드 위치**: `scg-app/src/main/java/com/koesc/ci_cd_test_app/`

---

## 목차

- [Background](#background)
- [Problem](#problem)
- [Filter 실행 순서](#filter-실행-순서)
- [라우팅 설계](#라우팅-설계)
- [인증 필터 설계 — JwtAuthenticationFilter](#인증-필터-설계--jwtauthenticationfilter)
- [보안 필터 설계 — RequestSanitizeFilter](#보안-필터-설계--requestsanitizefilter)
- [내부 경로 차단 — InternalPathBlockFilter](#내부-경로-차단--internalpathblockfilter)
- [에러 처리 — GlobalErrorHandler](#에러-처리--globalerrorhandler)
- [요청 상관관계 — RequestCorrelationFilter](#요청-상관관계--requestcorrelationfilter)
- [액세스 로그 — AccessLogFilter](#액세스-로그--accesslogfilter)
- [Rate Limiting & Bulkhead](#rate-limiting--bulkhead)
- [Circuit Breaker & Retry](#circuit-breaker--retry)
- [ADR 0002와의 관계](#adr-0002와의-관계)
- [Trade-offs](#trade-offs)
- [Failure Scenarios](#failure-scenarios)

---

## Background

scg-app은 Spring Cloud Gateway 기반의 API 게이트웨이다. 시스템의 단일 외부 진입점으로, 다음 역할을 담당한다.

- 모든 외부 요청 라우팅 (`/api/v1/**`)
- JWT 서명 검증 및 인증 컨텍스트 전파 (`Auth-User-Id`, `Auth-Passport` 헤더 주입)
- 위조 헤더 제거 (`RequestSanitizeFilter`)
- `/internal/**` 경로 외부 차단
- Rate Limiting 및 Bulkhead (서비스별 격리)
- Circuit Breaker + Retry (downstream 장애 격리)
- Access Log + Request Correlation ID

**upstream**: admin-gateway (Nginx, 8080) → scg-app (8080 내부)
**downstream**: user-app, concert-app, waitingroom-app, payment-app, booking-app

---

## Problem

API 게이트웨이 없이 클라이언트가 각 서비스에 직접 접근하면:

1. **헤더 위조**: 클라이언트가 `Auth-User-Id: 1`을 직접 주입해 다른 사용자로 위장 가능
2. **내부 API 노출**: `/internal/**` 경로가 외부에 노출되어 예약 확정·좌석 상태 변경을 직접 호출 가능
3. **인증 분산**: 각 서비스가 JWT 검증 로직을 각자 구현해야 함 → 코드 중복, 불일치 위험
4. **Rate Limit 분산**: DDoS 대응이나 과부하 보호를 서비스마다 따로 구현해야 함

---

## Filter 실행 순서

GlobalFilter는 `getOrder()` 반환값 기준 오름차순 실행 (낮은 값이 먼저).

```
요청 진입
  │
  ├─ [HIGHEST_PRECEDENCE + 0] RequestCorrelationFilter
  │    X-Request-Id 생성/전파, MDC 등록
  │
  ├─ [HIGHEST_PRECEDENCE + 1] AccessLogFilter
  │    method, path, statusCode, durationMs, clientIp 기록
  │
  ├─ [HIGHEST_PRECEDENCE + 2] InternalPathBlockFilter
  │    /internal/** 요청 → 403 즉시 반환
  │
  ├─ [HIGHEST_PRECEDENCE + 3] RequestSanitizeFilter
  │    Auth-User-Id, Auth-Passport, Auth-Roles, Internal-Token 헤더 제거 (외부 유입 위조 방지)
  │
  ├─ [HIGHEST_PRECEDENCE + 4] JwtAuthenticationFilter
  │    JWT 서명 검증 → Auth-User-Id, Auth-Passport 헤더 추가
  │
  ├─ [route 필터] RateLimiter → CircuitBreaker → Retry
  │
  └─ downstream 서비스
```

**중요**: Sanitize(+3)가 JWT(+4)보다 반드시 먼저 실행되어야 한다. 순서가 바뀌면 JWT 필터가 추가한 `Auth-User-Id`/`Auth-Passport`를 Sanitize가 지워버린다.

---

## 라우팅 설계

`scg-app/src/main/resources/application.yml` 기준 (4개 route).

| route id | predicate | upstream URI | 비고 |
|----------|-----------|--------------|------|
| `user-service` | `Path=/api/v1/users/**` | `http://user-app:8080` | CB + Retry(GET/HEAD) |
| `concert-service` | `Path=/api/v1/events/**,/api/v1/schedules/**,/api/v1/seats/**` | `http://concert-app:8080` | CB + Retry(GET/HEAD) |
| `waitingroom-service` | `Path=/api/v1/waiting-room/**` | `http://waitingroom-app:8080` | Rate Limit: 100/200 |
| `payment-service` | `Path=/api/v1/payments/**` | `http://payment-app:8080` | Rate Limit: 5/10, Bulkhead: 10 |

**booking-app route 부재 (의도적)**

`/api/v1/reservations/**` 라우팅이 현재 SCG 설정에 없다. 예약 진입은 반드시 대기열 토큰(`Queue-Token`) 검증을 거쳐야 하는데, 단순 Path predicate로는 토큰 검증 없이 직접 예약이 가능해진다. 별도의 토큰 검증 게이트웨이 필터를 포함한 라우팅이 필요하다 **(planned)**.

**전역 default-filter**: 모든 route에 `RequestSize` 제한 5MB 적용.

---

## 인증 필터 설계 — JwtAuthenticationFilter

**위치**: `filter/JwtAuthenticationFilter.java` | order: `HIGHEST_PRECEDENCE + 4`

### 처리 흐름

```
요청 수신
  │
  ├─ isExcluded(path)?
  │    YES → chain.filter 통과 (/actuator/**, /fallback/**)
  │    NO  ↓
  │
  ├─ Authorization 헤더 확인
  │    없음 or Bearer 미시작 → 401 ProblemDetail
  │    있음 ↓
  │
  ├─ parseToken(token) — HMAC-SHA256 서명 검증
  │    ExpiredJwtException → 401 "JWT token has expired"
  │    JwtException       → 401 "JWT token is invalid"
  │    성공 ↓
  │
  ├─ Claims.getSubject() → userId
  │    blank → 401 "JWT subject (userId) is missing"
  │    있음 ↓
  │
  └─ request.mutate()
       .header("Auth-User-Id", userId)
       .header("Auth-Passport", base64url(json))   ← userId, roles, jti, issuedAt, clientIp 포함
       → chain.filter
```

### 설정 (application.yml)

```yaml
gateway:
  security:
    jwt-secret: "change-me-in-production-must-be-at-least-32-bytes!!"
    excluded-paths:
      - /actuator/**
      - /fallback/**
```

JWT Claims 규약:
- `sub` (subject): `userId`
- `roles` (custom): `List<String>` 또는 쉼표 구분 String

---

## 보안 필터 설계 — RequestSanitizeFilter

**위치**: `filter/RequestSanitizeFilter.java` | order: `HIGHEST_PRECEDENCE + 3`

**목적**: 클라이언트가 위조한 내부 인증 헤더를 JWT 필터가 실행되기 전에 제거한다.

### 제거 대상 헤더

| 헤더 | 제거 이유 |
|------|---------|
| `Auth-User-Id` | JWT 필터가 검증 후 채워줌. 클라이언트 직접 주입 금지 |
| `Auth-Passport` | JWT 필터가 검증 후 채워줌. 클라이언트 직접 주입 금지 |
| `Auth-Roles` | JWT 필터가 검증 후 채워줌. 권한 위조 방지 |
| `Internal-Token` | 서비스 간 내부 통신 전용. 외부 클라이언트 접근 금지 |

위조 헤더 감지 시 WARN 로그를 남긴다:
```
[SANITIZE] Stripped forged header=Auth-User-Id clientIp=1.2.3.4 path=/api/v1/reservations
```

설정에서 `gateway.security.sanitize-headers`로 제거 대상 추가/수정 가능.

---

## 내부 경로 차단 — InternalPathBlockFilter

**위치**: `filter/InternalPathBlockFilter.java` | order: `HIGHEST_PRECEDENCE + 2`

`/internal/**` 경로에 대한 외부 요청을 403으로 즉시 차단한다. Sanitize 및 JWT 필터보다 먼저 실행되어, 내부 API 경로에 인증 로직조차 실행되지 않는다.

```yaml
gateway:
  security:
    internal-block-patterns: "/internal/**"
```

차단 시 WARN 로그: `[BLOCKED] requestId=... clientIp=... path=/internal/v1/seats/1/hold`

---

## 에러 처리 — GlobalErrorHandler

**위치**: `error/GlobalErrorHandler.java` | `@Order(-2)`

Spring Boot 기본 `DefaultErrorWebExceptionHandler`(order=-1)를 order=-2로 덮어쓴다.

**반환 형식**: RFC 7807 `application/problem+json`

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "No route found for request path: /api/v1/unknown",
  "instance": "/api/v1/unknown",
  "requestId": "abc123"
}
```

**에러 종류별 status 결정**:

| 예외 타입 | HTTP Status |
|-----------|------------|
| `ResponseStatusException` | 예외에 지정된 status |
| `ConnectException`, `UnknownHostException` | 502 Bad Gateway |
| 그 외 | 500 Internal Server Error |

**커버 범위**: 필터 체인 외부에서 발생하는 예외 (라우팅 실패, 연결 거부 등). 필터 체인 내부 예외(JwtAuthenticationFilter의 401 등)는 각 필터에서 직접 처리.

마이크로서비스의 비즈니스 에러 (4xx `ErrorResponse`)는 SCG를 그대로 통과해 클라이언트에 전달된다.

**클라이언트 분기 기준**: 응답에 `code` 필드 있음 → 마이크로서비스 `ErrorResponse`, `code` 없음 → SCG `ProblemDetail`.

---

## 요청 상관관계 — RequestCorrelationFilter

**위치**: `filter/RequestCorrelationFilter.java` | order: `HIGHEST_PRECEDENCE + 0`

- 인바운드 `X-Request-Id` 헤더가 있으면 그대로 사용, 없으면 UUID를 생성
- 생성된 `requestId`를 `X-Request-Id` 헤더와 응답 헤더로 설정
- `MDC.put("requestId", requestId)` — 로그에 `requestId` 필드 자동 포함
- Reactor Context에도 저장 (`Context.of(MDC_KEY, requestId)`) — Virtual Thread / CircuitBreaker fallback 등 스레드 전환 시 `Slf4jMdcThreadLocalAccessor`가 MDC 복원

---

## 액세스 로그 — AccessLogFilter

**위치**: `filter/AccessLogFilter.java` | order: `HIGHEST_PRECEDENCE + 1`

모든 요청(인증 실패 포함)의 처리 결과를 JSON 구조로 기록한다.

```json
{
  "requestId": "abc123",
  "method": "POST",
  "path": "/api/v1/payments/confirm",
  "statusCode": 200,
  "durationMs": 342,
  "clientIp": "1.2.3.4"
}
```

`doFinally()`로 응답 완료 후 기록하므로 실제 처리 시간(`durationMs`)이 정확하다.

---

## Rate Limiting & Bulkhead

**Rate Limiter**: `LocalRateLimiter.java` — Token Bucket 알고리즘 (Redis 없이 로컬 메모리)

| route | replenishRate | burstCapacity |
|-------|--------------|---------------|
| waitingroom-service | 100/s | 200 |
| payment-service | 5/s | 10 |
| 나머지 | 30/s (기본값) | 50 |

key-resolver: 클라이언트 IP 기반 (`remoteAddrKeyResolver`)

**Bulkhead**: 동시 요청 수 제한 (`BulkheadFilter.java`)

| route | maxConcurrentCalls |
|-------|-------------------|
| payment-service | 10 |
| 나머지 | 20 (기본값) |

payment-service는 TossPayments 호출(최대 10초)이 있어 동시 처리 수를 낮게 제한한다. 초과 요청은 503 반환.

---

## Circuit Breaker & Retry

모든 route에 CircuitBreaker + Retry가 적용된다.

**CircuitBreaker**: `Resilience4jConfig.java`에서 서비스별 override 가능.
- payment-service-cb: `waitDurationInOpenState=30s` (별도 설정)
- fallback URI: `forward:/fallback/service-unavailable`
- 트리거 statusCodes: 500, 502, 503, 504

**Retry 규칙** (공통):
- `methods: GET,HEAD` — POST 제외 (비멱등 요청 재시도 금지)
- `retries: 3`
- `exceptions: ConnectException, TimeoutException`
- backoff: firstBackoff 50ms, maxBackoff 500ms, factor 2

payment-service의 POST `/confirm`, `/request`, `/cancel`은 methods 제한으로 재시도 없음.

---

## ADR 이력과의 관계

[ADR-0002](../../architecture/adr/0002-internal-header-design.md)는 `X-Auth-User-Id` vs `X-User-Id` 헤더 일원화 결정을 다뤘다 (Superseded by ADR-0007).

[ADR-0007](../../architecture/adr/0007-header-naming-and-auth-passport.md) (Completed, 2026-03-22)이 최종 상태다.

- `X-User-Id`, `X-Auth-User-Id`는 모두 downstream 코드에서 제거됨 (Phase 3 완료)
- SCG는 `Auth-User-Id`와 `Auth-Passport`를 모두 주입하지만, **downstream 서비스(booking-app, payment-app)는 `Auth-Passport`만 소비** (Phase 2에서 전환 완료)
- SCG가 외부 유입 `Auth-User-Id`/`Auth-Passport`를 strip(Sanitize)한 후 검증된 값을 재발급(JWT 필터)하므로, downstream은 이 헤더를 신뢰할 수 있다

---

## Trade-offs

| 결정 | 이유 | 트레이드오프 |
|------|------|------------|
| JWT 검증을 SCG에서 일원화 | 각 서비스의 JWT 코드 제거, 헤더 위조 방지 집중 | SCG 장애 시 전체 인증 불가 |
| booking-app route 제외 | 토큰 검증 없는 직접 예약 방지 | booking-app이 외부에서 SCG를 통해 라우팅되지 않음 (planned 해결) |
| LocalRateLimiter (메모리 기반) | Redis 없이 동작 가능, 단순 | 다중 SCG 인스턴스 시 limit이 인스턴스별로 적용됨 (분산 Rate Limit 미지원) |
| InternalPathBlockFilter를 JWT보다 먼저 실행 | 내부 경로에 인증 로직 불필요 | /internal 경로는 어떤 JWT도 통과 불가 |

---

## Failure Scenarios

### SCG 장애 시
- 외부에서 모든 서비스 접근 불가 (마이크로서비스 간 internal API는 직접 호출이므로 영향 없음)
- admin-gateway(Nginx)에서 health check 실패 감지 가능

### JWT Secret 변경 시
- 기존 발급된 모든 토큰이 즉시 무효화됨
- `gateway.security.jwt-secret` 변경 후 scg-app 재시작 필요
- 재시작 시 graceful shutdown 30초 대기 (`server.shutdown: graceful`)

### Rate Limit 초과 시
- 429 Too Many Requests 반환 (Spring Cloud Gateway 기본)
- SCG가 로컬 메모리 기반이므로 재시작 시 counter 초기화됨

---

*최종 업데이트: 2026-03-19 | scg-app 소스 코드 기반 작성*
