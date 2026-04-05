---
title: "ErrorCode 카탈로그 및 예외 처리 정책"
last_updated: 2026-04-05
author: "민석"
reviewer: ""
---

## 목차
- [Background](#background)
- [Problem](#problem)
- [Design](#design)
- [ErrorCode 카탈로그](#errorcode-카탈로그)
- [예외 처리 흐름](#예외-처리-흐름)
- [Trade-offs](#trade-offs)
- [Failure Scenarios](#failure-scenarios)
- [신규 ErrorCode 추가 체크리스트](#신규-errorcode-추가-체크리스트)
- [관련 문서](#관련-문서)

# ErrorCode 카탈로그 및 예외 처리 정책

> 이 문서는 **common-module의 ErrorCode Enum**과 **GlobalExceptionHandler**가 만들어내는 예외 처리 표준을 정의합니다.
> 모든 마이크로서비스(booking-app, payment-app, concert-app, waitingroom-app, user-app)는 이 카탈로그에 등록된 코드만 사용합니다.

---

## Background

MSA 환경에서 서비스 간 에러 응답이 제각각이면 클라이언트·운영·모니터링이 모두 혼란을 겪습니다. 이 프로젝트는 초기부터 다음 3가지를 통일했습니다.

1. **ErrorCode Enum** 단일 소스 (`common-module/global/error/ErrorCode.java`)
2. **ErrorResponse** 단일 포맷 (`code`, `message`, `status` 3필드 record)
3. **GlobalExceptionHandler** 단일 진입점 (`@RestControllerAdvice`)

이를 통해 클라이언트는 `code` 접두사만 보면 도메인을 식별할 수 있고, 운영자는 Kibana에서 `code=P006` 같은 쿼리로 특정 이슈를 즉시 필터링할 수 있습니다.

---

## Problem

**왜 Enum으로 강제하는가:**

| 대안 | 문제점 |
|------|-------|
| 각 서비스가 문자열 리터럴 직접 사용 | 오타 발생, 동일 의미 코드가 서비스마다 다르게 표기됨 |
| HTTP 상태 코드만 사용 (예: 404, 409) | 도메인 컨텍스트 손실 (어떤 리소스의 404인지 불명) |
| 서비스별 자체 ErrorCode | 모니터링·로그 집계 시 코드 충돌, 중복 정의 |

**왜 common-module에 두는가:**

ErrorCode는 어떤 Spring 기능(WebFlux/JPA)에도 의존하지 않고 순수 상수 집합입니다. common-module은 JPA를 포함하지만 ErrorCode 자체는 WebFlux 모듈(scg-app)에서도 문제없이 참조할 수 있는 수준의 가벼운 구성입니다. (*shared-kernel 분리 대상은 PassportCodec류처럼 WebFlux↔JPA 충돌이 실제 발생하는 클래스로 한정했습니다. SCRUM-56 참조.*)

---

## Design

### 코드 체계

모든 ErrorCode는 **도메인 접두사 + 3자리 숫자** 형식을 따릅니다.

| 접두사 | 도메인 | 예시 |
|--------|--------|------|
| `C` | Common (공통 검증/시스템) | `C001`, `C999` |
| `E` | Event (공연) | `E001` ~ `E004` |
| `ES` | EventSchedule (회차) | `ES001` |
| `W` | WaitingRoom (대기열) | `W001` |
| `S` | Seat (좌석) | `S001`, `S002` |
| `R` | Reservation (예약) | `R001` ~ `R003` |
| `P` | Payment (결제) | `P001` ~ `P006` |
| `A` | Auth (인증/인가) | `A001` ~ `A004` |

숫자는 **001부터 도메인 내 순차 증가**하며, 의미가 유사한 코드를 묶기 위해 의도적으로 번호 구간을 비워두는 것(reserved gap)은 허용합니다.

### ErrorResponse 스키마

```java
public record ErrorResponse(String code, String message, int status) { }
```

JSON 예시:
```json
{
  "code": "R003",
  "message": "현재 해당 좌석에 대한 예약 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.",
  "status": 429
}
```

클라이언트는 `code`로 i18n 키 매핑, `status`로 재시도 여부 판단, `message`를 사용자 표시용 fallback으로 활용합니다.

---

## ErrorCode 카탈로그

### 공통 (C)

| 코드 | HTTP | 사용처 | 메시지 |
|------|------|--------|-------|
| C001 | 400 | `@Valid` 실패, `IllegalArgumentException`, 잘못된 요청 본문 | 적절하지 않은 입력 값입니다. |
| C999 | 500 | 예상치 못한 예외 (Exception.class fallback) | Internal Server Error |

### 공연 (E) / 회차 (ES)

| 코드 | HTTP | 사용처 | 메시지 |
|------|------|--------|-------|
| E001 | 404 | 존재하지 않는 이벤트 ID 조회 | 존재하지 않는 공연입니다. |
| E002 | 400 | 종료·취소된 공연 예매 시도 | 이미 종료되거나 취소된 공연입니다. |
| E003 | 400 | 예매 오픈 시각 이전 요청 | 아직 예매 오픈 전입니다. |
| E004 | 409 | 전석 매진 | 모든 좌석이 매진되었습니다. |
| ES001 | 404 | 존재하지 않는 회차 ID 조회 | 존재하지 않는 회차입니다. |

### 대기열 (W)

| 코드 | HTTP | 사용처 | 메시지 |
|------|------|--------|-------|
| W001 | 409 | 이미 유효한 대기열 토큰 보유한 사용자의 재발급 요청 | 이미 유효한 입장 토큰을 보유하고 있습니다. |

### 좌석 (S)

| 코드 | HTTP | 사용처 | 메시지 |
|------|------|--------|-------|
| S001 | 409 | JPA `@Version` 낙관적 락 실패 (`ObjectOptimisticLockingFailureException`) | 다른 사용자가 먼저 좌석을 선택했습니다. |
| S002 | 404 | 존재하지 않는 좌석 ID 조회 | 존재하지 않는 좌석입니다. |

### 예약 (R)

| 코드 | HTTP | 사용처 | 메시지 |
|------|------|--------|-------|
| R001 | 404 | 존재하지 않는 예약 ID 조회 | 존재하지 않는 예약입니다. |
| R002 | 409 | 결제 대기 상태가 아닌 예약에 확정 요청 | 결제 대기 중이 아니거나 만료된 예약입니다. |
| R003 | 429 | Redisson 분산락 획득 실패 (동시 예약 경합) | 현재 해당 좌석에 대한 예약 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요. |

### 결제 (P)

| 코드 | HTTP | 사용처 | 메시지 |
|------|------|--------|-------|
| P001 | 404 | 존재하지 않는 결제 ID 조회 | 존재하지 않는 결제입니다. |
| P002 | 409 | 이미 결제가 생성된 예약에 재요청 | 이미 결제가 존재하는 예약입니다. |
| P003 | 400 | 요청 금액과 예약 금액 불일치 | 결제 금액이 일치하지 않습니다. |
| P004 | 409 | 현재 결제 상태에서 허용되지 않는 전이 요청 | 현재 상태에서 허용되지 않는 결제 요청입니다. |
| P005 | 500 | PG 연동 오류 (TossPaymentsClient 예외) | 결제 처리 중 오류가 발생했습니다. |
| P006 | 409 | 멱등성 키 충돌 (동일 idempotencyKey 동시 요청) | 동일한 요청이 처리 중입니다. |

### 인증 (A)

| 코드 | HTTP | 사용처 | 메시지 |
|------|------|--------|-------|
| A001 | 401 | `Auth-Passport` 헤더 누락 / Base64·JSON 디코드 실패 | 이메일 또는 비밀번호가 올바르지 않습니다. *(메시지는 상황별로 override)* |
| A002 | 401 | JWT 서명 검증 실패 | 유효하지 않은 토큰입니다. |
| A003 | 401 | JWT 만료 | 만료된 토큰입니다. |
| A004 | 401 | 리프레시 토큰 부재 또는 만료 | 리프레시 토큰이 존재하지 않거나 만료되었습니다. |

---

## 예외 처리 흐름

### 요청 → Gateway → 서비스 → 응답

```
[Client]
   │
   ▼
[scg-app: JwtAuthenticationFilter]
   │  ├─ JWT 검증 실패 → 401 직접 응답 (A002/A003)
   │  └─ 성공 → Auth-Passport 헤더 생성 후 downstream 전달
   ▼
[booking-app / payment-app / ...]
   │
   ▼
[Controller → Service → Repository]
   │
   └─ 예외 발생
         │
         ▼
    [GlobalExceptionHandler]
         │
         ├─ BusinessException            → ErrorCode 기반 응답 (warn 로그, stack 생략)
         ├─ ObjectOptimisticLockingEx    → S001 (409)
         ├─ MethodArgumentNotValidEx     → C001 (400) + fieldError 메시지
         ├─ MissingRequestHeaderEx       → Auth-Passport면 A001, 아니면 C001
         ├─ PassportCodecException       → A001 (401)
         ├─ IllegalArgumentException     → C001 (400)
         ├─ ResponseStatusException      → status 그대로 + "HTTP_{status}" 코드
         └─ Exception (fallback)         → C999 (500, error 로그)
```

### 로깅 정책 (by 레벨)

| 예외 유형 | 로그 레벨 | 스택트레이스 | 이유 |
|----------|---------|-----------|------|
| BusinessException | WARN | debug 레벨에서만 출력 | 예측 가능한 도메인 예외. 스택 기록 시 디스크·검색 비용 급증 |
| ObjectOptimisticLockingEx | WARN | 포함 | 락 경합 원인 추적 필요 |
| MethodArgumentNotValidEx | WARN | 미포함 | 클라이언트 입력 오류 |
| PassportCodecException | WARN | 미포함 | 인증 정보 노출 방지 |
| Exception fallback | ERROR | 포함 | 예상 외 오류, 원인 분석 필수 |

---

## Trade-offs

### 1. RESERVATION_LOCK_CONFLICT를 409가 아닌 429로 매핑한 이유

**결정**: `R003` → `429 Too Many Requests`

**배경**: 동일 좌석에 대한 분산락 획득 실패는 두 가지 해석이 가능합니다.
- 상태 충돌(Conflict) 관점: 409
- 일시적 리소스 경합 관점: 429

**선택 근거**:
- 409는 "현재 상태에서 요청을 수행할 수 없다"는 **영구적 충돌** 뉘앙스가 강함 → 클라이언트가 재시도하지 않을 수 있음
- 429는 **재시도 가능한 일시 상태**임을 명시 → 클라이언트·API Gateway의 retry 정책 연계 용이
- 좌석 락 충돌은 락 홀드 시간(수백 ms) 이후 대부분 해소되는 **transient error**

**대안**:
- 409 유지 + `Retry-After` 헤더 부가 → 헤더 파싱이 필수가 되어 클라이언트 구현 부담 증가
- 503 Service Unavailable → 장애 상황 뉘앙스가 너무 강함

### 2. HTTP 상태와 Code의 1:N 관계 허용

- 하나의 HTTP 상태(예: 409)가 W001, E004, S001, R002, P002, P004, P006 등 **여러 code**에 매핑됨
- **장점**: HTTP 계층만 보는 로드밸런서·모니터링은 "409 비율" 기반으로 단순 관찰 가능
- **단점**: 클라이언트가 code 기반 분기 로직을 짜야 함 → 다만 구조상 i18n·UX 분기를 위해 어차피 code 레벨 핸들링이 필요하므로 수용

### 3. A001의 메시지가 컨텍스트별로 다른 이유

`A001`은 두 상황에서 사용됩니다.
- 로그인 실패 (이메일/비밀번호 불일치)
- Auth-Passport 헤더 decode 실패

**의도적 선택**: 두 경우 모두 **"인증 실패"** 라는 클라이언트 노출 메시지는 동일하지만, 내부 로그에는 구분된 컨텍스트를 남깁니다. 헤더 decode 실패를 별도 코드로 분리하면 공격자가 헤더 존재 여부·포맷을 역추적하는 단서가 될 수 있어 **보안상 통합**했습니다.

---

## Failure Scenarios

### 시나리오 1: 매진된 공연에 예매 시도

```
Client → POST /reservations
   → ReservationService 진입
   → Event 조회 (EventRepository)
   → 모든 좌석 sold-out 확인
   → throw new BusinessException(ErrorCode.EVENT_SOLD_OUT)
   → GlobalExceptionHandler.handleBusiness()
   → 409 { "code": "E004", "message": "모든 좌석이 매진되었습니다.", "status": 409 }
```

### 시나리오 2: 좌석 동시 예약 경합

```
Client A, B 동시에 → POST /reservations (seatId=100)
   → ReservationManager: Redisson tryLock(seatId=100)
   → A: 락 획득 성공 → 예약 생성 → 200
   → B: 락 획득 실패 (wait timeout) → BusinessException(RESERVATION_LOCK_CONFLICT)
   → 429 { "code": "R003", ..., "status": 429 }
   → 클라이언트 B: exponential backoff 후 재시도
```

### 시나리오 3: PG사 장애

```
Client → POST /payments/confirm
   → TossPaymentsClientImpl.confirm()
   → HTTP 5xx from PG
   → throw new BusinessException(ErrorCode.PAYMENT_PG_ERROR)
   → 500 { "code": "P005", ..., "status": 500 }
   → Saga 보상 트랜잭션 트리거 (payment-app → booking-app 예약 취소 이벤트)
```

### 시나리오 4: Auth-Passport 위조 시도

```
Client → GET /api/bookings (with tampered Auth-Passport)
   → downstream 서비스의 @ModelAttribute UserPassport
   → PassportCodec.decode() → Base64 디코드는 성공, JSON 파싱 실패
   → throw new PassportCodecException
   → GlobalExceptionHandler.handlePassportCodec()
   → 401 { "code": "A001", "message": "인증 컨텍스트가 유효하지 않습니다.", "status": 401 }
   → warn 로그 기록 (스택트레이스 제외, 헤더 원문 미노출)
```

---

## 신규 ErrorCode 추가 체크리스트

새 코드를 `ErrorCode.java`에 추가할 때 다음을 확인합니다.

- [ ] 도메인 접두사가 기존 체계(C/E/ES/W/S/R/P/A)에 속하는가? 속하지 않으면 새 접두사 정의 근거를 ADR에 기록
- [ ] 같은 의미의 코드가 이미 존재하지 않는가? (유사 코드 우선 재사용)
- [ ] HTTP 상태 매핑 근거가 명확한가? 특히 409 vs 429 선택 시 **재시도 가능성**으로 판단
- [ ] 메시지가 **민감 정보·내부 구현**을 노출하지 않는가? (쿼리·스택·DB 컬럼명 금지)
- [ ] `BusinessException`으로 던질 수 있는가? 아니면 별도 핸들러가 필요한가?
- [ ] 이 문서(`error-code.md`) 카탈로그 테이블에 행을 추가했는가?
- [ ] 테스트 코드에서 `code` 값으로 검증하고 있는가? (HTTP status만으로 검증 시 코드 중복 탐지 불가)

---

## 관련 문서

- [Architecture Overview](./why-msa.md) — 서비스 경계와 MSA 설계
- [ADR-0002: Internal Header Design](./adr/0002-internal-header-design.md) — 서비스 간 헤더 규약
- [ADR-0004: JWT Validation in SCG](./adr/0004-jwt-validation-in-scg.md) — Gateway 인증 흐름
- [ADR-0007: Header Naming and Auth-Passport](./adr/0007-header-naming-and-auth-passport.md) — A001 연동 맥락
- 구현체: `common-module/src/main/java/com/koesc/ci_cd_test_app/global/error/ErrorCode.java`
- 핸들러: `common-module/src/main/java/com/koesc/ci_cd_test_app/global/error/GlobalExceptionHandler.java`
