# Architecture Overview: MSA 기반 티켓팅 플랫폼

> 이 문서는 시스템의 **왜(Why)**와 **경계(Boundary)**에 집중합니다.
> 인프라/배포 세부사항은 [`docs/02-architecture-infrastructure.md`](../02-architecture-infrastructure.md)를 참고합니다.

---

## Background

티켓팅 시스템은 아래 세 가지 특성이 동시에 발생하는 도메인입니다.

1. **짧은 피크 트래픽**: 오픈 직후 수천 명이 동시 진입
2. **강한 정합성 요구**: 좌석 중복 예약 불가, 결제 이중 처리 불가
3. **도메인 간 명확한 책임 분리**: 대기열·좌석·예약·결제는 서로 다른 생명주기를 가짐

단일 서버로 구성하면 피크 시점에 전체가 함께 장애를 맞고, 어느 한 도메인의 변경이 다른 도메인에 영향을 줍니다.

---

## Problem

**왜 MSA인가:**

| 문제 | 모놀리스 | MSA |
|------|---------|-----|
| 피크 트래픽 | 전체 스케일아웃 필요 | waitingroom-app만 집중 스케일아웃 |
| 좌석 중복 예약 | 락 범위가 전체 트랜잭션에 영향 | concert-app에서 optimistic lock 국소화 |
| 결제 장애 | 결제 오류가 예약 흐름 전체 중단 | payment-app 장애가 booking 조회에 영향 없음 |
| 배포 | 한 기능 수정 시 전체 재배포 | 서비스별 독립 배포 |

**왜 waitingroom-app을 별도 서비스로 분리했는가:**

대기열은 예약/결제와 생명주기가 완전히 다릅니다. 대기열 토큰은 이벤트별로 발급/만료되고, 예약이 완료되면 소멸합니다. 이 상태를 Redis Sorted Set으로 처리하는 로직이 예약·결제 트랜잭션과 물리적으로 분리되어야 장애 전파를 막을 수 있습니다.

---

## Design

### 서비스 책임 경계

```
┌─────────────────────────────────────────────────────────────┐
│                        scg-app                               │
│  /api/v1/** 라우팅, X-Correlation-Id 전파, /internal 차단   │
└──────────┬──────────┬──────────┬──────────┬─────────────────┘
           │          │          │          │
    ┌──────▼───┐ ┌────▼────┐ ┌───▼────┐ ┌──▼───────┐
    │waitingroom│ │concert  │ │booking │ │payment   │
    │           │ │         │ │        │ │          │
    │ - 토큰 발급│ │ - 공연 조│ │ - 좌석 │ │ - PG 연동│
    │ - 순번 관리│ │   회     │ │   선점 │ │ - idempo │
    │ - 입장 허용│ │ - 좌석 조│ │ - 예약 │ │   tency  │
    └──────┬────┘ │   회     │ │   확정 │ │ - 보상   │
           │      └────┬─────┘ └───┬────┘ └──┬───────┘
           │           │            │          │
    Redis SortedSet  MySQL         MySQL      MySQL
    (대기열 순번)    (seats)    (reservations) (payments)
```

### 서비스 간 호출 의존성

```
payment-app
  ├── booking-app (internal): reservation 조회, confirm 호출
  ├── concert-app (internal): seat 가격 조회
  └── TossPayments (external): confirmPayment, cancelPayment

booking-app
  ├── concert-app (internal): seat hold / release / confirm
  └── waitingroom-app (internal): token validate / consume

concert-app, waitingroom-app, user-app
  └── 다른 서비스를 호출하지 않음 (leaf service)
```

**의존성 방향 원칙:**
- payment-app → booking-app → concert-app (방향이 단방향)
- concert-app과 waitingroom-app은 다른 서비스에 의존하지 않음
- 역방향 호출 없음 (순환 의존 없음)

### 핵심 사용자 흐름

```
1. POST /api/v1/waiting-room/join           → waitingroom-app: Redis Sorted Set 진입
2. GET  /api/v1/waiting-room/status         → waitingroom-app: 순번 통과 시 ACTIVE token 발급
3. POST /api/v1/reservations                → booking-app:
     ├── token validate  → waitingroom-app (internal)
     ├── seat HOLD       → concert-app (internal, optimistic lock)
     ├── reservation 저장 (PENDING, TTL 5분)
     └── token consume   → waitingroom-app (internal)
4. POST /api/v1/payments/request            → payment-app:
     ├── reservation 검증 → booking-app (internal)
     ├── seat 가격 조회    → concert-app (internal)
     └── Payment 저장 (READY)
5. [클라이언트가 TossPayments SDK로 결제 진행] → paymentKey 수령
6. POST /api/v1/payments/confirm            → payment-app:
     ├── TossPayments confirmPayment API 호출
     ├── Payment → APPROVED
     ├── reservation confirm → booking-app (internal)
     │     └── seat SOLD    → concert-app (internal)
     └── 실패 시: TossPayments cancelPayment (보상)
```

