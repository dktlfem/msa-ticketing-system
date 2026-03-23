---
title: "ADR 0004 — JWT 검증을 SCG(Gateway)에서 수행하는 이유"
last_updated: "2026-03-20"
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

# ADR 0004 — JWT 검증을 SCG(Gateway)에서 수행하는 이유

## 상태

> **Accepted** (헤더명 관련 내용은 [ADR-0007](./0007-header-naming-and-auth-passport.md)로 업데이트됨)

> **헤더명 변경 안내 (2026-03-22)**: 이 ADR에서 언급된 `X-Auth-User-Id`, `X-Auth-Roles`는
> ADR-0007 Phase 3 완료로 `Auth-User-Id`, `Auth-Passport`로 대체됨.
> JWT 검증 위치(SCG 일원화) 결정 자체는 여전히 유효합니다.

**날짜**: 2026-03-20

---

## 컨텍스트

시스템은 6개 마이크로서비스(user-app, waitingroom-app, concert-app, booking-app, payment-app, scg-app)로 구성된다.
외부 클라이언트의 모든 요청은 scg-app을 거쳐 downstream 서비스로 라우팅된다.

인증 처리 위치를 결정할 때 두 가지 방향이 있었다.

**옵션 A**: 각 마이크로서비스가 직접 JWT를 검증한다.
**옵션 B**: SCG가 JWT를 검증하고, 검증된 userId를 헤더(X-Auth-User-Id)로 전달한다.

결정이 필요한 이유:
- booking-app, payment-app은 현재 `@AuthUserId`(X-Auth-User-Id 헤더)를 기반으로 userId를 식별한다.
- user-app에 login(JWT 발급) API가 아직 미구현 상태이며, JWT는 staging에서 수동 발급 중이다.
- JWT secret 교체, 알고리즘 변경 등 키 관리가 이후 과제로 예정되어 있다.

---

## 결정

**JWT 검증을 scg-app의 `JwtAuthenticationFilter`(GlobalFilter, order=HIGHEST_PRECEDENCE+4)에서 수행한다.**

검증 성공 시:
- `X-Auth-User-Id: {sub}` 헤더를 downstream 요청에 추가한다.
- `X-Auth-Roles: {roles}` 헤더를 downstream 요청에 추가한다.

`RequestSanitizeFilter`(order=HIGHEST_PRECEDENCE+3)가 먼저 실행되어 클라이언트가 보낸 동명 헤더를 제거한다.
이 순서가 보장되어야 downstream 서비스가 헤더를 신뢰할 수 있다.

```
클라이언트 요청 (Authorization: Bearer {jwt}, X-Auth-User-Id: 999 위조 시도)
  ↓
RequestSanitizeFilter (+3):   X-Auth-User-Id 제거
  ↓
JwtAuthenticationFilter (+4): JWT 검증 → X-Auth-User-Id: 100 (실제 값) 추가
  ↓
downstream 서비스:             X-Auth-User-Id: 100 신뢰
```

---

## 결과

### 긍정적 효과

**1. 인증 코드 중복 제거**

booking-app, payment-app이 각자 jjwt 의존성을 추가하고 JWT 파싱 로직을 관리하지 않아도 된다.
JWT 검증은 scg-app 한 곳에만 존재한다.

**2. JWT secret 관리 단일화**

secret 교체, 알고리즘 변경(HS256 → RS256) 시 scg-app 하나만 수정하면 된다.
각 서비스를 순서에 맞게 재배포할 필요가 없다.

**3. downstream 서비스의 단순화**

booking-app과 payment-app은 JWT를 파싱하지 않는다.
`@AuthUserId` 어노테이션으로 X-Auth-User-Id 헤더만 읽으면 인증 주체를 알 수 있다.
서비스가 "비즈니스 로직"에만 집중할 수 있다.

**4. 인증 실패 응답 형식 일관성**

401 응답이 scg-app 하나에서만 나오므로 RFC 7807 ProblemDetail 형식이 보장된다.
각 서비스가 다른 형식으로 401을 반환하는 불일치가 없다.

### 부정적 효과 / 트레이드오프

**1. SCG가 SPOF(Single Point of Failure)에 가까워진다**

SCG가 죽으면 모든 외부 요청이 차단된다. 이는 SCG 역할 자체의 특성이지만,
JWT 검증까지 담당하면 SCG 장애 시 인증 bypass 수단이 없다.
→ scg-app HA(고가용성) 구성이 중요해진다.

**2. 서비스 간(internal) 통신에 JWT가 없다**

payment-app → booking-app 내부 호출은 JWT 없이 X-Internal-Caller 헤더만 사용한다.
internal 경로는 SCG를 거치지 않으므로 JWT 검증이 적용되지 않는다.
→ InternalPathBlockFilter로 외부에서 /internal/**에 접근하지 못하도록 차단하는 것이 전제 조건이다.
   (ADR-0006 참조)

**3. user-app이 JWT를 발급하지 않으면 end-to-end 인증 흐름이 불완전하다**

현재 user-app에 login API가 없다. JWT는 staging에서 수동으로 발급하고 있다.
scg-app이 검증만 하고 발급이 없는 상태이므로, 실제 인증 흐름 완성은 user-app 개발에 의존한다.

---

## 고려했으나 채택하지 않은 대안

| 대안 | 설명 | 미채택 이유 |
|------|------|-----------|
| 각 서비스가 직접 JWT 검증 | 각 서비스가 jjwt를 추가하고 파싱 로직 관리 | secret 분산, 코드 중복, 서비스 경계 오염 |
| Spring Security (각 서비스에 적용) | `@EnableWebSecurity` + JWT Filter per service | MSA에서 인증 관심사를 각 서비스에 분산시키는 방향은 복잡도 증가. 현재 booking/payment가 서블릿 기반이므로 Reactive SCG와 설정 공유가 어려움 |
| API Key 방식 | JWT 대신 정적 API Key | 만료/갱신 메커니즘이 없어 보안 수준 낮음. 사용자 식별 정보를 담을 수 없어 booking/payment의 userId 식별 불가 |
| Service Mesh (Istio) | 사이드카에서 mTLS + 인증 처리 | 인프라 복잡도가 현재 프로젝트 단계에 비해 과도함. Docker Compose 기반 staging에는 적합하지 않음 |

---

## 참고 자료

- `scg-app/src/main/java/.../filter/JwtAuthenticationFilter.java`
- `scg-app/src/main/java/.../filter/RequestSanitizeFilter.java`
- `scg-app/src/test/java/.../filter/JwtAuthenticationFilterTest.java`
- [ADR 0002](./0002-internal-header-design.md) — X-Auth-User-Id 헤더 설계
- [ADR 0006](./0006-internal-path-filter.md) — /internal/** 차단 설계
- [`docs/security/security-design.md`](../../security/security-design.md)
