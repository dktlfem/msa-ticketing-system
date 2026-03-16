# 01. API 명세서 및 엔드포인트 카탈로그

이 문서는 현재 코드베이스(`scg-app`, `waitingroom-app`, `concert-app`, `booking-app`, `payment-app`, `user-app`)와 지금까지 정리된 운영 설계를 기준으로 작성한 **현행 API 계약 문서**입니다.

## 1. 공통 규약

### 1.1 외부 진입점

- 외부 API는 기본적으로 `scg-app`을 통해 `/api/v1/**` 경로로 진입합니다.
- `scg-app`은 `/internal/**` 경로를 외부에 노출하지 않으며, 내부 API는 서비스 간 호출 전용입니다.
- `booking-app`의 `/api/v1/users/me/reservations`는 `user-app`보다 먼저 라우팅되도록 게이트웨이 우선순위를 조정했습니다.

### 1.2 공통 헤더

| 헤더 | 설명 | 필수 | 비고 |
| --- | --- | --- | --- |
| X-Correlation-Id | 요청 상관관계 ID. 없으면 gateway가 UUID를 생성합니다. | 선택 | 로그/트레이스 검색 기준 |
| X-Request-Id | 기존 요청 ID 헤더. gateway가 `X-Correlation-Id`와 동기화합니다. | 선택 | 하위 호환 |
| X-Auth-User-Id | 인증된 사용자 식별자. 현재 booking/payment에서 사용합니다. | 예약/결제 API는 필수 | legacy `X-User-Id`를 bridge 가능 |
| X-User-Id | 기존 사용자 식별 헤더. | 선택 | SCG가 `X-Auth-User-Id`로 bridge |
| X-Waiting-Token | 대기열 통과 후 발급되는 ACTIVE token. | 예약 생성 시 필수 | booking -> waitingroom internal validate/consume |
| Idempotency-Key | 멱등 처리 키. | payment prepare/confirm/cancel는 필수 | Redis에 processing/response 캐시 저장 |
| X-Internal-Caller | 게이트웨이 또는 서비스 간 내부 호출 식별자. | 내부 호출에서 권장 | SCG가 `scg-app` 값 주입 |

### 1.3 공통 에러 응답

```json
{
  "code": "P005",
  "message": "현재 상태에서는 결제를 승인할 수 없습니다.",
  "status": 409
}
```

대표 에러 코드 묶음:

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

## 2. 외부 API 엔드포인트

### 2.1 waitingroom-app

| Method | Path | 설명 | 인증/식별 | 필수 헤더 | Request | Response | 멱등성 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /api/v1/waiting-room/join | 대기열 진입 | Body userId (현재), JWT/OIDC 전환 예정 | X-Correlation-Id(optional) | WaitingRoomRequestDTO {eventId, userId} | WaitingRoomResponseDTO | N/A | Redis Sorted Set에 진입. 이미 등록된 사용자는 기존 순번 기준 응답. |
| GET | /api/v1/waiting-room/status | 대기열 상태 조회 / 토큰 발급 | Query userId (현재), JWT/OIDC 전환 예정 | X-Correlation-Id(optional) | Query: eventId, userId | WaitingRoomResponseDTO | N/A | 순번이 통과 구간이면 ACTIVE token 발급, 아니면 waiting 응답. |

### 2.2 concert-app

