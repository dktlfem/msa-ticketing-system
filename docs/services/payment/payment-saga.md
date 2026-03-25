---
title: "Payment Saga: 결제-예약 보상 트랜잭션 설계"
last_updated: 2026-03-25
author: "민석"
reviewer: ""
---

## 목차
- [Overview](#overview)
- [Why Saga](#why-saga)
- [Saga Participants](#saga-participants)
- [Happy Path](#happy-path)
- [Compensation Flows](#compensation-flows)
- [State Transition During Saga](#state-transition-during-saga)
- [Transaction Boundary Design](#transaction-boundary-design)
- [Failure Matrix](#failure-matrix)
- [Idempotency in Saga Context](#idempotency-in-saga-context)
- [Current Limitations](#current-limitations)
- [Related Documents](#related-documents)

# Payment Saga: 결제-예약 보상 트랜잭션 설계

> 이 문서는 payment-app과 booking-app 간의 Saga 보상 트랜잭션 흐름을 다룬다.
> 결제 도메인 내부 설계(트랜잭션 경계, 상태 전이, 장애 시나리오)는 [`payment-architecture.md`](./payment-architecture.md)를 참조한다.
> API 계약은 [`docs/api/api-spec.md`](../../api/api-spec.md)를 참조한다.

---

## Overview

대규모 티켓팅 시스템에서 "결제 승인 → 예약 확정"은 두 개의 독립된 서비스(payment-app, booking-app)에 걸친 분산 트랜잭션이다. 단일 DB 트랜잭션으로 묶을 수 없으므로 Saga 패턴을 적용해 최종 일관성(Eventual Consistency)을 보장한다.

핵심 원칙: **결제는 성공했으나 예약 확정이 실패하면, 반드시 결제를 취소(보상)해 고객 자금을 보호한다.**

---

## Why Saga

### 분산 트랜잭션이 필요한 이유

```
payment-app (ticketing_payment DB)  ←→  booking-app (ticketing_booking DB)
```

Database per Service 원칙에 따라 각 서비스가 독립 DB를 소유한다. 2PC(Two-Phase Commit)는 다음 이유로 채택하지 않았다.

| 항목 | 2PC | Saga (현재 채택) |
|------|-----|-----------------|
| 외부 PG 참여 | TossPayments는 XA 미지원 | PG를 일반 API 호출로 처리 가능 |
| 가용성 | 코디네이터 SPOF | 각 서비스 독립 운영 |
| 성능 | 전체 참여자 락 대기 | 각 단계 독립 커밋, 커넥션 비점유 |
| 복잡도 | 분산 락 매니저 필요 | 보상 로직만 추가 |

### Orchestration vs Choreography

현재 구현은 **Orchestration 방식**이다. `PaymentManager`가 오케스트레이터로서 전체 Saga 흐름을 제어한다.

| 항목 | Orchestration (현재) | Choreography |
|------|---------------------|-------------|
| 흐름 제어 | PaymentManager가 순서 결정 | 각 서비스가 이벤트 발행/구독 |
| 디버깅 | 단일 클래스에서 전체 흐름 추적 | 이벤트 체인 추적 필요 |
| 결합도 | payment-app이 booking-app 의존 | 느슨한 결합 |
| 적합 시나리오 | 참여자 2개 (현재) | 참여자 3개 이상 |

<!-- ADR: 현재 Saga 참여자가 payment-app, booking-app 2개뿐이므로
     Orchestration이 구현 단순성, 디버깅 용이성 측면에서 유리하다.
     향후 concert-app 좌석 SOLD 확정이 별도 Saga 단계로 분리되면
     Choreography(Kafka 이벤트 기반) 전환을 검토한다. -->

---

## Saga Participants

```
┌─────────────┐     ┌──────────────┐     ┌──────────────────┐
│  payment-app │     │  booking-app │     │  TossPayments PG │
│ (오케스트레이터) │     │  (참여자)      │     │  (외부 시스템)     │
└──────┬──────┘     └──────┬───────┘     └────────┬─────────┘
       │                   │                      │
       │  Saga 흐름 제어    │  예약 상태 변경       │  결제 승인/취소
       │  보상 트랜잭션 결정 │  (PENDING→CONFIRMED) │
       └───────────────────┴──────────────────────┘
```

| 참여자 | 역할 | 정방향 액션 | 보상 액션 |
|--------|------|-----------|----------|
| TossPayments | 결제 승인 | `POST /payments/confirm` | `POST /payments/{paymentKey}/cancel` |
| payment-app | 결제 상태 관리 | DB UPDATE → APPROVED | DB UPDATE → REFUNDED or CANCEL_FAILED |
| booking-app | 예약 확정 | `POST /internal/v1/reservations/{id}/confirm` | (현재 미구현: 예약 롤백 API) |

---

## Happy Path

정상 흐름에서 Saga는 3단계로 완료된다.

```
Client          payment-app         TossPayments        booking-app
  │                  │                    │                    │
  │ POST /confirm    │                    │                    │
  │ (orderId,        │                    │                    │
  │  paymentKey,     │                    │                    │
  │  amount)         │                    │                    │
  │─────────────────>│                    │                    │
  │                  │                    │                    │
  │            ┌─────┴─────┐              │                    │
  │            │ Step 1     │              │                    │
  │            │ 검증       │              │                    │
  │            │ - READY?   │              │                    │
  │            │ - 금액일치? │              │                    │
  │            └─────┬─────┘              │                    │
  │                  │                    │                    │
  │                  │ confirmPayment     │                    │
  │            ┌─────┴────────────────────┤                    │
  │            │ Step 2: PG 승인          │                    │
  │            │ (paymentKey, orderId,    │                    │
  │            │  amount)                 │                    │
  │            └─────┬────────────────────┤                    │
  │                  │ TossConfirmResponse │                    │
  │                  │<───────────────────│                    │
  │                  │                    │                    │
  │            ┌─────┴─────┐              │                    │
  │            │ DB UPDATE  │              │                    │
  │            │ → APPROVED │              │                    │
  │            └─────┬─────┘              │                    │
  │                  │                    │                    │
  │                  │ confirmReservation  │                    │
  │            ┌─────┴────────────────────┼────────────────────┤
  │            │ Step 3: 예약 확정        │                    │
  │            │ (reservationId,          │                    │
  │            │  paymentId)              │                    │
  │            └─────┬────────────────────┼────────────────────┤
  │                  │                    │           200 OK   │
  │                  │<───────────────────┼────────────────────│
  │                  │                    │                    │
  │  200 APPROVED    │                    │                    │
  │<─────────────────│                    │                    │
```

---

## Compensation Flows

### Case 1: 예약 확정 실패 → PG 취소 성공 (정상 보상)

```
payment-app         TossPayments        booking-app
     │                    │                    │
     │ confirmReservation │                    │
     │────────────────────┼───────────────────>│
     │                    │        Exception   │
     │<───────────────────┼────────────────────│
     │                    │                    │
     │ ┌──────────────┐   │                    │
     │ │ 보상 진입     │   │                    │
     │ │ initiateRefund│   │                    │
     │ └──────┬───────┘   │                    │
     │        │            │                    │
     │ cancelPayment      │                    │
     │───────────────────>│                    │
     │   TossCancelResponse                    │
     │<───────────────────│                    │
     │                    │                    │
     │ ┌──────────────┐   │                    │
     │ │ DB UPDATE     │   │                    │
     │ │ → REFUNDED    │   │                    │
     │ └──────────────┘   │                    │
     │                    │                    │
     │ BusinessException(P004) → Client        │
```

이 경우 고객 자금은 정상 환불되고, 클라이언트는 "예약 확정 실패로 결제가 취소되었습니다" 메시지를 받는다.

### Case 2: 예약 확정 실패 → PG 취소도 실패 (CANCEL_FAILED)

```
payment-app         TossPayments        booking-app
     │                    │                    │
     │ confirmReservation │                    │
     │────────────────────┼───────────────────>│
     │                    │        Exception   │
     │<───────────────────┼────────────────────│
     │                    │                    │
     │ cancelPayment      │                    │
     │───────────────────>│                    │
     │             Exception (PG 장애)         │
     │<───────────────────│                    │
     │                    │                    │
     │ ┌─────────────────────────────────────┐ │
     │ │ DB UPDATE → CANCEL_FAILED           │ │
     │ │ [CRITICAL] log: MANUAL INTERVENTION │ │
     │ └─────────────────────────────────────┘ │
     │                    │                    │
     │ BusinessException(P005) → Client        │
```

CANCEL_FAILED는 고객 돈이 PG에 묶인 상태다. 현재는 `[CRITICAL]` 로그로 기록하며, 운영자가 수동으로 TossPayments 관리자 페이지에서 취소를 처리해야 한다.

---

## State Transition During Saga

Saga 각 단계에서 Payment 상태가 어떻게 전이되는지 보여준다.

```
                    Saga Step 1           Saga Step 2            Saga Step 3
                    (PG 승인)             (DB 업데이트)           (예약 확정)
                        │                     │                      │
                        ▼                     ▼                      ▼
   ┌───────┐    PG 성공     PG 실패     DB 커밋        confirm 성공    confirm 실패
   │ READY │────┬──────────┬──────────┬──────────────┬──────────────┬──────────┐
   └───────┘    │          │          │              │              │          │
                ▼          ▼          ▼              ▼              ▼          │
              (진행)    ┌────────┐  ┌──────────┐   (완료)     ┌──────────┐    │
                       │ FAILED │  │ APPROVED │              │보상 진입  │    │
                       └────────┘  └──────────┘              └────┬─────┘    │
                                                                  │          │
                                                          PG 취소 성공  PG 취소 실패
                                                              │          │
                                                              ▼          ▼
                                                        ┌──────────┐ ┌───────────────┐
                                                        │ REFUNDED │ │ CANCEL_FAILED │
                                                        └──────────┘ └───────────────┘
```

| Payment 상태 | 의미 | Saga 관점 |
|-------------|------|----------|
| READY | 결제 대기 | Saga 시작 전 |
| APPROVED | PG 승인 완료 | Saga Step 2 완료 |
| FAILED | PG 승인 실패 | Saga 실패 (보상 불필요) |
| REFUNDED | PG 취소 완료 | 보상 트랜잭션 성공 |
| CANCEL_FAILED | PG 취소 실패 | 보상 트랜잭션 실패 (수동 개입) |

---

## Transaction Boundary Design

Saga의 각 단계가 독립 트랜잭션으로 실행되는 이유와 구조.

```
PaymentManager.confirmPayment()          ← @Transactional 없음 (오케스트레이터)
  │
  ├── paymentReader.readByOrderId()      ← @Transactional(readOnly=true)
  │     └── DB 커넥션 획득 → 조회 → 반환
  │
  ├── tossPaymentsClient.confirmPayment()  ← 트랜잭션 밖 (외부 HTTP 호출, 최대 10s)
  │     └── DB 커넥션 점유 없음
  │
  ├── paymentWriter.updateToApproved()   ← @Transactional (독립)
  │     └── DB 커넥션 획득 → findById → UPDATE → 커밋 → 반환
  │
  ├── bookingClient.confirmReservation() ← 트랜잭션 밖 (내부 HTTP 호출)
  │     └── DB 커넥션 점유 없음
  │
  └── [실패 시] initiateRefund()
        ├── tossPaymentsClient.cancelPayment()  ← 트랜잭션 밖
        └── paymentWriter.updateToRefunded()    ← @Transactional (독립)
              또는 paymentWriter.updateToCancelFailed()
```

**PaymentWriter가 paymentId를 받아 re-fetch하는 이유:**

PaymentManager에 @Transactional이 없으므로 이전 단계에서 조회한 Payment 객체는 영속성 컨텍스트(Persistence Context) 밖에 있다. Writer 내부에서 `findById(paymentId)`로 새 트랜잭션에서 다시 조회해 DetachedEntityException을 방지한다.

---

## Failure Matrix

Saga의 각 단계에서 발생할 수 있는 실패와 시스템 대응을 정리한다.

| 실패 지점 | Payment 최종 상태 | 고객 자금 | 예약 상태 | 대응 |
|----------|-----------------|----------|---------|------|
| PG 승인 실패 (4xx) | FAILED | 미출금 | PENDING 유지 | 재시도 가능 |
| PG 승인 timeout | FAILED | **불확실** | PENDING 유지 | reconciliation 필요 (planned) |
| DB APPROVED 커밋 실패 | READY | PG에서 승인됨 | PENDING 유지 | reconciliation 필요 (planned) |
| booking confirm 실패 | REFUNDED | 환불 완료 | PENDING 유지 | 정상 보상 |
| booking confirm 실패 + PG 취소 실패 | CANCEL_FAILED | **PG에 묶임** | PENDING 유지 | 수동 개입 필요 |
| booking confirm timeout | REFUNDED or CANCEL_FAILED | 환불 or 묶임 | **불확실** | booking-app 멱등성 필요 (planned) |

**가장 위험한 시나리오: PG 승인 timeout**

TossPayments가 실제로 승인을 처리했으나 응답이 timeout으로 돌아온 경우, Payment는 FAILED이지만 PG에서는 APPROVED 상태다. 현재 이 불일치를 해소하는 로직이 없다.

계획된 해결 방안:
1. **TossPayments 웹훅**: PG가 최종 상태를 서버로 push (SCRUM-46 Phase 2)
2. **cron reconciliation**: READY 10분 이상 잔류 건을 TossPayments 조회 API로 상태 확인 (SCRUM-46 Phase 2)

---

## Idempotency in Saga Context

Saga 재시도 시 멱등성이 보장되어야 한다.

### 현재 구현

`POST /confirm` 호출 시 `Idempotency-Key` 헤더를 기반으로 Redis SETNX + DB UK 이중 방어.

```
Client → POST /confirm (Idempotency-Key: "abc-123")
  │
  ├── Redis SETNX "payment:idempotency:abc-123" = "PROCESSING"
  │     ├── 성공: 진행
  │     └── 실패(키 존재):
  │           ├── 값 = "PROCESSING" → P006 CONFLICT (다른 스레드 처리 중)
  │           └── 값 = "{response}" → 캐시된 응답 반환 (동일 결과 보장)
  │
  └── Saga 완료 후: Redis SET "payment:idempotency:abc-123" = "{responseJson}" EX 86400
```

### 보상 트랜잭션의 멱등성

`initiateRefund()`는 현재 멱등하지 않다. TossPayments `cancelPayment`는 이미 취소된 결제를 다시 취소하면 에러를 반환한다.

계획된 개선: REFUNDED 상태 선검증 후 PG 호출 스킵 (SCRUM-48 범위)

---

## Current Limitations

| 제한 사항 | 영향 | 계획 |
|----------|------|------|
| PG timeout 후 상태 불일치 | 고객 자금 미환불 가능 | SCRUM-46 Phase 2: 웹훅 + cron reconciliation |
| CANCEL_FAILED 수동 처리 | 운영 부담 | SCRUM-49: Alertmanager 자동 알림 |
| booking confirm 멱등성 미보장 | timeout 재시도 시 이중 확정 가능 | SCRUM-48: 예약 확정 멱등성 API |
| concert-app 직접 의존 | 서비스 의존 원칙 위반 | SCRUM-48: booking-app 응답에 amount 포함 |
| initiateRefund 멱등성 없음 | 보상 재시도 시 PG 에러 | SCRUM-48: REFUNDED 상태 선검증 |
| 비동기 보상 미지원 | 동기 호출 실패 시 즉시 CANCEL_FAILED | 향후: Kafka 이벤트 기반 비동기 보상 검토 |

---

## Related Documents

| 문서 | 내용 |
|------|------|
| [`payment-architecture.md`](./payment-architecture.md) | 결제 도메인 내부 설계 (트랜잭션 경계, 상태 전이, 장애 시나리오) |
| [`docs/api/api-spec.md`](../../api/api-spec.md) | 결제 API 계약 (엔드포인트, idempotency 흐름, 에러 코드) |
| [`docs/data/database-cache-design.md`](../../data/database-cache-design.md) | payments DDL, Redis 키 패턴 |
| [`docs/architecture/overview.md`](../../architecture/overview.md) | MSA 서비스 의존 방향 |
| [`docs/architecture/adr/`](../../architecture/adr/) | ADR-0007: Auth-Passport 헤더 체계 |
