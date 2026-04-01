---
title: "Frontend Integration Guide"
last_updated: 2026-04-01
status: reviewed-draft
verified_against_commit: HEAD
---

# Frontend Integration Guide

SCG(scg-app)를 통해 시스템에 통합하는 프론트엔드 개발자를 위한 가이드입니다.

---

## 1. 진입점

모든 외부 요청은 scg-app(Spring Cloud Gateway)을 단일 진입점으로 사용합니다.
`/internal/**` 경로는 `InternalPathBlockFilter`에 의해 외부에서 완전히 차단됩니다.

### 서비스별 라우팅 경로

| 경로 패턴 | 라우팅 대상 | Rate Limit (req/s) | Burst | 타임아웃 |
|---|---|---|---|---|
| `/api/v1/users/**` | user-app | 30 | 50 | 10s (글로벌) |
| `/api/v1/events/**`, `/api/v1/schedules/**`, `/api/v1/seats/**` | concert-app | 30 | 50 | 10s (글로벌) |
| `/api/v1/reservations/**`, `/api/v1/users/me/reservations` | booking-app | 20 | 40 | **15s** |
| `/api/v1/waiting-room/**` | waitingroom-app | 100 | 200 | 10s (글로벌) |
| `/api/v1/payments/**` | payment-app | 5 | 10 | 10s |

booking-app에 15s 타임아웃이 적용되는 이유:
예약 생성 시 Redisson 분산락 대기(최대 5s) + waitingroom-app 검증 + concert-app HOLD 호출이
직렬로 수행되기 때문입니다. (`application.yml` `metadata.response-timeout: 15000`)

---

## 2. 헤더 규약

### ✅ 클라이언트가 직접 보내는 헤더

| 헤더 | 필수 여부 | 설명 | 예시 |
|---|---|---|---|
| `Authorization` | 인증 필요 API 전체 | `Bearer {JWT 액세스 토큰}` | `Bearer eyJhbGci...` |
| `Queue-Token` | 아래 "Queue-Token 적용 범위" 참고 | waitingroom-app이 발급한 대기열 통과 토큰 (UUID v4) | `550e8400-e29b-41d4-a716-446655440000` |
| `X-Request-Id` | 선택 | 요청 추적 ID. 없으면 SCG가 UUID를 자동 생성 | `my-trace-id-001` |
| `Idempotency-Key` | 결제 API (prepare/confirm/cancel) | 멱등 처리 키. 동일 키 재요청 시 캐시된 응답 반환 | `idem-key-20260401-001` |

#### Queue-Token 적용 범위

`QueueTokenValidationFilter`는 아래 **두 조건을 모두** 만족하는 요청에 Queue-Token을 요구합니다.

- 경로: `/api/v1/reservations/**` (`protectedPaths`)
- 메서드: `POST`, `PUT`, `PATCH`, `DELETE` (`PROTECTED_METHODS`)

따라서 현재 코드 기준으로 아래 요청들이 Queue-Token을 요구합니다.

| 요청 | Queue-Token 필요 |
|---|---|
| `POST /api/v1/reservations` (예약 생성) | **필요** — 의도된 동작 |
| `DELETE /api/v1/reservations/{id}` (예약 취소) | **필요** — 아래 Assumption 참고 |
| `GET /api/v1/reservations/{id}` (조회) | 불필요 |
| `GET /api/v1/users/me/reservations` (목록) | 불필요 |

> **Assumption — DELETE에 대한 Queue-Token 요구**
>
> 예약 취소(`DELETE`)에 Queue-Token이 필요한 것은 의도된 정책인지 확인이 필요합니다.
> 사용자가 대기열을 다시 통과하지 않고도 자신의 예약을 취소할 수 있어야 하는 것이 자연스럽습니다.
> 현재 `QueueTokenValidationFilter`는 메서드 단위로 일괄 보호하므로, DELETE 전용 제외 로직이 없습니다.
>
> 개선 방향: `protectedPaths`를 메서드별로 분리하거나,
> DELETE를 `PROTECTED_METHODS`에서 제거하는 검토가 필요합니다.

---

### 🚫 SCG가 내부적으로 주입하는 헤더 — 클라이언트 전송 불가

아래 헤더들은 `RequestSanitizeFilter`(order: HIGHEST_PRECEDENCE+3)가 클라이언트 요청에서
**무조건 제거**합니다. 클라이언트가 이 헤더를 직접 전송하면 `WARN` 로그가 남고 값은 폐기됩니다.