| Method | Path | 설명 | 인증/식별 | 필수 헤더 | Request | Response | 멱등성 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /api/v1/events | 공연 신규 등록 | 없음(현재 staging), 관리자 권한화 권장 | X-Correlation-Id(optional) | EventRequestDTO {title, description, posterUrl} | EventResponseDTO | N/A | 운영 전환 시 관리자 전용 API로 분리 권장. |
| GET | /api/v1/events/{eventId} | 공연 상세 조회 | 없음 | X-Correlation-Id(optional) | Path: eventId | EventResponseDTO | N/A | read-heavy 데이터. Caffeine L1 캐시 대상. |
| GET | /api/v1/events | 전체 공연 목록 조회 | 없음 | X-Correlation-Id(optional) | 없음 | List<EventResponseDTO> | N/A | 공연 카탈로그 조회용. |
| GET | /api/v1/events/{eventId}/schedules | 공연 회차 목록 조회 | 없음 | X-Correlation-Id(optional) | Path: eventId, Query: page/size/sort | PageResponse<EventScheduleResponseDTO> | N/A | 정렬 기본값: startTime ASC, scheduleId ASC. |
| GET | /api/v1/schedules/{scheduleId} | 회차 상세 조회 | 없음 | X-Correlation-Id(optional) | Path: scheduleId | EventScheduleDetailResponseDTO | N/A | bookable/bookableCode/bookableMessage를 서버 계산으로 제공. |
| GET | /api/v1/seats/available/{scheduleId} | 예약 가능 좌석 조회 | 없음 | X-Correlation-Id(optional) | Path: scheduleId | List<SeatResponseDTO> | N/A | AVAILABLE 좌석만 반환. |
| POST | /api/v1/seats/hold | 좌석 임시 점유 | 없음(현재), booking-app 내부 전용화 권장 | X-Correlation-Id(optional) | SeatRequestDTO {seatId} | SeatResponseDTO | N/A | 낙관적 락 사용. 실제 운영에서는 외부 직접 호출보다 booking 흐름 안으로 제한 권장. |

### 2.3 booking-app

| Method | Path | 설명 | 인증/식별 | 필수 헤더 | Request | Response | 멱등성 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /api/v1/reservations | 예약 생성 | @AuthUserId -> X-Auth-User-Id | X-Auth-User-Id(required), X-Waiting-Token(required), X-Correlation-Id(optional) | ReservationCreateRequestDTO {seatId} | ReservationResponseDTO | 권장(향후) | waitingroom token 검증 + seat HOLD + reservation 저장 + token consume. |
| GET | /api/v1/reservations/{reservationId} | 예약 상세 조회 | @AuthUserId -> X-Auth-User-Id | X-Auth-User-Id(required), X-Correlation-Id(optional) | Path: reservationId | ReservationResponseDTO | N/A | 본인 예약만 조회 가능. |
| GET | /api/v1/users/me/reservations | 내 예약 목록 조회 | @AuthUserId -> X-Auth-User-Id | X-Auth-User-Id(required), X-Correlation-Id(optional) | Query: status?, page?, size?, sort? | PageResponse<ReservationSummaryResponseDTO> | N/A | SCG에서 user-app보다 booking-app route가 먼저 평가되어야 하는 경로. |
| DELETE | /api/v1/reservations/{reservationId} | 예약 취소 | @AuthUserId -> X-Auth-User-Id | X-Auth-User-Id(required), X-Correlation-Id(optional) | Path: reservationId | ReservationStatusResponseDTO | 권장(향후) | PENDING 상태만 취소 가능. 취소 시 seat RELEASE. |

### 2.4 payment-app

