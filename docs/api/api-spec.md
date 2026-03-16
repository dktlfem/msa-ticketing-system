# API Specification: payment-app 구현 기준

> 이 문서는 **payment-app의 실제 구현 코드를 기준**으로 작성한 API 계약입니다.
> 시스템 전체 API 카탈로그는 [`docs/01-api-specification.md`](../01-api-specification.md)를 참고합니다.
> payment-app 내부 구현 설계는 [`docs/services/payment/payment-architecture.md`](../services/payment/payment-architecture.md)를 참고합니다.

---

## Background

TossPayments의 결제 흐름은 클라이언트가 PG SDK를 직접 호출하는 2단계 구조입니다.

```
서버 /request  →  클라이언트가 TossPayments SDK 호출  →  서버 /confirm
```

이 구조에서 서버는 두 가지 역할을 합니다:
1. `/request`: 주문번호(orderId)와 금액을 서버가 생성 → 클라이언트가 이 값으로 PG SDK 호출
2. `/confirm`: 클라이언트에서 받은 paymentKey로 TossPayments confirmPayment API 호출

---

## Problem

결제 API 설계에서 반드시 해결해야 하는 문제:

1. **금액 위변조**: 클라이언트가 amount를 바꿔서 전송하면 이중 검증이 필요
2. **중복 결제**: 같은 예약에 대해 네트워크 오류로 confirm이 2번 호출될 수 있음
3. **결제 후 예약 확정 실패**: 돈은 나갔는데 예약이 안 되는 상황
4. **PG 타임아웃**: TossPayments confirm이 응답 없이 끊기면 결제 여부를 알 수 없음

---

## Design

### 공통 헤더

| 헤더 | 필수 여부 | 설명 |
|------|----------|------|
| `X-User-Id` | 결제 요청/조회 시 필수 | SCG가 전달하는 인증된 사용자 ID |
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

### 에러 응답 형식

구현 클래스: `GlobalExceptionHandler.java`

```json
{
  "code": "P003",
  "message": "결제 금액 불일치. 저장된 금액: 150000, 요청 금액: 120000",
  "timestamp": "2026-03-16T15:30:00"
}
```

### 에러 코드 전체 목록

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

## Endpoints

### POST /api/v1/payments/request

결제 레코드를 생성합니다. 응답의 `orderId`와 `amount`를 클라이언트가 TossPayments SDK에 전달합니다.

**요청**

```http
POST /api/v1/payments/request
X-User-Id: 100
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

### POST /api/v1/payments/confirm

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

### POST /api/v1/payments/{paymentKey}/cancel

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

### GET /api/v1/payments/{paymentId}

결제 상태를 조회합니다.

**요청**

```http
GET /api/v1/payments/50001
X-User-Id: 100
```

**응답:** 위 PaymentResponseDTO와 동일한 구조.

---

## Internal API (booking-app → payment-app)

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

*최종 업데이트: 2026-03-16 | payment-app 구현 반영*
