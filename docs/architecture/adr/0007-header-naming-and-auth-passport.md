---
title: "ADR 0007 — X- 접두어 제거(RFC 6648)와 Auth-Passport 단일 컨텍스트 헤더 도입"
last_updated: "2026-03-22"
author: "민석"
reviewer: ""
---

## 목차

- [상태](#상태)
- [컨텍스트](#컨텍스트)
- [문제](#문제)
- [결정](#결정)
- [Auth-Passport 필드 설계](#auth-passport-필드-설계)
- [마이그레이션 전략](#마이그레이션-전략)
- [결과](#결과)
- [고려했으나 채택하지 않은 대안](#고려했으나-채택하지-않은-대안)
- [참고 자료](#참고-자료)

---

# ADR 0007 — X- 접두어 제거(RFC 6648)와 Auth-Passport 단일 컨텍스트 헤더 도입

## 상태

> **Completed**

**날짜**: 2026-03-20
**완료 날짜**: 2026-03-22

| Phase | 내용 | 상태 |
|-------|------|------|
| Phase 1 | SCG `JwtAuthenticationFilter`: 신규 헤더(`Auth-User-Id`, `Auth-Passport`) 병행 주입, `RequestSanitizeFilter` strip 대상 추가 | ✅ 완료 |
| Phase 2 | `booking-app` (4개소), `payment-app` (1개소) 헤더 소비를 `Auth-User-Id` → `Auth-Passport` (`PassportCodec.decode()`)로 전환. `GlobalExceptionHandler`에 `PassportCodecException` 핸들러 추가 (401) | ✅ 완료 |
| Phase 3 | `JwtAuthenticationFilter`에서 레거시 헤더(`X-Auth-User-Id`, `X-Auth-Roles`) 주입 코드 제거; `RequestSanitizeFilter` strip 목록 정리; 문서 일괄 업데이트 | ✅ 완료 |

---

## 컨텍스트

### RFC 6648 (2012년 6월)

RFC 6648은 HTTP 헤더에서 `X-` 접두어 사용을 공식적으로 폐기(deprecate)했다.

> "It is RECOMMENDED that new header field names not begin with the string 'X-'."
> — RFC 6648, Section 3

폐기 이유:
1. `X-` 접두어는 원래 "비공식 헤더"를 나타내기 위해 쓰였으나, 비공식 헤더가 사실상 표준이 됐을 때 이름을 바꿀 수 없는 문제가 반복됨 (`X-Forwarded-For` → 표준화 시도 실패)
2. 접두어 유무가 실제 충돌 방지에 기여하지 않음
3. 이름만 보고 헤더의 의미를 파악하기 어렵게 만듦

### 프로젝트 현황

현재 프로젝트는 아래 커스텀 헤더를 사용하며, 모두 `X-` 접두어가 붙어 있다.

| 헤더 | 용도 | 사용 위치 (코드 스캔 결과) |
|------|------|--------------------------|
| `X-Auth-User-Id` | JWT에서 추출한 userId 전파 | booking-app 4 endpoints, payment-app 1 endpoint, scg-app AuditLogFilter |
| `X-Auth-Roles` | JWT에서 추출한 역할 전파 | JwtAuthenticationFilter에서 주입. **downstream 소비 코드 없음(미사용)** |
| `X-Correlation-Id` | 요청 상관관계 ID | SCG 필터, 문서 |
| `X-Waiting-Token` | 대기열 통과 토큰 | booking-app ReservationController (예약 생성) |
| `X-Internal-Caller` | 서비스 간 호출 식별 | 문서 |
| `X-Internal-Token` | 내부 통신 전용 토큰 | RequestSanitizeFilter strip 대상 |
| `X-Request-Id` | 요청 ID (MDC) | RequestCorrelationFilter, AuditLogFilter |

### 분산 인증 컨텍스트 전파의 한계

현재 구조는 사용자 컨텍스트를 두 개의 독립 헤더(`X-Auth-User-Id`, `X-Auth-Roles`)로 전파한다.
앞으로 `clientIp`, `jti`(토큰 ID), `issuedAt` 같은 컨텍스트가 추가될 때마다 헤더가 하나씩 늘어나는 구조다.

---

## 문제

**1. RFC 6648 위반**
신규 커스텀 헤더에 `X-` 접두어를 사용하는 것은 2012년 이후 권장되지 않는 관행이다.

**2. "누가 주입했는가" 중심 네이밍**
`X-Auth-User-Id`의 `Auth`는 인증 필터가 주입한다는 구현 상세를 이름에 노출한다.
헤더 이름은 "무엇을 의미하는가"를 표현해야 한다.

**3. 컨텍스트 헤더 파편화**
userId와 roles가 분리된 헤더로 존재한다. 추가 컨텍스트(clientIp, jti)가 필요할 때 헤더가 계속 늘어난다.
downstream 서비스가 여러 헤더를 개별로 파싱해야 하므로 결합도가 높아진다.

---

## 결정

### 결정 1: X- 접두어 제거 및 헤더 이름 변경

**원칙**: 이름은 "무엇을 의미하는가"를 기준으로 정한다.

#### 변경 대상 (프로젝트 전용 커스텀 헤더)

| 현재 | 변경 후 | 변경 이유 |
|------|---------|---------|
| `X-Auth-User-Id` | `Auth-User-Id` | X- 제거. Auth(인증)에서 온 userId임을 유지 |
| `X-Auth-Roles` | (제거) | downstream 소비 코드 없음. Auth-Passport로 통합 |
| `X-Correlation-Id` | `Correlation-Id` | X- 제거. 의미 동일 |
| `X-Waiting-Token` | `Queue-Token` | X- 제거 + 도메인 의미 명확화 (대기열 토큰) |
| `X-Internal-Caller` | `Internal-Caller` | X- 제거 |
| `X-Internal-Token` | `Internal-Token` | X- 제거 |

#### 유지 대상 (de-facto 표준)

| 헤더 | 유지 이유 |
|------|---------|
| `X-Forwarded-For` | IANA 등록. 모든 로드밸런서·프록시가 이 이름을 사용. 변경 시 인프라 호환 파괴 |
| `X-Forwarded-Proto` | 동일 |
| `X-Request-Id` | Spring Cloud Gateway, AWS ALB, Nginx 등 생태계 전반에서 관례적으로 사용 |

> **X-Forwarded-For는 RFC 6648 적용 예외다.**
> RFC 6648은 신규 헤더에 대한 권고이며, 이미 생태계에 정착한 de-facto 표준을 소급 적용하는 것은
> RFC의 의도가 아니다. "표준이 될 가능성이 있는 비공식 헤더에 X-를 쓰지 말라"는 취지이지,
> "기존에 굳어진 모든 X- 헤더를 바꾸라"는 의미가 아니다.

---

### 결정 2: Auth-Passport 단일 컨텍스트 헤더 도입

사용자 인증 컨텍스트를 하나의 구조화된 헤더 `Auth-Passport`로 통합한다.

```
Auth-Passport: eyJ1c2VySWQiOiIxMDAiLCJyb2xlcyI6WyJVU0VSIl0sLi4ufQ==
```

값은 JSON을 Base64url 인코딩한 문자열이다.

**직렬화 형식: Base64url(JSON)**

```json
{
  "userId": "100",
  "roles": ["USER"],
  "jti": "550e8400-e29b-41d4-a716-446655440000",
  "issuedAt": 1710567600,
  "clientIp": "1.2.3.4"
}
```

**왜 서명을 추가하지 않는가**

`Auth-Passport`는 SCG가 생성해서 **내부망** downstream 서비스로 전달된다.
외부 클라이언트는 이 헤더를 직접 조작할 수 없다. `RequestSanitizeFilter`가 외부에서 유입된
`Auth-Passport` 헤더를 strip한 후 SCG가 검증된 값으로 다시 채운다.

신뢰 경계가 SCG 내부에 있으므로, Passport 자체에 HMAC 서명을 추가하면 복잡도만 늘어난다.
서명이 필요한 시나리오는 "SCG를 우회해 downstream 서비스를 직접 호출할 수 있는 경우"인데,
현재 아키텍처에서 internal API는 `InternalPathBlockFilter`로 차단하고
external API는 SCG를 필수적으로 거치므로 이 시나리오가 성립하지 않는다.

---

## Auth-Passport 필드 설계

### 코드 스캔 근거

아래 필드 목록은 현재 코드베이스에서 실제로 사용되거나 필요성이 확인된 것만 포함한다.

```
스캔 일자: 2026-03-20
스캔 대상:
  booking-app/src/main/java/.../api/controller/ReservationController.java
  payment-app/src/main/java/.../api/controller/PaymentController.java
  scg-app/src/main/java/.../filter/JwtAuthenticationFilter.java
  scg-app/src/main/java/.../filter/AuditLogFilter.java
  scg-app/src/main/java/.../filter/InternalPathBlockFilter.java
```

| 필드 | 타입 | 출처 | 현재 소비 위치 | 포함 근거 |
|------|------|------|--------------|---------|
| `userId` | String | JWT `sub` | booking-app 4 endpoints, payment-app 1 endpoint, AuditLogFilter | **핵심 — 현재 가장 많이 쓰이는 컨텍스트** |
| `roles` | List\<String\> | JWT `roles` | JwtAuthenticationFilter 주입, **downstream 소비 없음** | 현재 미사용이지만 향후 권한 검사(예: 관리자 API) 도입 시 즉시 필요. Passport에 포함해 확장 준비 |
| `jti` | String | JWT `jti` | **현재 미추출** | 향후 토큰 재사용(replay) 감지를 위해 필요. 결제 흐름에서 idempotency와 연계 가능 |
| `issuedAt` | long (epoch sec) | JWT `iat` | **현재 미추출** | downstream 서비스가 토큰 발급 시점을 알아야 할 때(freshness 검증) 필요. 현재 payment-app에서 예약 만료 검증 시 시간 기준이 필요한 경우에 활용 가능 |
| `clientIp` | String | `X-Forwarded-For` 첫 번째 값 | InternalPathBlockFilter 로그, AuditLogFilter 미사용(현재) | 감사 로그에 클라이언트 IP를 남기는 것은 보안 요건. AuditLogFilter가 개별 헤더 없이 Passport에서 읽을 수 있음 |

### 확정 필드 스펙

```java
// common-module (향후 생성) 또는 scg-app 내부
public record AuthPassport(
    String userId,           // JWT sub — downstream 서비스의 주요 인증 주체
    List<String> roles,      // JWT roles — 향후 권한 검사용
    String jti,              // JWT jti — 토큰 ID (replay 감지용, nullable)
    long issuedAt,           // JWT iat — 토큰 발급 시각 (epoch seconds)
    String clientIp          // X-Forwarded-For 첫 번째 값
) {}
```

### 포함하지 않은 필드와 이유

| 후보 필드 | 제외 이유 |
|----------|---------|
| `deviceType` | 현재 코드에 소비처 없음. JWT 클레임에도 없음. User-Agent 파싱은 별도 논의 필요 |
| `sessionId` | 현재 세션 기반 인증 없음. JWT stateless 방식에서 불필요 |
| `email` / `name` | 사용자 PII. 모든 서비스로 전파하면 불필요한 데이터 노출 범위 확대. user-app을 통해 필요 시 조회 |
| `exp` (만료 시각) | JwtAuthenticationFilter가 만료 토큰을 이미 차단하므로 downstream에 만료 시각 전달 불필요 |
| `requestId` | Passport는 "사용자 컨텍스트"이고 requestId는 "요청 컨텍스트". 분리 유지. `Correlation-Id` 헤더로 별도 전파 |

---

## 마이그레이션 전략

### 호환 기간 (Phase 1): 병행 주입

downstream 서비스가 준비되기 전까지, SCG는 구 헤더와 신 헤더를 **동시에** 주입한다.

```
JwtAuthenticationFilter 변경 후 주입 헤더:
  Auth-User-Id: 100          ← 신규 (X- 제거)
  Auth-Passport: base64(...)  ← 신규 (Passport)
  X-Auth-User-Id: 100        ← 레거시 (호환 기간 동안 유지)
```

- `RequestSanitizeFilter`의 strip 대상에 `Auth-User-Id`, `Auth-Passport` 추가
- 기존 `X-Auth-User-Id` 제거는 **하지 않음** (downstream 서비스 준비 전)

### 마이그레이션 (Phase 2): downstream 서비스 전환

각 서비스 컨트롤러를 순서대로 `X-Auth-User-Id` → `Auth-User-Id`로 변경한다.

```java
// Before
@RequestHeader("X-Auth-User-Id") Long userId

// After — Auth-User-Id 개별 헤더 사용 (단순한 경우)
@RequestHeader("Auth-User-Id") Long userId

// After — Auth-Passport 사용 (컨텍스트 전체가 필요한 경우)
@RequestHeader("Auth-Passport") String passportEncoded
// → PassportDecoder.decode(passportEncoded).userId()
```

전환 순서 (의존도 낮은 서비스부터):
1. `payment-app` — PaymentController.requestPayment (1개소)
2. `booking-app` — ReservationController (4개소)

### 레거시 헤더 제거 (Phase 3): 정리

모든 downstream 서비스가 신규 헤더로 전환된 것을 확인한 후:
- `JwtAuthenticationFilter`에서 `X-Auth-User-Id`, `X-Auth-Roles` 주입 코드 제거
- `RequestSanitizeFilter`의 strip 목록에서 구 헤더 제거
- ADR 이 문서를 `Completed` 상태로 전환하고 완료 날짜 기록 ← **완료 (2026-03-22)**

### 단계별 검증

| Phase | 검증 방법 |
|-------|---------|
| Phase 1 완료 | `JwtAuthenticationFilterTest`: 신규 헤더 존재 확인, 레거시 헤더도 존재 확인 |
| Phase 2 완료 | 각 서비스 단위 테스트: `Auth-User-Id` 헤더로 요청 성공 확인 |
| Phase 3 완료 | `RequestSanitizeFilterTest`: 레거시 헤더 strip 코드 제거 후 테스트 통과 확인 |

---

## 결과

### 긍정적 효과

**1. RFC 6648 준수**
신규 커스텀 헤더에 `X-` 접두어를 사용하지 않음으로써 명시적 표준을 따른다.

**2. 이름이 의미를 표현한다**
`Auth-User-Id`는 "인증을 통해 확인된 사용자 ID"를 의미한다.
`Auth-Passport`는 "인증 컨텍스트 전체를 담은 여권"을 의미한다.
헤더 이름만으로 역할을 파악할 수 있다.

**3. 컨텍스트 확장이 헤더 수 증가로 이어지지 않는다**
새 컨텍스트 필드가 필요할 때 `AuthPassport` 레코드 필드를 추가하면 된다.
downstream 서비스가 헤더 파싱 코드를 바꿀 필요 없이 `passport.newField()`로 접근 가능하다.

**4. 감사 로그 일관성**
`AuditLogFilter`가 `Auth-User-Id` 헤더 대신 `Auth-Passport`에서 userId와 clientIp를 함께 읽을 수 있다.
별도 IP 추출 로직 없이 Passport 하나만 파싱하면 된다.

### 부정적 효과 / 트레이드오프

**1. 마이그레이션 기간 헤더 중복**
Phase 1 동안 SCG가 구 헤더와 신 헤더를 동시에 주입한다.
요청당 헤더 크기가 일시적으로 증가한다.
`Auth-Passport` JSON(~100바이트) + Base64 인코딩 오버헤드 = 최대 ~150바이트 추가.

**2. downstream 서비스가 Base64 디코딩을 해야 한다**
단순 `@RequestHeader` 주입에서 `PassportDecoder.decode()` 호출로 변경해야 한다.
common-module에 유틸리티를 두면 각 서비스가 직접 구현하지 않아도 된다.

**3. 기존 API 문서, 테스트, ADR 업데이트 필요**
헤더 이름이 바뀌므로 api-spec.md, ADR-0002, ADR-0004, security-design.md 등
헤더를 언급하는 문서를 일괄 업데이트해야 한다. (마이그레이션 완료 시점에 일괄 처리)

---

## 고려했으나 채택하지 않은 대안

| 대안 | 설명 | 미채택 이유 |
|------|------|-----------|
| `Gateway-*` 접두어 사용 | `Gateway-User-Id`, `Gateway-Context` 등 | "누가 주입했는가(Gateway)"를 이름에 반영하는 방식. ADR 결정 원칙("무엇을 의미하는가" 중심)에 어긋남 |
| `X-` 접두어 유지 | RFC 6648을 내부 전용 헤더에 적용하지 않음 | RFC의 취지는 신규 헤더에 적용 권장. 적극 준수하는 방향을 선택 |
| Passport에 HMAC 서명 추가 | Passport를 mini-JWT로 발급 | 내부망에서 SCG를 신뢰 경계로 두는 설계에서 필요 없음. 복잡도 대비 보안 이득 없음 |
| JSON 평문 헤더 | Base64 인코딩 없이 JSON 직접 전송 | HTTP 헤더는 ISO-8859-1만 허용. 한국어 등 multi-byte 문자열이 포함될 가능성을 고려하면 Base64 인코딩이 안전 |
| Protobuf 직렬화 | 효율적인 바이너리 직렬화 | 브라우저/curl 디버깅 시 가독성 없음. 헤더 크기 차이(~150byte)가 의사결정을 바꿀 만큼 크지 않음 |
| W3C `traceparent` + baggage | 분산 트레이싱 표준으로 컨텍스트 전파 | baggage는 범용 key-value 전파 목적이고 인증 컨텍스트 전용 설계에 맞지 않음. traceId 전파는 별도 유지 |

---

## 참고 자료

- [RFC 6648 — Deprecating the "X-" Prefix](https://www.rfc-editor.org/rfc/rfc6648)
- [IANA — Message Headers Registry](https://www.iana.org/assignments/message-headers/)
- [ADR 0002](./0002-internal-header-design.md) — X-Auth-User-Id 최초 도입 배경
- [ADR 0004](./0004-jwt-validation-in-scg.md) — JWT 검증 위치 설계
- `scg-app/src/main/java/.../filter/JwtAuthenticationFilter.java`
- `scg-app/src/main/java/.../filter/RequestSanitizeFilter.java`
- `booking-app/src/main/java/.../api/controller/ReservationController.java` — userId 소비처
- `payment-app/src/main/java/.../api/controller/PaymentController.java` — userId 소비처
- `scg-app/src/main/java/.../filter/AuditLogFilter.java` — userId 소비처