| Method | Path | 설명 | 인증/식별 | 필수 헤더 | Request | Response | 멱등성 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /api/v1/payments/prepare | 결제 준비 | @AuthUserId -> X-Auth-User-Id | X-Auth-User-Id(required), Idempotency-Key(required), X-Correlation-Id(optional) | PaymentPrepareRequestDTO {reservationId, orderName?} | PaymentPrepareResponseDTO | 필수 | reservation/seat 상태를 확인하고 server-side orderId, amount를 생성. |
| POST | /api/v1/payments/confirm | 결제 승인 | @AuthUserId -> X-Auth-User-Id | X-Auth-User-Id(required), Idempotency-Key(required), X-Correlation-Id(optional) | PaymentConfirmRequestDTO {reservationId, paymentKey, orderId, amount} | PaymentResponseDTO | 필수 | 토스 승인 -> payment DONE -> booking confirm. 실패 시 자동 환불 보상 가능. |
| POST | /api/v1/payments/{paymentId}/cancel | 결제 취소 | @AuthUserId -> X-Auth-User-Id | X-Auth-User-Id(required), Idempotency-Key(required), X-Correlation-Id(optional) | Path: paymentId, Body: PaymentCancelRequestDTO {cancelReason} | PaymentResponseDTO | 필수 | 전액 취소 중심. 취소 후 booking cancel-confirmed 호출. |
| GET | /api/v1/payments/{paymentId} | 결제 상세 조회 | @AuthUserId -> X-Auth-User-Id | X-Auth-User-Id(required), X-Correlation-Id(optional) | Path: paymentId | PaymentResponseDTO | N/A | 본인 결제만 조회 가능. |
| GET | /api/v1/payments/orders/{orderId} | 주문번호 기준 결제 조회 | @AuthUserId -> X-Auth-User-Id | X-Auth-User-Id(required), X-Correlation-Id(optional) | Path: orderId | PaymentResponseDTO | N/A | orderId 기반 상태 확인용. |

### 2.5 user-app

| Method | Path | 설명 | 인증/식별 | 필수 헤더 | Request | Response | 멱등성 | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /api/v1/users/signup | 회원 가입 | 없음 | X-Correlation-Id(optional) | UserRequestDTO {email, name, password} | UserResponseDTO | 권장(향후) | 현재 공개. 중복 이메일 검증. |
| GET | /api/v1/users/{userId} | 회원 정보 조회 | 없음(현재), 본인/관리자 제한 권장 | X-Correlation-Id(optional) | Path: userId | UserResponseDTO | N/A | staging 데모용 오픈 상태. production 전환 시 권한 분리 필요. |
| GET | /api/v1/users/ai-test | AI 서킷 브레이커 테스트 | 없음 | X-Correlation-Id(optional) | 없음 | String | N/A | 회복탄력성 지표 생성용 테스트 엔드포인트. |

## 3. 내부 API 엔드포인트

### 3.1 waitingroom-app

| Method | Path | 설명 | 호출 주체 | 헤더 | Request | Response | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | /internal/v1/waiting-room/tokens/validate | 활성 토큰 검증 | 서비스 간 내부 호출 | X-Internal-Caller, X-Correlation-Id | ValidateTokenRequest {tokenId, userId, eventId} | ValidateTokenResponse | 410 EXPIRED, 409 ALREADY_USED, 422 INVALID 반환. |
| POST | /internal/v1/waiting-room/tokens/{tokenId}/consume | 활성 토큰 소모 | 서비스 간 내부 호출 | X-Internal-Caller, X-Correlation-Id | ConsumeTokenRequest {usedBy} | ConsumeTokenResponse | booking-app에서 reservation 저장 후 호출. |

### 3.2 concert-app

| Method | Path | 설명 | 호출 주체 | 헤더 | Request | Response | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | /internal/v1/seats/{seatId} | 좌석 상세 조회 | 서비스 간 내부 호출 | X-Internal-Caller, X-Correlation-Id | Path: seatId | SeatDetailResponse | seatId, scheduleId, eventId, seatNo, price, status, version 반환. |
| POST | /internal/v1/seats/{seatId}/hold | 좌석 HOLD | 서비스 간 내부 호출 | X-Internal-Caller, X-Correlation-Id | SeatCommandRequest {expectedStatus} | SeatCommandResponse | AVAILABLE -> HOLD. optimistic lock 충돌 시 409. |
| POST | /internal/v1/seats/{seatId}/release | 좌석 RELEASE | 서비스 간 내부 호출 | X-Internal-Caller, X-Correlation-Id | SeatCommandRequest {expectedStatus} | SeatCommandResponse | HOLD -> AVAILABLE. |
| POST | /internal/v1/seats/{seatId}/confirm | 좌석 CONFIRM | 서비스 간 내부 호출 | X-Internal-Caller, X-Correlation-Id | SeatCommandRequest {expectedStatus} | SeatCommandResponse | HOLD -> SOLD. |

