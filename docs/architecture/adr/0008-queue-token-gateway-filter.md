# ADR-0008: Gateway 레벨 Queue-Token 검증 도입

> 💡 **한 줄 요약**
> 예약 API 진입 전, SCG에서 Queue-Token 헤더의 존재와 UUID 형식을 검증하여
> 대기열을 거치지 않은 직접 예약 시도를 Fail-Fast로 차단한다.

- **상태**: Accepted
- **결정일**: 2026-03-27
- **관련 티켓**: SCRUM-49 (SCG Phase 2)
- **관련 ADR**: ADR-0004 (JWT 검증을 SCG에서), ADR-0006 (/internal/** 차단), ADR-0007 (Auth-Passport)

---

## Context (배경)

### 문제 상황

대기열 시스템(waitingroom-app)은 티켓팅 공정성의 핵심이다. 사용자는 반드시 아래 순서를 따라야 한다.

```
1. POST /api/v1/waiting-room/join   → 대기열 진입
2. GET  /api/v1/waiting-room/status → 순번 대기
3. 대기열 통과 → waitingroom-app이 Queue-Token(tokenId) 발급
4. POST /api/v1/reservations        → Queue-Token 포함하여 예약 요청
```

Phase 1에서 booking-service 라우팅이 SCG에 추가되었지만, Queue-Token 검증이 없어
**클라이언트가 대기열을 건너뛰고 POST /api/v1/reservations를 직접 호출할 수 있는 보안 공백**이 존재했다.

현재 구조에서 Queue-Token 검증은 booking-app 내부(WaitingRoomInternalClient → waitingroom-app DB)에서만 수행된다.
무효한 토큰을 가진 요청도 SCG → booking-app → waitingroom-app 까지 전달되어 불필요한 내부 API 호출이 발생한다.

### 해결해야 할 질문

**"Queue-Token 검증을 어느 계층에서, 어디까지 수행해야 하는가?"**

---

## Decision (결정)

**책임을 두 계층으로 명확히 분리한다.**

| 계층 | 검증 내용 | 구현체 |
|------|---------|--------|
| **SCG (Gateway)** | Queue-Token 헤더 존재 여부 + UUID 형식 검증 | `QueueTokenValidationFilter` |
| **booking-app (도메인)** | 토큰의 실제 유효성 (ACTIVE 상태, 만료 여부, userId/eventId 일치) | `WaitingRoomInternalClient` → waitingroom-app |

### 검증 제외 조건

- `GET /api/v1/reservations/**` (조회): 대기열 없이 예약 내역 확인 가능
- `gateway.queue-token.enabled: false` 환경변수 설정 시 (로컬 개발 편의)

### 전파 규약

SCG는 UUID 형식 검증 통과 후 **`Auth-Queue-Token: {tokenId}`** 헤더를 downstream에 추가한다.
booking-app은 이 헤더를 WaitingRoomInternalClient 호출 시 tokenId로 사용한다.

**ADR-0007 준수**: `Auth-*` 네임스페이스는 "SCG가 검증 후 주입하는 신뢰된 헤더"의 표준이다.
`X-` 접두사는 RFC 6648(2012) 이후 신규 헤더에 사용하지 않는다.
`Auth-Queue-Token`은 `Auth-Passport`, `Auth-User-Id`와 동일한 네임스페이스를 따른다.

### Auth-Passport와 분리하는 이유

"Auth-Passport에 queueTokenId를 포함하면 어떨까?"라는 질문이 있을 수 있다. 분리를 유지하는 이유:

| | Auth-Passport | Auth-Queue-Token |
|---|---|---|
| **의미** | 당신이 누구인가 (인증 컨텍스트) | 지금 예약할 자격이 있는가 (비즈니스 컨텍스트) |
| **생성 주체** | JwtAuthenticationFilter (+4), JWT 클레임에서 추출 | QueueTokenValidationFilter (+5), Queue-Token 헤더에서 추출 |
| **적용 범위** | 모든 인증 요청 | `POST /api/v1/reservations/**`에만 해당 |
| **데이터 원천** | JWT 서명된 토큰 | 클라이언트가 별도 제공한 Queue-Token 헤더 |

Auth-Passport에 포함하려면 +5 필터가 Auth-Passport를 디코딩 → queueTokenId 추가 → 재인코딩해야 한다.
이는 두 필터 간 결합도를 높이고, Auth-Passport가 "인증 컨텍스트 전용"이라는 단일 책임을 위반한다.

---

## 검토한 대안

| 대안 | 장점 | 단점 | 미채택 이유 |
|------|------|------|-----------|
| **A: 현재 방식 유지** (booking-app에서만 검증) | 변경 없음 | 대기열 우회 시도가 SCG 통과, 불필요한 내부 API 호출 | 보안 공백, downstream 부하 |
| **B: SCG에서 Redis로 토큰 유효성 전부 검증** | 빠른 응답, DB 미조회 | SCG에 도메인 로직 유입, waitingroom Redis 데이터 모델 의존 | 관심사 분리 위반, 결합도 상승 |
| **C: SCG → waitingroom Internal API 호출** | 완전한 검증 | SCG가 downstream 서비스를 직접 호출하는 역방향 의존성 | 아키텍처 원칙 위반 (Gateway는 라우팅만) |
| **✅ D: SCG(형식) + booking-app(의미)로 분리** | Fail-Fast, 도메인 로직 격리, Gateway 단순성 유지 | 완전한 검증은 여전히 booking-app까지 도달해야 함 | 채택 |

---

## Consequences (결과)

### ✅ 긍정적 효과

**보안 강화 (Fail-Fast)**
- 헤더 누락 요청 → 403, booking-app에 도달하지 않음
- UUID 형식 오류 요청 → 403, booking-app에 도달하지 않음
- 대기열 우회 시도가 Gateway 로그에 WARN으로 기록되어 보안 이벤트 탐지 가능

**downstream 부하 절감**
- 형식 자체가 잘못된 요청을 Gateway에서 즉시 차단
- booking-app → WaitingRoomInternalClient 불필요 호출 감소

**관심사 분리 명확화**
- SCG: 인증(JWT) + 비즈니스 컨텍스트 형식 검증 (Queue-Token 형식)
- booking-app: 도메인 규칙 검증 (토큰 유효성, 상태, 소유자)

**보안 관제 연계 가능**
- `[QUEUE_TOKEN] Missing header` WARN 로그 패턴을 Elasticsearch/AlertManager로 집계하면
  대기열 우회 시도 빈도를 모니터링하는 보안 대시보드 구현 가능

### ⚠️ 트레이드오프

- UUID 형식이 맞더라도 이미 USED/EXPIRED된 토큰은 SCG를 통과한다 (도메인 계층에서 차단)
- `X-Queue-Token-Id` 헤더를 RequestSanitizeFilter(+3) 대상에 추가해야 외부 위조를 방어할 수 있음
  → QueueTokenValidationFilter(+5) 내부에서 기존 헤더를 먼저 제거 후 신뢰된 값으로 재설정하여 방어

### 🔒 위조 방어 설계

```
클라이언트가 Queue-Token 없이 Auth-Queue-Token 헤더를 직접 주입하는 시나리오:

1. RequestSanitizeFilter(+3): Auth-Queue-Token 제거
   → application.yml sanitize-headers에 Auth-Queue-Token 포함 (ADR-0007 패턴 동일 적용)
2. QueueTokenValidationFilter(+5): 추가 방어로 mutate() 전에 Auth-Queue-Token 명시적 제거
   → Queue-Token 헤더가 없으면 → 403 반환
   → Queue-Token이 유효한 UUID이면 → Auth-Queue-Token을 신뢰된 값으로 재설정

결론: 이중 방어(Sanitize + 필터 내부)로 클라이언트의 직접 주입이 불가능하다.
```

이 패턴은 `Auth-Passport`, `Auth-User-Id`와 완전히 동일한 위조 방어 전략이다.

---

## 구현 위치

- 필터: `scg-app/src/main/java/.../filter/QueueTokenValidationFilter.java`
- 설정: `application.yml` → `gateway.queue-token.*`
- 테스트: `scg-app/src/test/java/.../filter/QueueTokenValidationFilterTest.java`
- Filter 순서: `HIGHEST_PRECEDENCE + 5` (JwtAuthenticationFilter +4 이후)

## 필터 실행 순서 (Phase 2 반영)

```
요청
  ├─ [+0] RequestCorrelationFilter   : X-Request-Id, MDC
  ├─ [+1] AccessLogFilter            : 전체 요청 로깅
  ├─ [+2] InternalPathBlockFilter    : /internal/** → 403
  ├─ [+3] RequestSanitizeFilter      : 위조 헤더 제거
  ├─ [+4] JwtAuthenticationFilter    : JWT 검증 → Auth-Passport 주입
  ├─ [+5] QueueTokenValidationFilter : Queue-Token 형식 검증 → X-Queue-Token-Id 주입  ← NEW
  ├─ [+6] AuditLogFilter             : 인증된 행위 감사 로그
  ├─ [+7] SecurityHeaderFilter       : 보안 응답 헤더
  ├─ [+8] BulkheadFilter             : 동시 요청 제한
  ├─ [+9] RequestLogMaskingFilter    : 민감 헤더 마스킹
  └─ [route] RateLimiter → CircuitBreaker → Retry → downstream
```

---

## 참고

- ADR-0004: JWT 검증을 SCG에서 일원화 (동일한 Fail-Fast 철학)
- ADR-0006: /internal/** 차단 (보안 계층화)
- `docs/services/waitingroom/queue-design.md`: 대기열 토큰 발급 설계
- `docs/services/booking/seat-locking-design.md`: 예약 처리 흐름
