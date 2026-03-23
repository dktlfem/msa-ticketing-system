---
title: "API Specification: 시스템 전체 API 카탈로그 + payment-app 구현 기준"
last_updated: 2026-03-22
author: "민석"
reviewer: ""
---

## 목차
- [1. 공통 규약](#1-공통-규약)
- [2. 외부 API — waitingroom-app](#2-외부-api--waitingroom-app)
- [3. 외부 API — concert-app](#3-외부-api--concert-app)
- [4. 외부 API — booking-app](#4-외부-api--booking-app)
- [5. 외부 API — payment-app (심화)](#5-외부-api--payment-app-심화)
- [6. 외부 API — user-app](#6-외부-api--user-app)
- [7. 내부 API — waitingroom-app](#7-내부-api--waitingroom-app)
- [8. 내부 API — concert-app](#8-내부-api--concert-app)
- [9. 내부 API — booking-app](#9-내부-api--booking-app)
- [10. 대표 요청/응답 예시](#10-대표-요청응답-예시)
- [11. 핵심 설계 포인트](#11-핵심-설계-포인트)
- [SCG 라우팅](#scg-라우팅)
- [Trade-offs](#trade-offs)
- [Failure Scenarios](#failure-scenarios)
- [Observability](#observability)

# API Specification: 시스템 전체 API 카탈로그

> 이 문서는 현재 코드베이스(`scg-app`, `waitingroom-app`, `concert-app`, `booking-app`, `payment-app`, `user-app`)와
> 지금까지 정리된 운영 설계를 기준으로 작성한 **현행 API 계약 문서**입니다.
> payment-app 내부 구현 설계는 [`docs/services/payment/payment-architecture.md`](../services/payment/payment-architecture.md)를 참고합니다.

---

## 1. 공통 규약

### 1.1 외부 진입점

- 외부 API는 기본적으로 `scg-app`을 통해 `/api/v1/**` 경로로 진입합니다.
- `scg-app`은 `/internal/**` 경로를 외부에 노출하지 않으며, 내부 API는 서비스 간 호출 전용입니다.
- `booking-app`의 `/api/v1/users/me/reservations`는 `user-app`보다 먼저 라우팅되도록 게이트웨이 우선순위를 조정했습니다.

### 1.2 공통 헤더

| 헤더 | 설명 | 필수 | 비고                                                                                                                        |
| --- | --- | --- |---------------------------------------------------------------------------------------------------------------------------|
| Correlation-Id | 요청 상관관계 ID. 없으면 gateway가 UUID를 생성합니다. | 선택 | 로그/트레이스 검색 기준 (여러 서비스간의 연관된 흐름 전체)                                                                                        |
| X-Request-Id | 기존 요청 ID 헤더 (de-facto 표준, 유지). gateway가 `Correlation-Id`와 동기화합니다. | 선택 | 하위 호환                                                                                                                     |
| Auth-User-Id | 인증된 사용자 식별자. SCG `JwtAuthenticationFilter`가 JWT 검증 후 주입합니다. | SCG 주입 전용 | SCG가 주입하지만 **downstream 서비스는 더 이상 이 헤더를 직접 소비하지 않음** (Phase 2에서 Auth-Passport로 전환 완료). `RequestSanitizeFilter`가 외부 유입 헤더 strip. [ADR 0007](../architecture/adr/0007-header-naming-and-auth-passport.md) |
| Auth-Passport | 인증 컨텍스트 전체(userId, roles, jti, issuedAt, clientIp)를 Base64url(JSON)으로 인코딩한 단일 헤더. **booking-app, payment-app이 실제로 소비하는 인증 헤더.** | 예약/결제 API는 필수 | SCG `JwtAuthenticationFilter`가 주입. `RequestSanitizeFilter`가 외부 유입 헤더 strip. `PassportCodec.decode()`로 파싱. [ADR 0007](../architecture/adr/0007-header-naming-and-auth-passport.md) |
| X-User-Id | 구 사용자 식별 헤더. downstream 코드에서 완전히 제거됨. | 사용 안 함 | 2026-03-22 기준 `Auth-User-Id`로 일원화 완료 (ADR-0007 Phase 3). SCG에 bridge 필터 없음.                                                |
| Queue-Token | 대기열 통과 후 발급되는 ACTIVE token. | 예약 생성 시 필수 | booking → waitingroom internal validate/consume                                                                           |
| Idempotency-Key | 멱등 처리 키. | payment prepare/confirm/cancel는 필수 | Redis에 processing/response 캐시 저장                                                                                          |
| Internal-Caller | 서비스 간 내부 호출 식별자. | 내부 호출에서 권장 | SCG가 `scg-app` 값 주입                                                                                                       |

### 1.3 공통 에러 응답

**에러 응답 포맷은 호출 경로(SCG vs 마이크로서비스)에 따라 다릅니다.**

#### A. 마이크로서비스 에러 응답 (booking-app, payment-app 등)

`GlobalExceptionHandler`가 반환하는 `ErrorResponse` 포맷입니다.

```json
{
  "code": "P005",
  "message": "현재 상태에서는 결제를 승인할 수 없습니다.",
  "status": 409
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `code` | String | 비즈니스 에러 코드 (예: P005, R001, C001) |
| `message` | String | 사람이 읽을 수 있는 에러 메시지 |
| `status` | Integer | HTTP 상태 코드 숫자 |

#### B. SCG(scg-app) 에러 응답 — RFC 7807 ProblemDetail

`scg-app`의 `GlobalErrorHandler`는 Spring WebFlux 기본 에러 처리 위에서 동작하며, **RFC 7807 ProblemDetail** 포맷을 반환합니다. 마이크로서비스의 `ErrorResponse`와 구조가 다릅니다.

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "No route found for request path: /api/v1/unknown",
  "instance": "/api/v1/unknown"
}
```

| 필드 | 타입 | 설명 |
| --- | --- | --- |
| `type` | String (URI) | 에러 유형 URI. 기본값 `about:blank` |
| `title` | String | HTTP 상태 텍스트 (예: "Not Found", "Bad Gateway") |
| `status` | Integer | HTTP 상태 코드 숫자 |
| `detail` | String | 구체적 에러 설명 |
| `instance` | String | 에러가 발생한 요청 경로 |

**발생 케이스**: 라우팅 실패(404), 필터 예외(401/403), downstream 서비스 502/504, 요청 timeout. 마이크로서비스의 비즈니스 에러(4xx)는 SCG를 통과해 `ErrorResponse` 포맷 그대로 클라이언트에 전달됩니다.

**클라이언트 처리 가이드**: 응답에 `code` 필드가 있으면 마이크로서비스 에러(`ErrorResponse`), 없으면 SCG 에러(`ProblemDetail`)로 분기 처리합니다.

#### 에러 코드 분류

| 분류 | 대표 코드 | 설명 |
| --- | --- | --- |
| 공통 | C001, C002, C999 | 입력값 오류, 상태 충돌, 서버 오류 |
| 인증 | A001, A002 | 인증 헤더 누락 또는 형식 오류 |
| 예약 | R001, R002, R003 | 예약 없음, 소유권 위반, 만료 |
| 결제 | P001 ~ P011 | 결제 없음/금액 불일치/승인 불가/외부 PG 오류/동기화 실패 |
| 멱등성 | I001, I002, I003 | 멱등 키 오류, 처리중 충돌, payload mismatch |

### 1.4 페이징 응답

`PageResponse<T>` 구조를 사용합니다.

```json
{
  "content": [ ... ],
  "page": {
    "number": 0,
    "size": 20,
    "totalElements": 123,
    "totalPages": 7,
    "first": true,
    "last": false
  },
  "sort": [
    { "property": "reservedAt", "direction": "DESC" },
    { "property": "reservationId", "direction": "DESC" }
  ]
}
```

---

## 2. 외부 API — waitingroom-app

| Method | Path | 설명 | 인증/식별 | 필수 헤더 | Request | Response | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /api/v1/waiting-room/join | 대기열 진입 | Body userId (현재), JWT/OIDC 전환 예정 | Correlation-Id(optional) | WaitingRoomRequestDTO {eventId, userId} | WaitingRoomResponseDTO | Redis Sorted Set에 진입. 이미 등록된 사용자는 기존 순번 기준 응답. |
| GET | /api/v1/waiting-room/status | 대기열 상태 조회 / 토큰 발급 | Query userId (현재), JWT/OIDC 전환 예정 | Correlation-Id(optional) | Query: eventId, userId | WaitingRoomResponseDTO | 순번이 통과 구간이면 ACTIVE token 발급, 아니면 waiting 응답. |

---

## 3. 외부 API — concert-app

| Method | Path | 설명 | 인증/식별 | 필수 헤더 | Request | Response | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /api/v1/events | 공연 신규 등록 | 없음(현재 staging), 관리자 권한화 권장 | Correlation-Id(optional) | EventRequestDTO {title, description, posterUrl} | EventResponseDTO | 운영 전환 시 관리자 전용 API로 분리 권장. |
| GET | /api/v1/events/{eventId} | 공연 상세 조회 | 없음 | Correlation-Id(optional) | Path: eventId | EventResponseDTO | read-heavy 데이터. Caffeine L1 캐시 대상. |
| GET | /api/v1/events | 전체 공연 목록 조회 | 없음 | Correlation-Id(optional) | 없음 | List\<EventResponseDTO\> | 공연 카탈로그 조회용. |
| GET | /api/v1/events/{eventId}/schedules | 공연 회차 목록 조회 | 없음 | Correlation-Id(optional) | Path: eventId, Query: page/size/sort | PageResponse\<EventScheduleResponseDTO\> | 정렬 기본값: startTime ASC, scheduleId ASC. |
| GET | /api/v1/schedules/{scheduleId} | 회차 상세 조회 | 없음 | Correlation-Id(optional) | Path: scheduleId | EventScheduleDetailResponseDTO | bookable/bookableCode/bookableMessage를 서버 계산으로 제공. |
| GET | /api/v1/seats/available/{scheduleId} | 예약 가능 좌석 조회 | 없음 | Correlation-Id(optional) | Path: scheduleId | List\<SeatResponseDTO\> | AVAILABLE 좌석만 반환. |
| POST | /api/v1/seats/hold | 좌석 임시 점유 | 없음(현재), booking-app 내부 전용화 권장 | Correlation-Id(optional) | SeatRequestDTO {seatId} | SeatResponseDTO | 낙관적 락 사용. 실제 운영에서는 booking 흐름 안으로 제한 권장. |

---

## 4. 외부 API — booking-app

<!-- 2026-03-18 API 정합성 통일 -->
> **[SCG 라우팅 현황]** booking-app(`/api/v1/reservations/**`)은 현재 SCG 라우팅 테이블에서 **의도적으로 제외**되어 있습니다.
>
> **제외 사유**: 예약 진입은 반드시 대기열 토큰(`Queue-Token`) 검증을 거쳐야 합니다. 단순 `Path=/api/v1/reservations/**` predicate 라우팅을 적용하면 토큰 검증 없이 직접 예약이 가능해지므로, 토큰 검증 게이트웨이 필터를 포함한 별도 라우팅 설계가 필요합니다 **(planned)**.
>
> **예외**: `GET /api/v1/users/me/reservations`는 user-app 경로와의 충돌을 피하기 위해 SCG에서 user-app보다 높은 우선순위로 라우팅됩니다.

| Method | Path | 설명 | 인증/식별 | 필수 헤더 | Request | Response | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /api/v1/reservations | 예약 생성 | Auth-Passport → PassportCodec.decode() | Auth-Passport(required), Queue-Token(required) | ReservationCreateRequestDTO {seatId} | ReservationResponseDTO | waitingroom token 검증 + seat HOLD + reservation 저장 + token consume. |
| GET | /api/v1/reservations/{reservationId} | 예약 상세 조회 | Auth-Passport → PassportCodec.decode() | Auth-Passport(required) | Path: reservationId | ReservationResponseDTO | 본인 예약만 조회 가능. |
| GET | /api/v1/users/me/reservations | 내 예약 목록 조회 | Auth-Passport → PassportCodec.decode() | Auth-Passport(required) | Query: status?, page?, size?, sort? | PageResponse\<ReservationSummaryResponseDTO\> | SCG에서 user-app보다 booking-app route가 먼저 평가되어야 하는 경로. |
| DELETE | /api/v1/reservations/{reservationId} | 예약 취소 | Auth-Passport → PassportCodec.decode() | Auth-Passport(required) | Path: reservationId | ReservationStatusResponseDTO | PENDING 상태만 취소 가능. 취소 시 seat RELEASE. |

---

## 5. 외부 API — payment-app

TossPayments의 결제 흐름은 클라이언트가 PG SDK를 직접 호출하는 2단계 구조입니다.

```
서버 /request  →  클라이언트가 TossPayments SDK 호출  →  서버 /confirm
```

이 구조에서 서버는 두 가지 역할을 합니다:
1. `/request`: 주문번호(orderId)와 금액을 서버가 생성 → 클라이언트가 이 값으로 PG SDK 호출
2. `/confirm`: 클라이언트에서 받은 paymentKey로 TossPayments confirmPayment API 호출

---

### 5.1 Problem

결제 API 설계에서 반드시 해결해야 하는 문제:

1. **금액 위변조**: 클라이언트가 amount를 바꿔서 전송하면 이중 검증이 필요
2. **중복 결제**: 같은 예약에 대해 네트워크 오류로 confirm이 2번 호출될 수 있음
3. **결제 후 예약 확정 실패**: 돈은 나갔는데 예약이 안 되는 상황
4. **PG 타임아웃**: TossPayments confirm이 응답 없이 끊기면 결제 여부를 알 수 없음

---

### 5.2 Design

#### 공통 헤더 (payment-app 적용 기준)

<!-- 2026-03-18 API 정합성 통일 -->
| 헤더 | 필수 여부 | 설명 |
|------|----------|------|
| `Auth-Passport` | 결제 요청 시 필수 | SCG가 주입하는 인증 컨텍스트 단일 헤더. `PassportCodec.decode()`로 userId 추출. (ADR-0007 Phase 2 완료; legacy `X-User-Id`, `X-Auth-User-Id` 모두 제거됨) |
| `Idempotency-Key` | request, confirm 시 필수 | 클라이언트가 생성하는 고유 키 (UUID 권장) |
| `Content-Type` | POST 시 필수 | `application/json` |

**Idempotency-Key 처리 흐름:**
```
1. 요청 수신 → Redis GET payment:idempotency:{key}
2. 값 = "PROCESSING" → 409 Conflict (다른 스레드가 처리 중)
3. 값 = {responseJson} → 200 OK (캐시된 응답 반환)
4. 값 없음 → Redis SETNX PROCESSING (원자적 점유) → 처리
5. 완료 후 → Redis SET {key} = responseJson (TTL 24h)
6. 실패 시 → Redis DEL {key} (재시도 가능 상태로 복원)
```

#### 에러 응답 형식

구현 클래스: `GlobalExceptionHandler.java`

<!-- 2026-03-18 API 정합성 통일 -->
> **[통일 기준]** 에러 응답 포맷은 이 문서의 1.3항 공통 에러 응답 형식과 동일합니다.
> 기존 `timestamp` 필드는 `status`(HTTP 상태코드 숫자)로 대체됩니다.

```json
{
  "code": "P003",
  "message": "결제 금액 불일치. 저장된 금액: 150000, 요청 금액: 120000",
  "status": 400
}
```

#### 에러 코드 전체 목록 (payment-app 기준)

`common-module/src/main/java/com/koesc/ci_cd_test_app/global/error/ErrorCode.java` 기준:

| 코드 | HTTP | 메시지 | 발생 조건 |
|------|------|--------|----------|
| `R001` | 404 | 존재하지 않는 예약입니다 | booking-app에서 reservation 찾을 수 없을 때 |
| `R002` | 409 | 결제 대기 중이 아니거나 만료된 예약입니다 | reservation status ≠ PENDING 또는 만료 |
| `P001` | 404 | 존재하지 않는 결제입니다 | payment 찾을 수 없을 때 |
| `P002` | 409 | 이미 결제가 존재하는 예약입니다 | reservation_id UK 중복 |
| `P003` | 400 | 결제 금액이 일치하지 않습니다 | confirm 요청의 amount ≠ 저장된 amount |
| `P004` | 409 | 현재 상태에서 허용되지 않는 결제 요청입니다 | 잘못된 상태 전이 (예: FAILED 상태에서 confirm) |
| `P005` | 500 | 결제 처리 중 오류가 발생했습니다 | TossPayments API 오류 |
| `P006` | 409 | 동일한 요청이 처리 중입니다 | Idempotency-Key 중복 처리 중 |
| `C001` | 400 | 적절하지 않은 입력 값입니다 | @Valid 검증 실패 |
| `C999` | 500 | Internal Server Error | 처리되지 않은 예외 |

---

### 5.3 Endpoints

#### POST /api/v1/payments/request

결제 레코드를 생성합니다. 응답의 `orderId`와 `amount`를 클라이언트가 TossPayments SDK에 전달합니다.

**요청**

<!-- 2026-03-18 API 정합성 통일 -->
```http
POST /api/v1/payments/request
Auth-Passport: eyJ1c2VySWQiOiIxMDAiLCJyb2xlcyI6WyJVU0VSIl19
Idempotency-Key: pay-req-90001-uuid-v1
Content-Type: application/json

{
  "reservationId": 90001
}
```

| 필드 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `reservationId` | Long | NotNull, Positive | 결제할 예약 ID |

**응답 (201 Created → 현재 구현은 200)**

```json
{
  "paymentId": 50001,
  "reservationId": 90001,
  "orderId": "RES90001_1710567600123",
  "paymentKey": null,
  "amount": 150000,
  "status": "READY",
  "method": null,
  "approvedAt": null,
  "cancelledAt": null
}
```

**내부 처리 순서:**
1. booking-app `GET /internal/v1/reservations/{reservationId}` → status=PENDING, userId 검증
2. `existsByReservationId` → 중복 결제 방지
3. concert-app `GET /internal/v1/seats/{seatId}` → 좌석 가격 조회
4. `orderId = "RES{reservationId}_{epochMilli}"` 생성
5. PaymentEntity 저장 (status=READY)

**orderId 규격:** TossPayments 요구사항에 맞춤 (영문, 숫자, `-`, `_` / 최대 64자)

---

#### POST /api/v1/payments/confirm

TossPayments SDK에서 받은 paymentKey로 PG 승인을 요청합니다. 성공 시 booking-app에 예약 확정을 호출합니다.

**요청**

```http
POST /api/v1/payments/confirm
Idempotency-Key: pay-confirm-50001-uuid-v1
Content-Type: application/json

{
  "paymentKey": "pay_8xvRL2D3mA9W7mYzKqP0n",
  "orderId": "RES90001_1710567600123",
  "amount": 150000
}
```

| 필드 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `paymentKey` | String | NotBlank | TossPayments SDK에서 발급된 결제 키 |
| `orderId` | String | NotBlank | /request에서 받은 주문 ID |
| `amount` | BigDecimal | NotNull, Positive | /request 응답의 amount와 동일해야 함 |

**응답 (200 OK)**

```json
{
  "paymentId": 50001,
  "reservationId": 90001,
  "orderId": "RES90001_1710567600123",
  "paymentKey": "pay_8xvRL2D3mA9W7mYzKqP0n",
  "amount": 150000,
  "status": "APPROVED",
  "method": "카드",
  "approvedAt": "2026-03-16T15:30:05",
  "cancelledAt": null
}
```

**내부 처리 순서 (트랜잭션 경계 명시):**

```
[readOnly TX] readByOrderId(orderId) → 금액 검증
[외부 호출]   TossPayments confirmPayment(paymentKey, orderId, amount)
    → 실패 시: [TX] updateToFailed(paymentId, reason)
[TX]          updateToApproved(paymentId, paymentKey, method, approvedAt, pgResponseRaw)
[외부 호출]   booking-app POST /internal/v1/reservations/{id}/confirm
    → 실패 시: [보상] TossPayments cancelPayment → [TX] updateToRefunded 또는 updateToCancelFailed
```

---

#### POST /api/v1/payments/{paymentKey}/cancel

APPROVED 상태의 결제를 취소합니다.

**요청**

```http
POST /api/v1/payments/pay_8xvRL2D3mA9W7mYzKqP0n/cancel
Content-Type: application/json

{
  "cancelReason": "사용자 요청 취소"
}
```

| 필드 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `cancelReason` | String | NotBlank, max 200 | 취소 사유 (TossPayments에 전달됨) |

**응답 (200 OK)**

```json
{
  "paymentId": 50001,
  "reservationId": 90001,
  "orderId": "RES90001_1710567600123",
  "paymentKey": "pay_8xvRL2D3mA9W7mYzKqP0n",
  "amount": 150000,
  "status": "REFUNDED",
  "method": "카드",
  "approvedAt": "2026-03-16T15:30:05",
  "cancelledAt": "2026-03-16T16:00:00"
}
```

**취소 실패 시:** 상태가 `CANCEL_FAILED`로 전이되고 `[CRITICAL]` 로그가 발생합니다. 수동 처리가 필요합니다.

---

#### GET /api/v1/payments/{paymentId}

결제 상태를 조회합니다.

**요청**

<!-- 2026-03-18 API 정합성 통일 -->
```http
GET /api/v1/payments/50001
Auth-Passport: eyJ1c2VySWQiOiIxMDAiLCJyb2xlcyI6WyJVU0VSIl19
```

**응답:** 위 PaymentResponseDTO와 동일한 구조.

---

### 5.4 Internal API (booking-app → payment-app)

payment-app이 제공하는 internal API는 현재 없습니다.
booking-app 취소 시 payment 상태를 확인하는 API가 향후 필요할 수 있습니다 **(planned)**.

---

## SCG 라우팅

`scg-app/src/main/resources/application.properties`:

```properties
spring.cloud.gateway.routes[3].id=payment-service
spring.cloud.gateway.routes[3].uri=http://payment-app:8080
spring.cloud.gateway.routes[3].predicates[0]=Path=/api/v1/payments/**
```

`/internal/v1/**`는 SCG 라우팅에 포함되지 않습니다. 내부 서비스 간 직접 호출만 허용됩니다.

---

## Trade-offs

| 결정 | 이유 | 트레이드오프 |
|------|------|------------|
| `/request`와 `/confirm` 2단계 분리 | 서버가 orderId와 amount를 생성 → 금액 위변조 방지 | 클라이언트가 2번 서버를 호출해야 함 |
| Idempotency-Key를 클라이언트가 생성 | 클라이언트가 재시도 의도를 명시적으로 제어 | 키 관리를 클라이언트에 의존 |
| paymentKey를 경로 파라미터로 사용 (`/cancel`) | TossPayments의 cancelPayment API가 paymentKey 기반 | paymentId가 아닌 paymentKey로 조회하는 경로가 생김 |
| amount를 confirm 요청에 포함 | TossPayments의 confirm API 규격이 amount 포함을 요구 | 서버에서 저장값과 일치 검증 필수 |

---

## Failure Scenarios

### PG 타임아웃 (현재 미처리, planned)
- TossPayments confirm이 read timeout (10s) 내 응답 없으면 RuntimeException 발생
- Payment는 READY 상태로 유지
- 해결책: TossPayments 웹훅 수신 엔드포인트 (`POST /api/v1/payments/toss-webhook`) 구현 또는 cron 기반 폴링

### amount 불일치
- 클라이언트가 다른 amount를 전송 → `P003` 에러, TossPayments 호출 없이 종료
- TossPayments도 서버가 orderId 생성 시 지정한 amount와 다르면 거부

---

## Observability

```
로그 예시:
2026-03-16 15:30:00.123 [abc123,def456] [http-nio-8080-exec-1] INFO  PaymentManager - Payment created - paymentId=50001, reservationId=90001, orderId=RES90001_1710567600123, amount=150000
2026-03-16 15:30:05.456 [abc123,def456] [http-nio-8080-exec-2] INFO  PaymentManager - Payment approved - paymentId=50001, orderId=RES90001_1710567600123
2026-03-16 15:30:05.789 [abc123,def456] [http-nio-8080-exec-2] INFO  PaymentManager - Reservation confirmed - reservationId=90001, paymentId=50001
```

traceId는 `micrometer-tracing-bridge-brave`가 자동으로 MDC에 주입합니다.
`logging.pattern.console`에 `%X{traceId},%X{spanId}` 패턴이 설정되어 있습니다.

---

## 6. 외부 API — user-app

> **현재 구현 상태**: user-app은 회원 가입 및 조회 2개 비즈니스 엔드포인트와 테스트 엔드포인트 1개로 구성된다. **로그인(JWT 발급) API는 미구현** 상태다 (`security-design.md` R7, Planned Improvements P1 참조).

| Method | Path | 설명 | 인증/식별 | 필수 헤더 | Request | Response | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /api/v1/users/signup | 회원 가입 | 없음 | Correlation-Id(optional) | UserRequestDTO | UserResponseDTO | 이메일 중복 검증(DB UK) + 이름 금칙어 검증. **비밀번호 평문 저장 (BCrypt 미적용, planned)** |
| GET | /api/v1/users/{userId} | 회원 정보 조회 | 없음(현재), 본인/관리자 제한 권장 | Correlation-Id(optional) | Path: userId | UserResponseDTO | staging 데모용 오픈 상태. production 전환 시 권한 분리 필요. |
| GET | /api/v1/users/ai-test | AI 서킷 브레이커 테스트 | 없음 | Correlation-Id(optional) | 없음 | String | Resilience4j 서킷 브레이커 지표 생성용. AIModelClient fallback 호출. |

### 6.1 DTO 상세

**UserRequestDTO** (회원 가입 요청)

| 필드 | 타입 | 제약 | 설명 |
|------|------|------|------|
| `email` | String | `@NotBlank`, `@Email` | 이메일 주소. UK 제약으로 중복 불가. |
| `name` | String | `@NotBlank` | 사용자 이름. `admin`, `관리자` 포함 불가. |
| `password` | String | `@NotBlank` | 비밀번호. **현재 평문 저장** (BCrypt 적용 예정). |

**UserResponseDTO** (응답)

| 필드 | 타입 | 설명 |
|------|------|------|
| `id` | Long | 사용자 ID (PK) |
| `email` | String | 이메일 주소 |
| `name` | String | 사용자 이름 |
| `point` | BigDecimal | 포인트 잔액 (초기값 0) |

### 6.2 에러 응답 (user-app 기준)

| 코드 | HTTP | 발생 조건 |
|------|------|----------|
| `C001` | 400 | `@Valid` 검증 실패 (이메일 형식 불일치, 이름 공백 등) |
| `C001` | 400 | 이름 금칙어 포함 (`admin`, `관리자`) |
| `C001` | 400 | 이메일 중복 (`이미 가입된 이메일입니다`) |
| `C999` | 500 | 처리되지 않은 예외 |

> **로그인 API 없음**: 현재 `POST /api/v1/users/login`이 없다. JWT 기반 인증 흐름을 완성하려면 login 엔드포인트 구현이 필요하다. 구현 전까지 `Auth-Passport` 헤더는 SCG의 JWT 검증 없이 통과된다.

---

## 7. 내부 API — waitingroom-app

| Method | Path | 설명 | 호출 주체 | 헤더 | Request | Response | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /internal/v1/waiting-room/tokens/validate | 활성 토큰 검증 | 서비스 간 내부 호출 | Internal-Caller, Correlation-Id | ValidateTokenRequest {tokenId, userId, eventId} | ValidateTokenResponse | 410 EXPIRED, 409 ALREADY_USED, 422 INVALID 반환. |
| POST | /internal/v1/waiting-room/tokens/{tokenId}/consume | 활성 토큰 소모 | 서비스 간 내부 호출 | Internal-Caller, Correlation-Id | ConsumeTokenRequest {usedBy} | ConsumeTokenResponse | booking-app에서 reservation 저장 후 호출. |

---

## 8. 내부 API — concert-app

| Method | Path | 설명 | 호출 주체 | 헤더 | Request | Response | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | /internal/v1/seats/{seatId} | 좌석 상세 조회 | 서비스 간 내부 호출 | Internal-Caller, Correlation-Id | Path: seatId | SeatDetailResponse | seatId, scheduleId, eventId, seatNo, price, status, version 반환. |
| POST | /internal/v1/seats/{seatId}/hold | 좌석 HOLD | 서비스 간 내부 호출 | Internal-Caller, Correlation-Id | SeatCommandRequest {expectedStatus} | SeatCommandResponse | AVAILABLE → HOLD. optimistic lock 충돌 시 409. |
| POST | /internal/v1/seats/{seatId}/release | 좌석 RELEASE | 서비스 간 내부 호출 | Internal-Caller, Correlation-Id | SeatCommandRequest {expectedStatus} | SeatCommandResponse | HOLD → AVAILABLE. |
| POST | /internal/v1/seats/{seatId}/confirm | 좌석 CONFIRM | 서비스 간 내부 호출 | Internal-Caller, Correlation-Id | SeatCommandRequest {expectedStatus} | SeatCommandResponse | HOLD → SOLD. |

---

## 9. 내부 API — booking-app

| Method | Path | 설명 | 호출 주체 | 헤더 | Request | Response | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | /internal/v1/reservations/{reservationId} | 예약 상세 조회 | 서비스 간 내부 호출 | Internal-Caller, Correlation-Id | Path: reservationId | ReservationResponseDTO | payment-app에서 reservation 검증용. |
| POST | /internal/v1/reservations/{reservationId}/confirm | 예약 확정 | 서비스 간 내부 호출 | Internal-Caller, Correlation-Id | ReservationConfirmRequestDTO {paymentId} | ReservationStatusResponseDTO | payment DONE 후 호출. seat CONFIRM 연계. |
| POST | /internal/v1/reservations/{reservationId}/cancel-confirmed | 결제 취소에 따른 예약 취소 | 서비스 간 내부 호출 | Internal-Caller, Correlation-Id | Path: reservationId | ReservationStatusResponseDTO | payment CANCELLED 후 호출. seat RELEASE 연계. |
| POST | /internal/v1/reservations/{reservationId}/expire | 예약 만료 처리 | 서비스 간 내부 호출 | Internal-Caller, Correlation-Id | Path: reservationId | ReservationStatusResponseDTO | PENDING && expired 대상. seat RELEASE. |

---

## 10. 대표 요청/응답 예시

### 10.1 대기열 진입

```http
POST /api/v1/waiting-room/join
Content-Type: application/json

{
  "eventId": 1,
  "userId": 100
}
```

```json
{
  "rank": 152,
  "estimatedSeconds": 45,
  "allowed": false,
  "tokenId": null,
  "expiredAt": null
}
```

### 10.2 대기열 상태 조회 후 토큰 발급

```http
GET /api/v1/waiting-room/status?eventId=1&userId=100
```

```json
{
  "rank": 0,
  "estimatedSeconds": 0,
  "allowed": true,
  "tokenId": "550e8400-e29b-41d4-a716-446655440000",
  "expiredAt": "2026-01-08T15:00:00"
}
```

### 10.3 예약 생성

```http
POST /api/v1/reservations
Auth-Passport: eyJ1c2VySWQiOiIxMDAiLCJyb2xlcyI6WyJVU0VSIl19
Queue-Token: 550e8400-e29b-41d4-a716-446655440000
Content-Type: application/json

{
  "seatId": 10001
}
```

```json
{
  "reservationId": 90001,
  "userId": 100,
  "seatId": 10001,
  "status": "PENDING",
  "reservedAt": "2026-03-14T10:00:00",
  "expiredAt": "2026-03-14T10:05:00"
}
```

### 10.4 결제 준비

```http
POST /api/v1/payments/prepare
Auth-Passport: eyJ1c2VySWQiOiIxMDAiLCJyb2xlcyI6WyJVU0VSIl19
Idempotency-Key: pay-prepare-90001-v1
Content-Type: application/json

{
  "reservationId": 90001,
  "orderName": "콜드플레이 내한공연 VIP 1석"
}
```

```json
{
  "paymentId": 50001,
  "reservationId": 90001,
  "orderId": "ord_90001_7c7b0f9f7f9249ab",
  "orderName": "콜드플레이 내한공연 VIP 1석",
  "amount": 150000,
  "currency": "KRW",
  "status": "READY",
  "orderExpiresAt": "2026-03-14T10:05:00"
}
```

### 10.5 결제 승인

```http
POST /api/v1/payments/confirm
Auth-Passport: eyJ1c2VySWQiOiIxMDAiLCJyb2xlcyI6WyJVU0VSIl19
Idempotency-Key: pay-confirm-50001-v1
Content-Type: application/json

{
  "reservationId": 90001,
  "paymentKey": "pay_8xvRL2D3mA9W7mYzKqP0n",
  "orderId": "ord_90001_7c7b0f9f7f9249ab",
  "amount": 150000
}
```

```json
{
  "paymentId": 50001,
  "reservationId": 90001,
  "orderId": "ord_90001_7c7b0f9f7f9249ab",
  "paymentKey": "pay_8xvRL2D3mA9W7mYzKqP0n",
  "status": "DONE",
  "method": "CARD",
  "amount": 150000,
  "approvedAt": "2026-03-14T10:01:05"
}
```

### 10.6 결제 취소

```http
POST /api/v1/payments/50001/cancel
Auth-Passport: eyJ1c2VySWQiOiIxMDAiLCJyb2xlcyI6WyJVU0VSIl19
Idempotency-Key: pay-cancel-50001-v1
Content-Type: application/json

{
  "cancelReason": "사용자 요청 취소"
}
```

---

## 11. 핵심 설계 포인트

- 결제는 항상 `prepare → confirm` 2단계로 분리하여, **주문번호와 금액의 source of truth를 서버가 가진다**는 원칙을 유지합니다.
- 예약 생성은 `waiting token 검증 → seat HOLD → reservation 저장 → token consume` 순서로 처리하며, 중간 실패 시 좌석 RELEASE 보상을 수행합니다.
- 결제 승인 이후 booking 확정에 실패하면 **자동 환불 보상 트랜잭션**을 시도합니다. 보상 설계 상세는 [`docs/services/payment/payment-architecture.md`](../services/payment/payment-architecture.md)를 참조합니다.
- `Idempotency-Key`는 Redis에 `processing` / `response` 키로 나누어 저장하고, request hash 불일치를 별도로 차단합니다.
- 내부 API는 게이트웨이 바깥에서만 사용되며, 외부에 `/internal/**`가 보이지 않도록 차단합니다.

---

*최종 업데이트: 2026-03-22 | ADR-0007 Phase 3 완료 반영 (Auth-Passport 소비 전환)*