### 3.3 booking-app

| Method | Path | 설명 | 호출 주체 | 헤더 | Request | Response | 비고 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | /internal/v1/reservations/{reservationId} | 예약 상세 조회 | 서비스 간 내부 호출 | X-Internal-Caller, X-Correlation-Id | Path: reservationId | ReservationResponseDTO | payment-app에서 reservation 검증용. |
| POST | /internal/v1/reservations/{reservationId}/confirm | 예약 확정 | 서비스 간 내부 호출 | X-Internal-Caller, X-Correlation-Id | ReservationConfirmRequestDTO {paymentId} | ReservationStatusResponseDTO | payment DONE 후 호출. seat CONFIRM 연계. |
| POST | /internal/v1/reservations/{reservationId}/cancel-confirmed | 결제 취소에 따른 예약 취소 | 서비스 간 내부 호출 | X-Internal-Caller, X-Correlation-Id | Path: reservationId | ReservationStatusResponseDTO | payment CANCELLED 후 호출. seat RELEASE 연계. |
| POST | /internal/v1/reservations/{reservationId}/expire | 예약 만료 처리 | 서비스 간 내부 호출 | X-Internal-Caller, X-Correlation-Id | Path: reservationId | ReservationStatusResponseDTO | PENDING && expired 대상. seat RELEASE. |

## 4. 대표 요청/응답 예시

### 4.1 대기열 진입

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

### 4.2 대기열 상태 조회 후 토큰 발급

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

### 4.3 예약 생성

```http
POST /api/v1/reservations
X-Auth-User-Id: 100
X-Waiting-Token: 550e8400-e29b-41d4-a716-446655440000
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

### 4.4 결제 준비

```http
POST /api/v1/payments/prepare
X-Auth-User-Id: 100
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

### 4.5 결제 승인

```http
POST /api/v1/payments/confirm
X-Auth-User-Id: 100
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
  "orderName": "콜드플레이 내한공연 VIP 1석",
  "paymentKey": "pay_8xvRL2D3mA9W7mYzKqP0n",
  "status": "DONE",
  "method": "CARD",
  "type": "NORMAL",
  "amount": 150000,
  "balanceAmount": 150000,
  "currency": "KRW",
  "lastTransactionKey": "tx_...",
  "cancelReason": null,
  "failureCode": null,
  "failureMessage": null,
  "orderExpiresAt": "2026-03-14T10:05:00",
  "requestedAt": "2026-03-14T10:01:03",
  "approvedAt": "2026-03-14T10:01:05",
  "canceledAt": null
}
```

### 4.6 결제 취소

```http
POST /api/v1/payments/50001/cancel
X-Auth-User-Id: 100
Idempotency-Key: pay-cancel-50001-v1
Content-Type: application/json

{
  "cancelReason": "사용자 요청 취소"
}
```

## 5. 핵심 설계 포인트

- 결제는 항상 `prepare -> confirm` 2단계로 분리하여, **주문번호와 금액의 source of truth를 서버가 가진다**는 원칙을 유지합니다.
- 예약 생성은 `waiting token 검증 -> seat HOLD -> reservation 저장 -> token consume` 순서로 처리하며, 중간 실패 시 좌석 RELEASE 보상을 수행합니다.
- 결제 승인 이후 booking 확정에 실패하면 **자동 환불 보상 트랜잭션**을 시도합니다.
- `Idempotency-Key`는 Redis에 `processing` / `response` 키로 나누어 저장하고, request hash 불일치를 별도로 차단합니다.
- 내부 API는 게이트웨이 바깥에서만 사용되며, 외부에 `/internal/**`가 보이지 않도록 차단합니다.