| 헤더 | 주입 주체 | 필터 Order | 내용 |
|---|---|---|---|
| `Auth-Passport` | `JwtAuthenticationFilter` | +4 | JWT 검증 후 생성. Base64url(JSON). downstream 서비스가 userId/roles 추출에 사용 |
| `Auth-User-Id` | `JwtAuthenticationFilter` | +4 | JWT `sub` 클레임 값 (userId 문자열) |
| `Auth-Queue-Token` | `QueueTokenValidationFilter` | +5 | Queue-Token UUID 형식 검증 통과 후 downstream 전파용 |
| `Internal-Token` | 서비스 간 내부 호출 전용 | — | 외부에서 사용 불가 |

프론트엔드는 이 헤더들을 파싱하거나 전송할 필요가 없습니다.
downstream 서비스 내부에서만 소비됩니다.

---

## 3. 인증 흐름

이 가이드에서 다루는 "SCG를 통한 정상 통합 범위"에서 프론트엔드의 인증 흐름은 다음과 같습니다.

```
모든 인증 필요 API 호출 시:
  Authorization: Bearer {accessToken}
```

프론트엔드가 보내는 인증 관련 헤더는 `Authorization` 하나뿐입니다.
SCG가 JWT를 검증한 뒤 `Auth-Passport`, `Auth-User-Id`를 downstream에 주입하므로,
프론트엔드는 이 헤더들을 직접 다룰 필요가 없습니다.

> **Known Issue — 인증 API(`/api/v1/auth/**`) 라우팅 누락**
>
> user-app의 `AuthController`는 아래 엔드포인트를 구현하고 있습니다.
>
> | 엔드포인트 | 용도 |
> |---|---|
> | `POST /api/v1/auth/login` | JWT 토큰 쌍 발급 (이메일 + 비밀번호) |
> | `POST /api/v1/auth/refresh` | Refresh Token으로 토큰 갱신 |
> | `POST /api/v1/auth/logout` | Refresh Token 무효화 |
>
> 그러나 SCG route 설정은 `/api/v1/users/**`만 매칭하고 있어
> **`/api/v1/auth/**` 경로가 SCG를 통해 라우팅되지 않습니다.**
>
> 이 문제가 해결되기 전까지 인증 API는 이 가이드의 정상 통합 범위 밖에 있습니다.
> user-app에 직접 요청하는 것은 임시 우회 경로이며,
> SCG의 Rate Limiting, 헤더 Sanitize, 로깅 등 보호가 적용되지 않으므로 권장 통합 방식이 아닙니다.
>
> TODO: `application.yml`에 `/api/v1/auth/**` → user-app route 추가 필요.
> 추가 시 `JwtAuthenticationFilter.excludedPaths`에 `/api/v1/auth/login`, `/api/v1/auth/refresh`도
> 등록해야 합니다 (인증 전 단계이므로 JWT 검증 제외 필요).

**인증 제외 경로** (`JwtAuthenticationFilter.excludedPaths`):
`/actuator/**`, `/fallback/**`

---

## 4. 에러 응답 포맷

에러 응답 포맷은 **에러 발생 위치에 따라 다릅니다.**

### 4-1. SCG 레벨 에러

Content-Type: `application/problem+json` (RFC 7807)