### 서비스 간 통신 방식

| 호출 방향 | 방식 | 타임아웃 | 에러 처리 |
|-----------|------|---------|----------|
| SCG → 서비스 | Spring Cloud Gateway 라우팅 | — | circuit breaker (planned) |
| 서비스 → 서비스 | RestClient (동기 HTTP) | connect 3s / read 10s | HttpClientErrorException 매핑 |
| 서비스 → TossPayments | RestClient (동기 HTTP) | connect 3s / read 10s | BusinessException(PAYMENT_PG_ERROR) |

**왜 RestClient(동기)를 사용했는가:**
booking-app, concert-app 모두 동기 호출 패턴을 이미 사용하고 있습니다. 현재 단계에서 비동기(WebClient/메시지 큐)로 전환하면 에러 처리와 보상 트랜잭션 추적이 복잡해집니다. 정합성 요구사항이 높은 결제 흐름에서는 동기 흐름이 디버깅이 용이합니다.

---

## Trade-offs

| 결정 | 얻은 것 | 잃은 것 |
|------|---------|---------|
| 서비스별 DB 스키마 분리 | 배포/장애 독립성 | 서비스 간 JOIN 불가, 정합성은 애플리케이션 레이어에서 보장 |
| 동기 HTTP 호출 | 디버깅 용이, 단순한 에러 전파 | 하나의 서비스 응답 지연이 전체 흐름 지연으로 이어짐 |
| Saga Orchestration | payment-app이 흐름 전체 제어, 보상 로직 명확 | payment-app의 복잡도 증가, booking-app과 concert-app에 대한 의존 |
| Redis를 별도 노드로 분리 | DB 부하 분산, TTL 기반 만료 자동 처리 | Redis 장애 시 대기열/멱등성 기능 일부 영향 |

---

## Failure Scenarios

### 1. concert-app 장애 시
- booking-app: seat HOLD 실패 → reservation 저장 안 됨 (정상 실패)
- payment-app: seat 가격 조회 실패 → payment 생성 안 됨 (정상 실패)
- 사용자에게 503 반환

### 2. booking-app 장애 시 (PG 승인 후)
- payment-app이 TossPayments confirm 성공 후 booking confirm 호출 실패
- payment-app의 `initiateRefund`가 TossPayments cancel 호출 (보상 트랜잭션)
- 결제 취소 성공 시: Payment → REFUNDED
- 결제 취소마저 실패: Payment → CANCEL_FAILED, `[CRITICAL]` 로그 발생

### 3. Redis 장애 시
- waitingroom-app: 대기열 기능 중단
- payment-app: idempotency 키 검증 불가 → DB UK(reservation_id, order_id)가 2차 방어
- 멱등성이 완벽하게 보장되지 않는 구간이 생기므로 Redis 가용성 모니터링이 중요

### 4. reservation TTL 만료 경합
- booking-app의 만료 스케줄러가 5분마다 실행
- payment-app이 confirm 호출 직전에 reservation이 만료될 수 있음
- booking-app의 `confirmReservation` 내부에서 `PENDING + not expired` 검증이 있어 이중 방어됨

---

## Observability

현재 구현된 항목:
- **traceId/spanId**: `micrometer-tracing-bridge-brave` (common-module 포함) → MDC 자동 주입
- **로그 패턴**: `[traceId, spanId]` 포함 (`payment-app/application.properties` 설정)
- **Prometheus 메트릭**: `/actuator/prometheus` (각 서비스 노출)
- **Grafana 대시보드**: `management.metrics.tags.application` 태그로 서비스별 필터링

계획 중 (planned):
- Jaeger로 분산 트레이스 전체 흐름 시각화 (OTLP exporter 설정)
- CANCEL_FAILED 상태 발생 시 Alertmanager 알림
- k6 부하 테스트 후 P99 레이턴시 측정

---

## Interview Explanation (90s version)

> 이 시스템은 6개 서비스로 구성된 MSA 기반 티켓팅 플랫폼입니다. 서비스를 분리한 핵심 이유는 세 가지입니다. 첫째, waitingroom-app을 분리해 오픈 직후 트래픽 폭증 시 이 서비스만 스케일아웃합니다. 둘째, 좌석 중복 예약 방지를 concert-app의 optimistic lock으로 국소화해서 다른 서비스 트랜잭션에 영향을 주지 않습니다. 셋째, 결제 흐름을 payment-app이 단독으로 오케스트레이션하면서 "돈이 빠져나갔는데 예약이 안 된" 상황을 보상 트랜잭션으로 처리합니다. 서비스 간 통신은 내부 REST API를 사용하고, 결제 정합성은 Saga Orchestration 패턴으로 보장합니다.

---

*최종 업데이트: 2026-03-16 | payment-app 구현 반영*
