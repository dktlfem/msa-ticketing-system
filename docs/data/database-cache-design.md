---
title: "Database & Cache Design: 실제 구현 기준"
last_updated: 2026-03-18
author: "민석"
reviewer: ""
---

## 목차
- [Background](#background)
- [Problem](#problem)
- [Design](#design)
- [Redis 키 패턴](#redis-키-패턴)
- [무엇을 캐시하지 않는가](#무엇을-캐시하지-않는가)
- [Trade-offs](#trade-offs)
- [Failure Scenarios](#failure-scenarios)
- [Observability](#observability)

# Database & Cache Design: 실제 구현 기준

> 이 문서는 **실제 JPA 엔티티와 Redis 키 패턴**을 기준으로 작성합니다.
> 파티셔닝 전략, 인덱스 체크리스트, 시스템 전체 ERD 등 설계 원칙은 이 문서 하단 섹션에 통합되어 있습니다.

---

## Background

서비스별 스키마 분리 원칙을 따릅니다. 5개 서비스가 각자 스키마를 소유하며, 서비스 간 FK를 물지 않습니다. 대신 ID 참조 + internal API로 정합성을 관리합니다.

| 스키마 | 소유 서비스 | 테이블 |
|--------|-----------|--------|
| `ticketing_user` | user-app | users |
| `ticketing_concert` | concert-app | events, event_schedules, seats |
| `ticketing_booking` | booking-app | reservations |
| `ticketing_payment` | payment-app | payments |
| `ticketing_waitingroom` | waitingroom-app | active_tokens |

---

## Problem

결제 데이터 설계에서 해결해야 하는 문제:

1. **이중 결제 방지**: 같은 예약에 두 번 결제 레코드가 생기면 안 됨
2. **PG 정합성**: TossPayments의 orderId, paymentKey가 시스템 내에서 유일해야 함
3. **감사(Audit) 요구**: 결제 분쟁 시 PG 원문 응답이 필요
4. **상태 추적**: 보상 실패(CANCEL_FAILED) 같은 이상 상태를 즉시 탐지

---

## Design

### payments 테이블 DDL

`PaymentEntity.java` 기준 실제 생성 DDL:

```sql
CREATE TABLE ticketing_payment.payments (
    payment_id      BIGINT          NOT NULL AUTO_INCREMENT,
    reservation_id  BIGINT          NOT NULL,
    user_id         BIGINT          NOT NULL,
    order_id        VARCHAR(64)     NOT NULL,
    payment_key     VARCHAR(200)    NULL,           -- PG 승인 전까지 NULL
    amount          DECIMAL(10, 0)  NOT NULL,
    status          VARCHAR(20)     NOT NULL,
    method          VARCHAR(20)     NULL,
    fail_reason     VARCHAR(500)    NULL,
    pg_response_raw TEXT            NULL,           -- PG 응답 원문 (감사용)
    approved_at     DATETIME        NULL,
    cancelled_at    DATETIME        NULL,
    created_at      DATETIME        NOT NULL,
    updated_at      DATETIME        NOT NULL,

    PRIMARY KEY (payment_id),

    -- 이중 결제 방지: 예약 1건당 결제 1건
    CONSTRAINT uk_reservation_id UNIQUE (reservation_id),

    -- TossPayments 주문번호 중복 방지
    CONSTRAINT uk_order_id UNIQUE (order_id),

    -- 인덱스
    INDEX idx_user_id (user_id),
    INDEX idx_status (status)
);
```

**설계 결정 근거:**

| 제약 | 이유 |
|------|------|
| `uk_reservation_id` | `reservationId`는 1:1 결제 보장. Idempotency-Key 없이도 2차 방어 |
| `uk_order_id` | 같은 orderId로 TossPayments에 이중 승인 시 결제 레코드 중복 방지 |
| `payment_key NULL` | READY 상태에서는 PG 미호출이므로 paymentKey 없음. NULL 허용 UK는 MySQL에서 중복 허용 |
| `pg_response_raw TEXT` | PG 분쟁 시 원문 필요. 스토리지 비용 << 분쟁 처리 비용 |
| `amount DECIMAL(10,0)` | 원화(KRW)는 소수점 없음. 정밀도 손실 없이 정수 처리 |

**payment_key에 UK를 별도로 추가하지 않은 이유:**

원래 설계에서 `uk_payment_key`를 추가했으나, `payment_key`는 NULL 허용 컬럼입니다. MySQL에서 NULL 값은 UNIQUE 제약에서 중복으로 처리되지 않으므로 여러 READY 레코드가 `payment_key = NULL`인 상태가 가능합니다. 대신 TossPayments의 paymentKey는 PG가 전역 유일하게 관리하며, `uk_order_id`로 동일 orderId 재사용이 이미 차단됩니다.

---

### reservations 테이블 (booking-app 소유, 참고용)

```sql
-- ReservationEntity.java 기준 (booking-app 소유, payment-app은 internal API로만 접근)
CREATE TABLE ticketing_booking.reservations (
    reservation_id  BIGINT      NOT NULL AUTO_INCREMENT,
    user_id         BIGINT      NOT NULL,
    seat_id         BIGINT      NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reserved_at     DATETIME    NOT NULL,
    expired_at      DATETIME    NOT NULL,

    PRIMARY KEY (reservation_id),
    INDEX idx_user_id (user_id),
    INDEX idx_status_expired (status, expired_at)
);
```

payment-app이 이 테이블에 직접 접근하지 않습니다. booking-app의 `/internal/v1/reservations/{id}`로만 접근합니다.

---

### 상태 전이와 DB 업데이트 매핑

`PaymentWriter.java` 각 메서드가 독립 `@Transactional`로 실행됩니다:

| 메서드 | 상태 전이 | 변경 컬럼 |
|--------|----------|----------|
| `save(payment)` | → READY | 전체 insert |
| `updateToApproved(...)` | READY → APPROVED | payment_key, method, approved_at, pg_response_raw, status |
| `updateToFailed(...)` | READY → FAILED | fail_reason, status |
| `updateToRefunded(...)` | APPROVED → REFUNDED | cancelled_at, status |
| `updateToCancelFailed(...)` | APPROVED → CANCEL_FAILED | status |

모든 update 메서드는 `paymentId`로 entity를 새로 조회합니다. 이전 단계 트랜잭션이 커밋된 후 새 트랜잭션이 시작되므로, 교차 트랜잭션 엔티티 오염이 없습니다.

---

## Redis 키 패턴

### 1. Payment Idempotency

`IdempotencyManager.java`:

```
KEY: payment:idempotency:{idempotencyKey}
TTL: 24시간
TYPE: String

값의 생명주기:
  [처음] 키 없음
  [처리 시작] SETNX → "PROCESSING"
  [처리 완료] SET → "{\"paymentId\":50001, ...}" (응답 JSON)
  [처리 실패] DEL → 키 삭제 (재시도 가능)
```

**Redis 명령 예시:**
```
SETNX payment:idempotency:pay-req-90001-uuid-v1 PROCESSING
EXPIRE payment:idempotency:pay-req-90001-uuid-v1 86400
GET payment:idempotency:pay-confirm-50001-uuid-v1
SET payment:idempotency:pay-confirm-50001-uuid-v1 '{"paymentId":50001,...}' EX 86400
```

### 2. 대기열 (waitingroom-app 소유, 참고용)

```
KEY: waiting-room:event:{eventId}
TYPE: Sorted Set
MEMBER: userId
SCORE: 진입 시각 epoch
```

### 3. Rate Limiting (waitingroom-app 소유, 참고용)

```
KEY: rate_limit:event:{eventId}:{epochSecond}
TYPE: String (counter)
TTL: 2초
```

---

## 무엇을 캐시하지 않는가

| 데이터 | Redis 캐시 여부 | 이유 |
|--------|----------------|------|
| payment.status | **X** | 결제 상태는 DB가 source of truth. 돈이 관련된 상태는 ACID 보장 필수 |
| payment.amount | **X** | 금액 위변조 방지를 위해 DB 단독 관리 |
| reservation.status | **X** | 예약 확정/취소 상태는 DB authoritative |
| seat.status | **X** | 좌석 재고는 DB optimistic lock으로 관리 |
| idempotency key | **O** | TTL 자동 만료, 높은 읽기 빈도, 임시 데이터 |
| 대기열 순번 | **O** | 실시간 순번 계산이 필요하고 TTL 만료가 자연스러움 |
| 이벤트/공연 정보 (planned) | **O** | read-heavy, 변경 빈도 낮음 → L1(Caffeine) + L2(Redis) 적합 |

---

## Trade-offs

| 결정 | 얻은 것 | 잃은 것 |
|------|---------|---------|
| DB UK(reservation_id, order_id) | Redis 없이도 중복 결제 방지 | UK 위반 전에 PG 호출이 일어날 수 있음 (Redis가 없으면) |
| pg_response_raw TEXT 컬럼 | PG 분쟁 대응 가능, 디버깅 정보 보존 | 레코드당 수 KB 저장 |
| DECIMAL(10,0) for amount | 정수 원화 금액 정확 처리 | 다국통화 확장 시 DECIMAL(15,2) 또는 별도 컬럼 필요 |
| Idempotency 키를 Redis에 저장 | 빠른 중복 체크, TTL 자동 만료 | Redis 장애 시 idempotency 검사 불가 (DB UK가 fallback) |
| 서비스별 스키마 분리 | 배포/장애 독립성 | 서비스 간 JOIN 불가, 정합성은 애플리케이션에서 보장 |

---

## Failure Scenarios

### Redis 장애 시 idempotency 처리
1. `IdempotencyManager.startProcessing()` 에서 RedisException 발생
2. 현재 구현: Exception이 상위로 전파되어 500 에러 반환
3. **개선 방안 (planned)**: Redis 장애 시 idempotency 체크를 skip하고 DB UK에 fallback
   - 이 경우 PG 이중 호출이 일어날 수 있으나 DB UK가 최종 방어

### DB UK 위반 (DataIntegrityViolationException)
- `GlobalExceptionHandler`에서 `P002` 에러로 변환
- 이미 결제가 존재하는 예약에 대한 두 번째 요청을 막음

### CANCEL_FAILED 상태 잔류
```sql
-- 수동 조회
SELECT * FROM ticketing_payment.payments WHERE status = 'CANCEL_FAILED';
-- 모니터링: idx_status 인덱스로 빠른 조회 가능
```

---

## Observability

**idx_status 인덱스의 운영 활용:**
```sql
-- 이상 상태 모니터링 (CANCEL_FAILED)
SELECT payment_id, reservation_id, order_id, payment_key, created_at
FROM ticketing_payment.payments
WHERE status = 'CANCEL_FAILED'
ORDER BY created_at DESC;

-- READY 상태 장시간 잔류 감지 (PG 타임아웃 의심)
SELECT payment_id, order_id, created_at
FROM ticketing_payment.payments
WHERE status = 'READY'
  AND created_at < NOW() - INTERVAL 10 MINUTE;
```

**Grafana 메트릭 태그:**
- `management.metrics.tags.application=payment-service` 설정으로 Grafana에서 payment-service 단독 필터링 가능

---
