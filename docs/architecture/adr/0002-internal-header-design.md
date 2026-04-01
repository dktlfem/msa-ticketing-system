---
title: "ADR 0002 — SCG 내부 헤더 설계: X-Auth-User-Id 채택 및 헤더 신뢰 경계 정의"
last_updated: "2026-03-19"
author: "민석"
reviewer: ""
---

## 목차

- [상태](#상태)
- [컨텍스트](#컨텍스트)
- [결정](#결정)
- [결과](#결과)
- [고려했으나 채택하지 않은 대안](#고려했으나-채택하지-않은-대안)
- [참고 자료](#참고-자료)

---

# ADR 0002 — SCG 내부 헤더 설계: X-Auth-User-Id 채택 및 헤더 신뢰 경계 정의

## 상태

> **Superseded by [ADR-0007](./0007-header-naming-and-auth-passport.md)**

> ~~Accepted~~ → **Superseded** (2026-03-22)
>
> ADR-0007 Phase 3 완료로 `X-Auth-User-Id`가 `Auth-User-Id`/`Auth-Passport`로 대체됨.
> 이 문서는 `X-User-Id` → `X-Auth-User-Id` 일원화 의사결정의 **역사적 기록**으로 보존됩니다.

**날짜**: 2026-03-19

---

## 컨텍스트

SCG(scg-app)가 JWT를 검증한 뒤 하위 서비스로 어떤 헤더를 어떤 방식으로 전달할지 결정이 필요했다.
설계 시 아래 여섯 가지를 동시에 고려했다.

### 고려 항목

**1. 인증 헤더**

SCG가 JWT를 검증한 뒤 하위 서비스에 `userId`를 헤더로 전달한다.
하위 서비스는 이 헤더를 내부망 신뢰 값으로 취급하며 자체 검증을 수행하지 않는다.
보안 수준을 높이기 위해 JWT 원문을 전달하거나 Service Mesh(mTLS)를 병행할지 검토했다.

**2. 추적 헤더**

분산 환경에서 서비스 간 요청 흐름을 추적하려면 표준화된 컨텍스트 전파가 필요하다.
W3C Trace Context 표준(`traceparent`, `tracestate`)과 벤더 고유 헤더 방식을 비교했다.

**3. RFC 6648 (2012) — `X-` 접두사 폐기 권고**

IETF RFC 6648은 `X-` 접두사를 사용한 비표준 헤더 관례를 공식 폐기했다.
`X-Auth-User-Id` 대신 `Auth-User-Id`로 정의할지 검토했다.

**4. Immutability (불변 전파)**

`Transaction-ID` 같은 핵심 식별자는 여러 서비스를 경유해도 변형 없이 동일하게 전파되어야 한다.
중간 서비스가 이 값을 덮어쓰거나 새로 생성하면 추적이 끊긴다.

**5. 보안 — 헤더 위변조 방지**

외부 클라이언트가 `X-Auth-User-Id: 1` 같은 헤더를 직접 삽입해 내부 서비스를 속일 수 있다.
SCG에서 외부 유입 헤더를 신뢰하지 않고 검증 후 새로 발급하는 방식이 필요하다.
`X-Forwarded-For` 변조 방지와 같은 원칙이다.

**6. Observability — 단일 ID 묶음 추적**

Log, Trace, Metric이 서로 다른 ID 체계를 사용하면 장애 발생 시 여러 도구를 교차 검색해야 한다.
하나의 헤더 ID로 Kibana 로그, Jaeger trace, Grafana 메트릭을 동시에 조회할 수 있어야 한다.

---

## 결정

### 1. 인증 헤더: `X-Auth-User-Id` 채택

SCG의 `JwtAuthenticationFilter`가 JWT를 검증한 뒤 `X-Auth-User-Id: {userId}`를 내부 요청에 추가한다.
하위 서비스(`booking-app`, `payment-app`)는 이 헤더만을 신뢰하며, 자체 JWT 파싱을 하지 않는다.

채택 이유:
- Spring Security, Nginx, Kong 등 실무 생태계에서 `X-` 접두사가 관례적으로 널리 사용되고 있어 호환성이 높다.
- `Auth-` 접두사가 "SCG에서 검증이 완료된 사용자 식별자"임을 헤더 이름 자체에서 명시한다.
- RFC 6648의 폐기는 강제 규범이 아닌 권고(SHOULD)이며, 팀 컨벤션으로 `X-` 관례를 유지한다.

### 2. JWT 원문 전달 — 미채택

내부망 신뢰 경계 안에서는 `userId`만 필요하다.
JWT 원문을 전달하면 모든 하위 서비스에 서명 검증 로직과 의존 라이브러리가 추가되며,
요청마다 파싱 비용이 발생한다. 향후 Service Mesh 도입 시 재검토한다.

### 3. 추적 헤더: W3C Trace Context 채택

`traceparent`, `tracestate` (W3C Trace Context, RFC 9532) 표준을 사용한다.
`micrometer-tracing-bridge-brave`가 자동으로 MDC에 주입하며 Jaeger/OTEL과 호환된다.
벤더 중립적이므로 관측성 스택 교체 시에도 코드 변경이 불필요하다.

### 4. 외부 유입 헤더 strip 후 재발급

SCG의 `RequestSanitizeFilter`가 외부 요청에서 `X-Auth-User-Id`를 제거한다.
이후 `JwtAuthenticationFilter`가 JWT 검증값 기반으로 `X-Auth-User-Id`를 새로 발급한다.
외부 클라이언트가 임의로 삽입한 인증 헤더가 내부망에 도달하지 않도록 보장한다.

### 내부 헤더 표준 정의

| 헤더 | 설정 주체 | 소비 주체 | 설명 |
|------|---------|---------|------|
| `X-Auth-User-Id` | scg-app `JwtAuthenticationFilter` | booking-app, payment-app | JWT 검증 완료된 userId. 외부 유입 동명 헤더는 strip 후 재발급. |
| `X-Correlation-Id` | scg-app `GatewayAccessLogGlobalFilter` | 전체 서비스 | 요청 단위 추적 ID. 없으면 UUID 생성. 불변 전파. |
| `traceparent` | micrometer-tracing (자동) | Jaeger (OTEL) | W3C Trace Context. 서비스 간 span 연결. |
| `tracestate` | micrometer-tracing (자동) | Jaeger (OTEL) | W3C Trace Context 벤더 확장. |
| `X-Internal-Caller` | 서비스 간 호출 측 | 수신 서비스 | 내부 API 호출 식별. `/internal/**` 접근 제어 보조. |

---

## 결과

### 긍정적 효과

- **헤더 계약 일원화**: 문서(`api-spec.md`)와 코드(`@RequestHeader`) 사이에 `X-Auth-User-Id`로 통일되어 불일치 제거
- **보안 경계 명확화**: 외부 유입 헤더 strip → 내부 재발급 패턴으로 헤더 위변조 경로 차단
- **Observability 연결**: `X-Correlation-Id`(Kibana) + `traceId`(Jaeger) + `X-Auth-User-Id`(감사 로그)로 단일 요청을 세 도구에서 교차 검색 가능
- **하위 서비스 단순화**: booking-app, payment-app이 JWT 파싱 없이 헤더 값만 신뢰하면 되므로 인증 로직이 SCG에 집중됨

### 부정적 효과 / 트레이드오프

- **내부망 신뢰 전제**: 하위 서비스가 헤더를 무조건 신뢰하므로, SCG 우회 경로가 존재하면 인증 없이 접근 가능. `/internal/**` 외부 차단과 결합 필요.
- **JWT 검증 단일 장애점**: SCG의 `JwtAuthenticationFilter` 버그 시 잘못된 userId가 전파됨. 현재 단계에서 허용 가능한 위험으로 판단.
- **Postman/k6 등 외부 도구**: 직접 호출 테스트 시 `X-Auth-User-Id` 헤더를 수동으로 설정해야 함 (`X-User-Id` 사용 불가).

### 변경된 파일 / 영향 범위

| 파일 | 변경 내용 |
|------|----------|
| `payment-app/src/.../api/controller/PaymentController.java` | `@RequestHeader("X-User-Id")` → `@RequestHeader("X-Auth-User-Id")` (L37), 주석 수정 (L32) |
| `booking-app/src/.../api/controller/ReservationController.java` | `@RequestHeader("X-User-Id")` → `@RequestHeader("X-Auth-User-Id")` (L48/67/84/106), 주석 수정 (L26/39) |
| `booking-app/src/.../api/controller/ReservationControllerTest.java` | `.header("X-User-Id", ...)` → `.header("X-Auth-User-Id", ...)` (L63/82/113) |
| `docs/api/api-spec.md` | 이미 `X-Auth-User-Id` 기준으로 작성됨 — 변경 없음 |
| `scg-app/src/.../filter/JwtAuthenticationFilter.java` | 이미 `X-Auth-User-Id` 설정 — 변경 없음 |
| `scg-app/src/.../filter/RequestSanitizeFilter.java` | 이미 `X-Auth-User-Id` 허용 목록 포함 — 변경 없음 |

### 향후 검토 사항

- **Service Mesh 도입 시**: Istio mTLS + JWT propagation으로 전환 여부 재검토. 이 경우 `X-Auth-User-Id` 대신 JWT 원문 또는 SVID(SPIFFE) 기반 서비스 인증으로 전환 가능.
- **`X-` 접두사 제거 시점**: RFC 6648 준수를 위한 `Auth-User-Id` 전환 여부를 팀 컨벤션 재정립 시 검토.

---

## 고려했으나 채택하지 않은 대안

| 대안 | 설명 | 미채택 이유 |
|------|------|-----------|
| `Auth-User-Id` (X- 접두사 제거) | RFC 6648 권고에 따른 표준 헤더명 | Spring Security, Nginx, Kong 등 기존 도구가 `X-` 접두사를 관례로 사용 중이라 팀 내 혼란 우려. 강제 규범이 아닌 권고이므로 기존 관례 유지. |
| JWT 원문 전달 | Authorization 헤더를 SCG → 하위 서비스까지 그대로 전달 | 모든 하위 서비스에 JWT 서명 검증 로직과 의존 라이브러리 추가 필요. 내부망에서 userId만 필요한 상황에서 파싱 비용 과다. |
| Service Mesh (Istio mTLS) | 서비스 간 인증을 mTLS + SPIFFE SVID로 처리 | 현 단계 인프라 복잡도(사이드카 프록시, 인증서 관리) 대비 이점 부족. 서비스 수 증가 및 보안 요구 강화 시 재검토 대상. |
| 벤더 고유 추적 헤더 (`X-B3-TraceId` 등) | Zipkin B3 포맷 사용 | W3C Trace Context가 OTEL/Jaeger와 호환되며 벤더 중립적. 향후 관측성 스택 교체 시 코드 변경 불필요. |

---

## 참고 자료

- [`docs/api/api-spec.md`](../../api/api-spec.md) — 공통 헤더 표 (1.2항)
- [`docs/architecture/overview.md`](../overview.md) — 서비스 간 호출 의존성 및 보안 구조
- [RFC 6648 — Deprecating the "X-" Prefix and Similar Constructs in Application Protocols](https://www.rfc-editor.org/rfc/rfc6648)
- [W3C Trace Context (traceparent / tracestate)](https://www.w3.org/TR/trace-context/)
- [Micrometer Tracing — MDC 자동 주입](https://micrometer.io/docs/tracing)
