---
title: "Payment Architecture: payment-app 심화 설계"
last_updated: 2026-03-18
author: "민석"
reviewer: ""
---

## 목차
- [Background](#background)
- [Problem](#problem)
- [Current Design](#current-design)
- [State / Flow](#state-flow)
- [Concurrency / Consistency Risks](#concurrency-consistency-risks)
- [Failure Scenarios](#failure-scenarios)
- [Observability](#observability)
- [Trade-offs](#trade-offs)
- [Planned Improvements](#planned-improvements)

# Payment Architecture: payment-app 심화 설계

> 이 문서는 payment-app의 내부 구조, 상태 전이, 트랜잭션 경계, 외부 서비스 의존성을 다룬다.
> API 계약(엔드포인트, 요청/응답 형식)은 [`docs/api/api-spec.md`](../../api/api-spec.md)를 참조한다.
> DB 스키마·Redis 키 패턴은 [`docs/data/database-cache-design.md`](../../data/database-cache-design.md)를 참조한다.
> 전체 MSA 서비스 의존 방향은 [`docs/architecture/overview.md`](../../architecture/overview.md)를 참조한다.
> Saga 흐름 개요는 이 문서 하단 `## Saga 흐름 다이어그램` 섹션을 참조한다.

---

## Background

TossPayments의 결제 흐름은 클라이언트가 PG SDK를 직접 호출하는 2단계 구조다.

```
서버 /request → 클라이언트가 TossPayments SDK로 결제 UI 진행 → 서버 /confirm
```

서버는 두 역할을 한다.
- `/request`: orderId와 amount를 서버에서 생성 → 금액 위변조 방지
- `/confirm`: 클라이언트가 받은 paymentKey로 TossPayments confirmPayment API 호출

payment-app은 이 흐름의 오케스트레이터다. 결제 승인, 예약 확정, 보상 취소를 모두 payment-app이 제어한다.

---

## Problem

결제 도메인에서 해결해야 하는 핵심 문제:

1. **이중 결제 방지**: 네트워크 재시도, 더블 클릭, 동시 요청 시 같은 예약에 두 번 결제 레코드가 생기면 안 된다.
2. **금액 위변조 방지**: 클라이언트가 amount를 조작해서 `/confirm`에 전송할 수 있다.
3. **PG 호출 중 DB 커넥션 점유**: PG 응답을 기다리는 동안(최대 10s) DB 커넥션을 붙잡으면 HikariCP 풀이 고갈된다.
4. **결제 성공 후 예약 확정 실패**: TossPayments 승인은 완료됐는데 booking-app confirm이 실패하면 고객 돈은 나갔으나 예약이 확정되지 않는다.
5. **보상 취소 실패**: PG 취소마저 실패하면 고객 자금이 CANCEL_FAILED 상태로 묶인다.

---

## Current Design

### 서비스 책임 경계

payment-app이 **직접 하는 것**:
- READY/APPROVED/FAILED/REFUNDED/CANCEL_FAILED 상태 관리
- TossPayments Basic Auth 인증, confirmPayment, cancelPayment 호출
- Redis idempotency key 관리 (동시 중복 요청 차단)
- orderId 생성 (`RES{reservationId}_{epochMilli}`)

payment-app이 **하지 않는 것** (다른 서비스에 위임):
- 예약 상태 변경 → booking-app 내부 API
- 좌석 SOLD 확정 → concert-app (booking-app을 통해 간접 호출)
- 사용자 신원 검증 → SCG의 Auth-Passport 헤더에 의존 (PassportCodec.decode()로 userId 추출) <!-- 2026-03-22 ADR-0007 Phase 2 완료 반영 -->

### 외부 서비스 의존 관계

```
payment-app
  ├── booking-app (internal)
  │     ├── GET /internal/v1/reservations/{id}          → 예약 상태 + userId + seatId 조회
  │     └── POST /internal/v1/reservations/{id}/confirm → 예약 확정 요청
  ├── concert-app (internal)
  │     └── GET /internal/v1/seats/{seatId}             → 좌석 가격 조회 (결제 시점 스냅샷)
  └── TossPayments API (external)
        ├── POST /payments/confirm                       → PG 승인
        └── POST /payments/{paymentKey}/cancel           → PG 취소
```

**concert-app 직접 의존 — 현재 구조와 trade-off:**

`PaymentManager.createPaymentRequest`는 concert-app을 직접 호출해 좌석 가격을 조회한다.
[`docs/architecture/overview.md`](../../architecture/overview.md)에 명시된 `payment → booking → concert` 단방향 의존 원칙에서 벗어난다.

| 현재 구조 | 권장 구조 (planned) |
|---------|----------------|
| payment-app → concert-app 직접 호출 | booking-app `/internal/reservations/{id}` 응답에 `amount` 포함 |
| 서비스 의존이 2개 (booking + concert) | payment-app이 booking-app만 의존 |

채택 이유: MVP 속도. booking-app 수정 없이 concert-app 직접 조회로 가격 스냅샷을 확보한다.

### 트랜잭션 경계 설계

`PaymentManager.java` 기준:

```
createPaymentRequest()
  └── @Transactional 전체 포함
        (booking-app 조회 + concert-app 가격 조회 + DB INSERT READY)

confirmPayment()
  └── 메서드 레벨 @Transactional 없음
        ├── [readOnly TX] readByOrderId(orderId)
        ├── 검증: validateConfirmable + validateAmount
        ├── [외부 호출] TossPayments confirmPayment(paymentKey, orderId, amount)
        │     └── 실패 시: [독립 TX] updateToFailed(paymentId, reason)
        ├── [독립 TX] updateToApproved(paymentId, paymentKey, method, approvedAt, pgResponseRaw)
        ├── [외부 호출] bookingClient.confirmReservation(reservationId, paymentId)
        │     └── 실패 시: initiateRefund()
        └── return approved

cancelPayment()
  └── 메서드 레벨 @Transactional 없음
        ├── [readOnly TX] readByPaymentKey(paymentKey)
        └── initiateRefund(paymentId, paymentKey, cancelReason)
```

**왜 confirmPayment에 @Transactional이 없는가:**
TossPayments read-timeout은 10초다. 단일 @Transactional로 묶으면 PG 응답 대기 10초 동안 HikariCP 커넥션 1개를 점유한다. 동시 요청이 HikariCP 풀 크기(기본 ~10개)를 넘으면 커넥션 고갈이 발생한다. PaymentWriter의 각 업데이트 메서드가 독립 @Transactional을 가지므로, 커넥션은 실제 DB 작업 시점에만 획득된다.

**PaymentWriter가 paymentId를 받아 내부에서 re-fetch하는 이유:**
PaymentManager의 confirmPayment가 트랜잭션이 없으므로 이전 단계에서 받은 Payment 도메인 객체는 영속성 컨텍스트 밖에 있다. Writer 내부에서 `findById(paymentId)`로 새 트랜잭션에서 다시 조회해 entity 오염(DetachedEntityException)을 방지한다.

---

## State / Flow

### Payment 상태 전이

```
         [/request 호출]
               │
               ▼
           [READY]
          /       \
   PG 승인 성공   PG 승인 실패
       │               │
       ▼               ▼
  [APPROVED]        [FAILED]
   /       \          (종료)
취소 성공  취소 실패
    │           │
    ▼           ▼
[REFUNDED]  [CANCEL_FAILED]
  (종료)    (수동 개입 필요)
```

> **CANCEL_PENDING**: `PaymentStatus` enum에 존재하나 현재 흐름에서 사용되지 않는다. `initiateRefund()`는 APPROVED에서 직접 REFUNDED 또는 CANCEL_FAILED로 전이한다. 향후 비동기 취소 흐름 도입 시를 위해 예약된 상태다. **(planned)**

### 정상 흐름 (요약)

```
[1] POST /request
    payment-app → booking-app: 예약 상태 검증 (PENDING + userId 일치)
    payment-app → concert-app: 좌석 가격 조회
    payment-app: DB INSERT payments (READY, orderId, amount)
    응답: {paymentId, orderId, amount}

[2] 클라이언트: TossPayments SDK로 결제 진행 → paymentKey 수령

[3] POST /confirm
    payment-app: readByOrderId → amount 검증
    payment-app → TossPayments: confirmPayment(paymentKey, orderId, amount)
    payment-app: DB UPDATE payments (APPROVED, paymentKey, method, approvedAt, pgResponseRaw)
    payment-app → booking-app: POST /internal/reservations/{id}/confirm
    응답: {status: APPROVED}
```

### 보상 흐름 (booking confirm 실패 시)

```
[3] POST /confirm
    ...
    [TossPayments 승인 완료]
    [DB APPROVED 업데이트 완료]
    payment-app → booking-app: confirm 호출
    booking-app: Exception 반환
    payment-app: initiateRefund() 진입
      → TossPayments cancelPayment(paymentKey)
        성공: DB UPDATE (REFUNDED)
        실패: DB UPDATE (CANCEL_FAILED)
              log.error("[CRITICAL] Payment cancel failed ... MANUAL INTERVENTION REQUIRED")
```

---

## Concurrency / Consistency Risks

### 이중 결제 방지: 2중 방어

**1차 방어: Redis Idempotency**
```
KEY: payment:idempotency:{idempotencyKey}
TTL: 24시간

요청 수신 → SETNX "PROCESSING" (원자적)
  성공(1): 처리 진행
  실패(0): P006 CONFLICT 반환 (다른 스레드가 처리 중)
완료 후 → SET {responseJson} EX 86400
실패 시 → DEL (재시도 가능 상태 복원)
```

**2차 방어: DB UNIQUE KEY**
```sql
CONSTRAINT uk_reservation_id UNIQUE (reservation_id)
CONSTRAINT uk_order_id UNIQUE (order_id)
```
Redis 장애 시에도 DataIntegrityViolationException → GlobalExceptionHandler → P002 반환.

**두 방어가 모두 필요한 이유:**

| 상황 | Redis만 있을 때 | DB UK만 있을 때 |
|------|-------------|--------------|
| Redis 장애 | 동시 요청 모두 통과 가능 | PG 두 번 호출 후 DB에서 차단 |
| 정상 동작 | 동시 요청 1개만 통과 | PG 호출 전 차단 불가 |

### amount 검증

`/confirm` 수신 시 DB의 저장 금액과 요청 금액을 비교한다:
```
if (storedAmount != requestAmount) → P003
```
TossPayments도 서버가 orderId 생성 시 지정한 amount와 다르면 PG 레벨에서 거부한다. 클라이언트 측 금액 조작은 두 지점에서 차단된다.

---

## Failure Scenarios

### 시나리오 1: TossPayments read-timeout (10s)

- `BusinessException(P005)` 발생
- `paymentWriter.updateToFailed(paymentId, reason)` → FAILED
- 클라이언트에 500 반환
- **위험**: TossPayments가 실제로 승인 완료했을 수 있음. Payment는 FAILED이나 PG에서는 APPROVED 상태 불일치 가능
- **현재 미처리**: reconciliation 로직 없음 **(planned: 웹훅 또는 cron 폴링)**

### 시나리오 2: booking confirm 실패 (PG 승인 완료 후)

```
TossPayments cancel 성공 → REFUNDED (정상 보상)
TossPayments cancel 실패 → CANCEL_FAILED + [CRITICAL] log
```
CANCEL_FAILED는 수동 개입이 필요하다. 현재 자동 알림 없음. **(planned: Alertmanager)**

### 시나리오 3: Redis 장애

- `IdempotencyManager.startProcessing()` → RedisException → 500 반환
- idempotency 검사 없이 DB UK가 2차 방어
- PG 이중 호출 가능성 존재
- **(planned: Redis 장애 시 idempotency skip + DB UK fallback)**

### 시나리오 4: createPaymentRequest 중 concert-app 장애

- RestClient connect-timeout(3s) / read-timeout(10s)
- @Transactional rollback → DB INSERT 없음
- 클라이언트 503 반환 (정상 실패)

---

## Observability

현재 구현된 로그:
```
INFO  Payment created - paymentId=50001, reservationId=90001, orderId=RES90001_xxx, amount=150000
INFO  Payment approved - paymentId=50001, orderId=RES90001_xxx
INFO  Reservation confirmed - reservationId=90001, paymentId=50001
ERROR Payment failed after PG rejection - orderId=RES90001_xxx
ERROR Reservation confirm failed - reservationId=90001, initiating refund
ERROR [CRITICAL] Payment cancel failed - paymentId=50001, paymentKey=pay_xxx - MANUAL INTERVENTION REQUIRED
```

이상 상태 모니터링 쿼리:
```sql
-- CANCEL_FAILED 잔류
SELECT payment_id, reservation_id, order_id, created_at
FROM ticketing_payment.payments WHERE status = 'CANCEL_FAILED';

-- READY 10분 이상 잔류 (PG timeout 의심)
SELECT payment_id, order_id, created_at
FROM ticketing_payment.payments
WHERE status = 'READY' AND created_at < NOW() - INTERVAL 10 MINUTE;
```

planned:
- `payment.confirm.total{result="success/pg_error/booking_failed/cancel_failed"}` Micrometer counter (Prometheus: `payment_confirm_total`) <!-- 2026-03-18 메트릭 명칭 통일 -->
- CANCEL_FAILED → Alertmanager 자동 알림

---

## Trade-offs

| 결정 | 이유 | 잃은 것 |
|------|------|---------|
| confirmPayment @Transactional 없음 | PG 10s 대기 중 커넥션 비점유 | 단일 트랜잭션 보장 없음. 각 단계가 독립 커밋 |
| concert-app 직접 호출 (가격 조회) | MVP 속도, booking-app 수정 불필요 | 서비스 의존 방향 원칙 위반 |
| CANCEL_PENDING 미사용 | 동기 보상 흐름에서 불필요 | 향후 비동기 취소 도입 시 상태 추가 필요 |
| orderId 서버 생성 | 클라이언트 orderId 조작 차단 | 클라이언트가 서버 2회 호출 필요 |
| pg_response_raw 전체 저장 | PG 분쟁 시 원문 증거 | 카드 관련 PII 저장 가능 (마스킹 planned) |
| Redis + DB UK 이중 방어 | Redis 장애 시에도 최종 방어 | 각 방어 레이어 독립 유지 필요 |

---

## Planned Improvements

1. **TossPayments 웹훅** (planned): read-timeout 발생 후 READY 상태 결제를 웹훅으로 최종 상태 동기화
2. **cron 기반 reconciliation** (planned): READY 10분 이상 잔류 건을 TossPayments 조회 API로 상태 확인
3. **CANCEL_FAILED 자동 알림** (planned): Alertmanager → Slack 연동
4. **concert-app 의존 제거** (planned): booking-app `/internal/reservations/{id}`에 `amount` 추가
5. **Redis 장애 시 idempotency fallback** (planned): RedisException 시 DB UK만으로 동작

---