발생 위치: JWT 인증 실패, Queue-Token 검증 실패, Circuit Breaker, 라우팅 오류 등

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "Missing or invalid Authorization header",
  "instance": "/api/v1/reservations",
  "requestId": "550e8400-e29b-41d4-a716-446655440000"
}
```

| 상황 | status | title | 발생 필터/핸들러 |
|---|---|---|---|
| JWT 누락/만료 | 401 | Unauthorized | `JwtAuthenticationFilter` |
| Queue-Token 누락 | 403 | Forbidden | `QueueTokenValidationFilter` |
| Queue-Token UUID 형식 오류 | 403 | Forbidden | `QueueTokenValidationFilter` |
| CB open / 서비스 다운 | 503 | Service Unavailable | `FallbackController` |
| 라우팅 실패 / 연결 거부 | 502 | Bad Gateway | `GlobalErrorHandler` |
| Rate Limit 초과 | 429 | — | `RedisRateLimiter` |

### 4-2. 서비스 레벨 에러

Content-Type: `application/json`

발생 위치: booking-app, payment-app 등 downstream 서비스의 `GlobalExceptionHandler`

```json
{
  "code": "R003",
  "message": "현재 해당 좌석에 대한 예약 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.",
  "status": 429
}
```

> 에러 코드 전체 목록은 `ErrorCode.java` 참고.
> ErrorCode enum 구조 재설계 완료 후 별도 `error-code.md`로 분리 예정.

---

## 5. Circuit Breaker / Retry / Fallback

### Retry 동작

- 대상 메서드: **GET, HEAD만** (POST 등 비멱등 요청은 재시도하지 않음)
- 재시도 횟수: 3회
- 백오프: 첫 50ms → 최대 500ms (factor 2, 비누적)
- 트리거 조건: `ConnectException`, `TimeoutException`

### Circuit Breaker 트리거 상태코드

| 서비스 | CB 트리거 status | 비고 |
|---|---|---|
| user, concert, waitingroom, payment | 500, 502, 503, 504 | — |
| **booking** | **502, 503, 504만** | 500 제외 이유 아래 참고 |

booking-app CB에서 500을 제외한 이유:
대기열 토큰 만료/무효(waitingroom 4xx)가 booking-app 내부에서 `IllegalStateException` → 500으로 변환됩니다.
이것은 인프라 장애가 아닌 비즈니스 오류인데, 현재 코드 구조상 500으로 전파됩니다.
CB가 이를 장애로 카운트하면 정상적인 비즈니스 거절이 CB를 열어버리므로 제외했습니다.
(`application.yml` 주석: `TODO: booking-app에서 4xx를 직접 비즈니스 예외로 처리하도록 개선 후 500 복원 검토`)

### Fallback 응답

CB open 시 `FallbackController`가 처리합니다:
```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "The upstream service is temporarily unavailable. Please try again later.",
  "instance": "/api/v1/reservations",
  "requestId": "..."
}
```

---

## 6. Rate Limiting

- 전략: Redis 기반 Token Bucket (`RedisRateLimiter`)
- 키 결정 (`RateLimiterConfig.principalOrIpKeyResolver`):
  1순위 — `Auth-User-Id` 헤더 존재 시: `user:{userId}`
  2순위 — 미인증: 클라이언트 IP
- 초과 시: HTTP **429** 반환
- 모든 요청의 최대 바디 크기: **5MB** (`RequestSize` 필터)

---

## 7. CORS

현재 설정 (`gateway.security.cors.allowed-origin-patterns`):

```
http://localhost:3000
http://localhost:8080
```

> **Assumption**: 이 값은 로컬 개발 기준입니다.
> 스테이징/운영 origin은 Config Server 또는 환경변수로 주입될 것으로 예상됩니다.

---

## 8. SCG 필터 실행 순서

```
클라이언트 요청 도착
│
│  [+0] RequestCorrelationFilter   X-Request-Id 확보/생성, MDC 등록
│  [+1] AccessLogFilter            요청/응답 로깅
│  [+2] InternalPathBlockFilter    /internal/** 외부 차단
│  [+3] RequestSanitizeFilter      Auth-Passport, Auth-User-Id,
│                                  Auth-Queue-Token, Internal-Token 위조 제거
│  [+4] JwtAuthenticationFilter    JWT 검증 → Auth-Passport, Auth-User-Id 주입
│  [+5] QueueTokenValidationFilter Queue-Token UUID 형식 검증 → Auth-Queue-Token 주입
│
│  RedisRateLimiter                요청 속도 제한  ← route filter
│  CircuitBreaker + Retry          장애 격리       ← route filter
│
└─→ downstream (booking-app, payment-app, ...)
```

---

## 검증에 사용한 소스 파일

- `scg-app/src/main/resources/application.yml`
- `scg-app/.../filter/RequestSanitizeFilter.java`
- `scg-app/.../filter/JwtAuthenticationFilter.java`
- `scg-app/.../filter/QueueTokenValidationFilter.java`
- `scg-app/.../filter/RequestCorrelationFilter.java`
- `scg-app/.../config/RateLimiterConfig.java`
- `scg-app/.../controller/FallbackController.java`
- `scg-app/.../error/GlobalErrorHandler.java`
- `user-app/.../api/controller/AuthController.java`
- `common-module/.../gateway/UserPassport.java`
- `common-module/.../gateway/GatewayHeaders.java`
- `common-module/.../error/GlobalExceptionHandler.java`
