---
title: "SLI / SLO 정의 및 Error Budget"
last_updated: 2026-03-19
author: "민석"
reviewer: ""
---

## 목차
- [Background](#background)
- [Problem](#problem)
- [Current Design](#current-design)
- [Measurement / Validation](#measurement-validation)
- [SLO 목표값 (proposed)](#slo-목표값-proposed)
- [Error Budget 계산 예시](#error-budget-계산-예시)
- [Failure / Bottleneck Scenarios](#failure-bottleneck-scenarios)
- [SLO 위반 시 대응 기준](#slo-위반-시-대응-기준)
- [현재 SLO 측정 불가 항목 (planned)](#현재-slo-측정-불가-항목-planned)
- [Trade-offs](#trade-offs)
- [Planned Improvements](#planned-improvements)

# SLI / SLO 정의 및 Error Budget

> **중요**: 이 문서에 나오는 모든 SLO 수치는 **(proposed)** 상태다.
> 현재 시스템에 공식적으로 합의되거나 운영에 적용된 SLO는 없다.
> Prometheus 쿼리는 현재 수집 중인 메트릭 기반으로 작성됐으나, SLO 준수 여부를 자동으로 알람하는 recording rule은 아직 구성되지 않았다.
>
> 관측성 스택 개요(Prometheus, Grafana, Jaeger, Alertmanager 설정)와 payment confirm 흐름의 trace 절차는 [`docs/observability/observability.md`](../observability/observability.md),
> payment 상태 머신과 트랜잭션 경계는 [`docs/services/payment/payment-architecture.md`](../services/payment/payment-architecture.md)를 참고한다.
> CANCEL_FAILED 인시던트 대응은 [`docs/operations/incident-runbook.md`](../operations/incident-runbook.md)를 참고한다.

---

## Background

티켓팅 시스템에서 "서비스가 잘 동작하고 있다"를 판단하려면 기준이 필요하다.
HTTP 200 응답률만 보면 실제로 결제가 성공하는지 알 수 없고,
P95 레이턴시만 보면 CANCEL_FAILED 같은 치명적 상태가 누적되고 있는지 알 수 없다.

SLI/SLO 프레임워크는 "무엇을 측정하는가(SLI)", "얼마나 잘해야 하는가(SLO)", "얼마나 실패해도 되는가(Error Budget)"를 명시적으로 정의한다.
이 문서는 현재 인프라(Redis single node, MySQL single instance, TossPayments 외부 의존)와 도메인 특성(결제 정합성, 좌석 선점 TTL 5분)에 맞게 SLI를 정의하고, 향후 SLO를 설정할 때 기준으로 삼을 수 있도록 작성됐다.

---

## Problem

현재 시스템에서 SLI/SLO 없이 답하기 어려운 질문들:

1. payment confirm 응답이 느려졌을 때 "언제부터 대응해야 하는가"의 기준이 없다.
2. 결제 APPROVED 비율이 95%에서 85%로 떨어졌을 때 이것이 허용 범위인지 아닌지 알 수 없다.
3. CANCEL_FAILED가 하루에 1건 발생했을 때 이게 P0인지 P2인지 합의되지 않았다.
4. 배포 후 "성능이 저하됐다"는 주관적 판단을 수치로 뒷받침할 방법이 없다.
5. 어느 서비스에 얼마나 투자해야 하는지 우선순위 근거가 없다.

---

## Current Design

### SLI vs SLO vs Error Budget 개념 명확화

| 용어 | 정의 | 이 시스템 예시 |
|------|------|-------------|
| **SLI** (Service Level Indicator) | 실제로 측정하는 지표. 구체적인 Prometheus 쿼리나 DB 쿼리로 표현 가능해야 한다. | `rate(http_server_requests_seconds_count{status!~"5..",service="payment-app"}[5m]) / rate(...)` |
| **SLO** (Service Level Objective) | SLI에 대한 목표값. 현재는 모두 **(proposed)** 상태. | payment /confirm availability > 99.9% (proposed) |
| **Error Budget** | `1 - SLO`. 이 범위 내에서는 실패해도 SLO 위반이 아니다. | 0.1% = 월 43분 다운타임 허용 (proposed) |

Error Budget의 핵심 의도: 완벽한 가용성보다 "얼마나 빠르게 배포하고 실험할 수 있는가"와 "얼마나 많이 실패할 수 있는가" 사이의 트레이드오프를 명시적으로 관리하는 것이다.

---

## Measurement / Validation

### Category 1: Availability (가용성)

**SLI 정의**: 전체 요청 중 5xx가 아닌 응답의 비율. 5분 rolling window 기준.

```promql
-- 서비스별 가용성 SLI (5분 window)
rate(http_server_requests_seconds_count{status!~"5.."}[5m])
/
rate(http_server_requests_seconds_count[5m])
```

**측정 대상**: 모든 서비스의 `/actuator/health` 응답 및 비즈니스 엔드포인트.

**주의**: 비즈니스 오류(409 SEAT_ALREADY_HELD, 409 PAYMENT_IDEMPOTENCY_CONFLICT)는 4xx이므로 가용성 SLI 계산에서 제외된다. 이것은 의도된 설계다. 4xx는 서비스가 정상적으로 비즈니스 규칙을 집행하고 있음을 나타낸다.

**현재 측정 가능 여부**: 가능. Spring Boot Actuator + Prometheus 수집 중.

---

### Category 2: Latency (지연)

**SLI 정의**: 엔드포인트별 P95/P99 응답 시간. 5분 rolling window 기준.

```promql
-- booking-app /reservations P95
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{
    uri="/api/v1/reservations",
    method="POST"
  }[5m])
)

-- payment-app /confirm P99
histogram_quantile(0.99,
  rate(http_server_requests_seconds_bucket{
    uri="/api/v1/payments/confirm",
    method="POST"
  }[5m])
)
```

**핵심 엔드포인트별 특성**:

| 엔드포인트 | 외부 호출 포함 여부 | 예상 P99 지배 요인 |
|-----------|-----------------|-----------------|
| `POST /waiting-room/join` | 없음 | Redis ZADD O(log N) |
| `POST /api/v1/reservations` | booking-app → concert-app (내부) | seat 낙관적 락 + DB save + 내부 HTTP 왕복 |
| `POST /api/v1/payments/request` | payment-app → booking-app, concert-app (내부) | booking 조회 + seat 가격 조회 + DB save |
| `POST /api/v1/payments/confirm` | payment-app → TossPayments (외부, read timeout 10s) | TossPayments 응답 시간 |

**현재 측정 가능 여부**: 가능. 단, 엔드포인트별로 Prometheus label이 정확히 수집되는지 확인이 필요하다.

---

### Category 3: Error Rate (오류율)

**SLI 정의 1**: 5xx 비율 (5분 window)

```promql
rate(http_server_requests_seconds_count{status=~"5.."}[5m])
/
rate(http_server_requests_seconds_count[5m])
```

**SLI 정의 2**: 결제 PG 오류(P005) 비율

P005는 TossPayments가 오류를 반환했을 때 발생한다.
이것은 5xx와 별개로 추적해야 한다. 시스템은 정상이지만 PG가 오류를 반환하는 경우가 있기 때문이다.

현재 P005는 application log에서만 확인 가능하다.
Micrometer counter를 추가하면 Prometheus에서 직접 쿼리할 수 있다 (Planned Improvements 참고).

**현재 측정 가능 여부**: 5xx는 가능. P005 비율은 현재 log 기반 수동 확인만 가능.

---

### Category 4: Business SLI (결제 성공률)

**SLI 정의 1**: Payment APPROVED 비율

```sql
-- 1시간 window 기준 결제 성공률
SELECT
  status,
  COUNT(*) AS count,
  COUNT(*) * 100.0 / SUM(COUNT(*)) OVER () AS pct
FROM ticketing_payment.payments
WHERE created_at >= NOW() - INTERVAL 1 HOUR
GROUP BY status;
```

이 SLI는 Prometheus 메트릭이 아니라 DB 쿼리 기반이다.
현재 Micrometer counter가 없으므로 Grafana에서 실시간으로 볼 수 없다.

**SLI 정의 2**: CANCEL_FAILED 발생 건수

```sql
-- CANCEL_FAILED 잔류 건수 (0이어야 함)
SELECT COUNT(*)
FROM ticketing_payment.payments
WHERE status = 'CANCEL_FAILED'
  AND updated_at >= NOW() - INTERVAL 24 HOUR;
```

CANCEL_FAILED는 "PG 승인은 됐지만 자동 환불도 실패한" 상태다.
이 상태의 결제는 고객 돈이 묶인 채 수동 처리를 기다린다.
단 1건이라도 발생하면 즉각 대응이 필요하다.
인시던트 대응 절차는 [`docs/operations/incident-runbook.md`](../operations/incident-runbook.md)를 참고한다.

**현재 측정 가능 여부**: DB 쿼리로 수동 측정 가능. Prometheus 연동은 planned.

---

## SLO 목표값 (proposed)

아래 표의 모든 SLO 수치는 현재 시스템에 합의되거나 적용된 값이 아니다.
인프라 제약(Redis single node, MySQL single instance, TossPayments sandbox)과 도메인 요구사항을 바탕으로 제안한 초기값이다.
실제 부하 테스트 결과와 운영 데이터를 기반으로 보정해야 한다.

| 서비스 / 엔드포인트 | SLI 종류 | SLO 목표 (proposed) | Error Budget (월 기준) | 측정 방법 |
|-------------------|---------|-------------------|----------------------|---------|
| booking-app `POST /reservations` | Availability | 99.5% | 월 3.6시간 다운 허용 | Prometheus `http_server_requests_seconds_count` |
| booking-app `POST /reservations` | Latency P95 | < 800ms | — | Prometheus `histogram_quantile(0.95, ...)` |
| payment-app `POST /confirm` | Availability | 99.9% (proposed) | 월 43분 다운 허용 | Prometheus `http_server_requests_seconds_count` |
| payment-app `POST /confirm` | Latency P95 | < 5000ms (proposed) | — | Prometheus `histogram_quantile(0.95, ...)` (TossPayments 포함) |
| payment APPROVED 비율 | Business | > 95% (PG 정상 시) (proposed) | — | DB: `COUNT(*) WHERE status='APPROVED'` / 전체 건수 |
| CANCEL_FAILED 발생 건수 | Business | 0건/일 (proposed) | 1건 = 즉시 SLO 위반 | DB: `COUNT(*) WHERE status='CANCEL_FAILED'` |
| waitingroom-app `POST /join` | Availability | 99.5% (proposed) | 월 3.6시간 다운 허용 | Prometheus `http_server_requests_seconds_count` |
| waitingroom-app `POST /join` | Latency P95 | < 200ms (proposed) | — | Prometheus `histogram_quantile(0.95, ...)` |
| concert-app `GET /events/{eventId}` | Availability | 99.5% (proposed) | 월 3.6시간 다운 허용 | Prometheus `http_server_requests_seconds_count` |
| concert-app `GET /events/{eventId}` | Latency P95 | < 100ms (proposed) | — | Prometheus `histogram_quantile(0.95, ...)` (Caffeine L1 캐시 hit 시 수 ms) |
| concert-app `GET /seats/available/{scheduleId}` | Latency P95 | < 300ms (proposed) | — | Prometheus `histogram_quantile(0.95, ...)` (AVAILABLE 좌석 조회, DB 직접 쿼리) |
| user-app `POST /signup` | Availability | 99.0% (proposed) | 월 7.2시간 다운 허용 | Prometheus `http_server_requests_seconds_count` |
| user-app `POST /signup` | Latency P95 | < 200ms (proposed) | — | Prometheus `histogram_quantile(0.95, ...)` (DB UK 이메일 중복 체크 포함) |
| scg-app (라우팅 오버헤드) | Latency P99 | < 50ms overhead (proposed) | — | Grafana: `spring_cloud_gateway_requests_seconds` vs upstream P99 차이 |

**왜 payment /confirm의 SLO가 다른 서비스보다 엄격한가**:
payment-app은 고객 자금과 직결된다. 가용성 99.9%는 월 43분의 다운타임 허용을 의미하는데, 이 43분 동안 결제가 불가능하면 티켓팅 이벤트 기간 중 치명적이다. 반면 booking-app의 99.5%는 기술적으로 덜 엄격하지만, payment-app이 booking-app에 의존하므로 booking-app 다운이 payment-app에 연쇄된다는 점에서 실질적으로 payment SLO가 binding constraint다.

**concert-app SLO 설계 근거**:
- `GET /events/{eventId}` availability: 99.5% (proposed). 공연 조회 불가는 예약 불가로 이어지지만, booking-app이 concert-app에 의존하므로 booking SLO가 indirect upper bound다.
- `GET /events/{eventId}` latency P95 < 100ms: Caffeine L1 캐시 적중 시 수 ms가 가능하므로 타이트하게 설정. cold start나 캐시 eviction 시 일시적 스파이크는 허용.
- `GET /seats/available/{scheduleId}` latency P95 < 300ms: 캐시 없이 DB 직접 조회하는 엔드포인트. AVAILABLE 조건 인덱스(`idx_seat_schedule_status_no` 권장) 유무에 따라 크게 달라짐.

**user-app SLO 설계 근거**:
- Availability 99.0%: user-app은 현재 회원 가입과 조회만 있어 직접적인 결제/예약 흐름에 포함되지 않는다. 로그인 API가 없으므로 피크 트래픽에서 집중적으로 호출되지 않아 SLO를 다소 느슨하게 설정했다.
- Latency P95 < 200ms: DB UK 이메일 중복 체크(existsByEmail 쿼리)가 P95를 지배한다. email 컬럼에 UK 인덱스가 있으므로 정상 부하에서는 충분히 달성 가능하다.
- **주의**: 로그인 API와 BCrypt 인증이 구현(planned)되면 signup latency가 BCrypt cost factor(기본 10 → ~100ms 해싱)만큼 증가하므로 SLO 재검토가 필요하다.

---

## Error Budget 계산 예시

### Payment confirm availability SLO = 99.9% (proposed)

```
SLO = 99.9%
Error budget = 1 - 0.999 = 0.001 = 0.1%

월 총 시간: 30일 × 24시간 × 60분 = 43,200분
Error budget = 43,200 × 0.001 = 43.2분/월
```

즉, payment /confirm이 한 달에 43분 이하로 5xx를 반환하면 SLO를 준수한 것이다.

### CANCEL_FAILED 1건 = Error Budget 소진 시나리오

CANCEL_FAILED의 SLO는 0건/일이다.
1건이 발생하면 즉시 SLO 위반이고 Error Budget 소진이다.

CANCEL_FAILED 시나리오: PG 승인 완료 → booking confirm API 실패 → 보상 취소(PG 환불) 시도 → 환불도 실패 → CANCEL_FAILED 잔류.
이 상태는 고객 돈이 묶인 채 어떤 자동화 로직도 처리하지 못한다. 수동 개입이 반드시 필요하다.

이 시나리오가 Error Budget에서 별도로 관리되어야 하는 이유는 발생 빈도가 낮아도 영향도가 절대적이기 때문이다.
availability 99.9% SLO와 CANCEL_FAILED 0건 SLO는 서로 독립적으로 추적한다.

---

## Failure / Bottleneck Scenarios

SLO 위반으로 이어질 수 있는 시나리오와 예상 영향:

| 시나리오 | 영향받는 SLO | 예상 Error Budget 소진 속도 | 감지 방법 |
|---------|------------|--------------------------|---------|
| MySQL single instance 장애 | booking, payment, concert, user availability SLO 즉시 위반 | 빠름 (수분 내 전체 소진) | `hikaricp.connections.active` = 0, 5xx 급증 |
| Redis single node 장애 | waitingroom availability SLO, payment idempotency 불가 | 빠름 | Redis `PING` 실패, waitingroom join 5xx |
| TossPayments 지연 (P99 급등) | payment /confirm latency SLO 위반 | 중간 (지속 시간에 비례) | Jaeger `toss-confirm` span duration 급증 |
| HikariCP 고갈 (booking-app) | booking /reservations latency SLO 위반 | 중간 | `hikaricp.connections.pending` 지속 상승 |
| CANCEL_FAILED 발생 | CANCEL_FAILED 0건 SLO 즉시 위반 | 즉시 | DB 쿼리, CRITICAL 로그 |
| concert-app optimistic lock 충돌 폭증 | concert latency SLO 영향 없음 (409는 4xx) — seat HOLD 5xx 급증 시 영향 | 중간 (5xx 전환 시) | INC-008 절차, `seat.hold.conflict` 비율 |
| concert-app Caffeine 캐시 미히트 (cold start) | concert `GET /events/{eventId}` latency SLO 위반 가능 | 느림 (첫 요청 후 안정화) | p95 스파이크 후 자동 복구 |
| user-app DB 커넥션 고갈 | user availability SLO 위반 | 중간 | `hikaricp.connections.pending{application="user-service"}` |
| optimistic lock 충돌률 80% 이상 | SLO 위반 아님 — 정상 동작 | 없음 | S001 에러 비율 모니터링 (경보 불필요) |

**optimistic lock 충돌이 SLO 위반이 아닌 이유**: 409 SEAT_ALREADY_HELD는 비즈니스 규칙 집행 결과다. 100명 중 99명이 409를 받아도 서비스는 정상적으로 동작하고 있다. 이것을 오류로 분류하면 SLO가 의미 없어진다.

---

## SLO 위반 시 대응 기준

| 조건 | 심각도 | 대응 시간 목표 | 근거 |
|------|--------|------------|------|
| CANCEL_FAILED > 0건 (24시간 window) | P0 | 즉시 | 고객 자금 묶임. 수동 환불 처리 필요. |
| payment 5xx > 3% (5분 window) | P1 | 5분 이내 | payment availability SLO 위반 임박 또는 이미 위반. |
| booking P95 > 2000ms (지속 5분 이상) | P2 | 30분 이내 | 사용자 경험 저하. HikariCP 또는 DB 조회 성능 확인 필요. |
| payment APPROVED 비율 < 90% (1시간 window) | P1 | 15분 이내 | 95% SLO 위반 진행 중. PG 상태 확인 필요. |
| waitingroom join 5xx > 1% (5분 window) | P1 | 5분 이내 | waitingroom availability SLO 위반. Redis 장애 연동 가능성 높음. |
| concert `GET /events/{eventId}` P95 > 500ms (지속 5분) | P2 | 30분 이내 | latency SLO 위반. Caffeine 캐시 미히트 또는 DB 부하. |
| concert seat HOLD 5xx > 5% (5분 window) | P1 | 10분 이내 | optimistic lock 충돌이 아닌 서버 오류. INC-008 절차 참조. |
| user signup 5xx > 2% (5분 window) | P2 | 30분 이내 | user availability SLO 위반. DB 커넥션 또는 UK 위반 외 서버 오류 확인. |
| seat hold 409율 > 80% | 모니터링 | 대응 불필요 | 정상적인 경쟁 상황. SLO 위반 아님. |
| Redis latency > 10ms 지속 | P2 | 30분 이내 | waitingroom latency SLO 위반 위험. Redis 메모리/커넥션 풀 확인. |

**대응 절차**: 각 심각도별 상세 조사 절차는 [`docs/operations/incident-runbook.md`](../operations/incident-runbook.md)를 참고한다.

---

## 현재 SLO 측정 불가 항목 (planned)

다음 항목들은 SLI로 정의됐으나 현재 자동 측정이 불가능하다.

### 1. Micrometer custom counter for Business SLI

payment APPROVED 비율과 CANCEL_FAILED 건수를 Prometheus에서 실시간으로 추적하려면 Micrometer counter가 필요하다.

추가가 필요한 지점 (payment-app):
<!-- 2026-03-18 메트릭 명칭 통일 -->
- `PaymentService.confirmPayment()` 성공 시: `Counter("payment.confirm.total", "result", "success")` increment
- `PaymentService.confirmPayment()` PG 오류 시: `Counter("payment.confirm.total", "result", "pg_error")` increment
- CANCEL_FAILED 전이 시: `Counter("payment.confirm.total", "result", "cancel_failed")` increment
- booking confirm 실패 → 보상 트리거 시: `Counter("payment.confirm.total", "result", "booking_failed")` increment

이 counter들이 추가되면 다음 Prometheus 쿼리로 Business SLI를 실시간 측정할 수 있다:

<!-- 2026-03-18 메트릭 명칭 통일 -->
```promql
-- APPROVED 비율 (5분 window)
rate(payment_confirm_total{result="success"}[5m])
/
(rate(payment_confirm_total{result="success"}[5m]) + rate(payment_confirm_total{result="pg_error"}[5m]))

-- CANCEL_FAILED 건수 (1시간 window)
increase(payment_confirm_total{result="cancel_failed"}[1h])

-- booking confirm 실패로 인한 보상 취소 횟수 (1시간 window)
increase(payment_confirm_total{result="booking_failed"}[1h])
```

### 2-A. Micrometer counter for Idempotency SLI

결제 중복 방지 효과를 측정하려면 idempotency cache hit/conflict 횟수를 추적해야 한다.

추가가 필요한 지점 (payment-app `IdempotencyManager`):
- cache hit(이미 처리된 요청 재수신) 시: `Counter("payment.idempotency.hit")` increment
- PROCESSING 상태 충돌(동시 중복 요청) 시: `Counter("payment.idempotency.conflict")` increment

```promql
-- idempotency cache hit 비율 (재시도 트래픽 비율 모니터링)
rate(payment_idempotency_hit_total[5m])

-- 동시 중복 요청 충돌 빈도
rate(payment_idempotency_conflict_total[5m])
```

**측정 의의**: cache hit 비율이 높으면 클라이언트 재시도가 많다는 신호다. conflict가 급증하면 동시 중복 클릭 또는 네트워크 재전송이 폭발적으로 늘어난 것을 의미한다. 두 지표를 조합하면 멱등성 방어가 실제로 얼마나 작동하는지 수치로 확인할 수 있다.

**현재 측정 가능 여부**: 현재 불가. `IdempotencyManager` 내 로그만 존재. Micrometer counter 추가 필요 (planned).

### 2-B. Histogram for PG API duration

TossPayments `confirmPayment` / `cancelPayment` 외부 API 응답 시간을 histogram으로 수집한다.

추가가 필요한 지점 (payment-app `TossPaymentsClient`):
- `Timer("payment.pg.duration_seconds", "operation", "confirm/cancel")` — 외부 API 호출 전후 측정

```promql
-- TossPayments confirm P95 응답 시간 (5분 window)
histogram_quantile(0.95,
  rate(payment_pg_duration_seconds_bucket{operation="confirm"}[5m])
)

-- TossPayments cancel P95 응답 시간
histogram_quantile(0.95,
  rate(payment_pg_duration_seconds_bucket{operation="cancel"}[5m])
)
```

**측정 의의**: payment /confirm의 P95가 5000ms를 초과할 때 그 원인이 TossPayments 응답 지연인지 내부 처리 지연인지 분리해서 볼 수 있다. `payment.pg.duration_seconds`가 높으면 PG 장애 또는 네트워크 이슈이고, 낮으면 내부 DB/Redis 병목이다.

**현재 측정 가능 여부**: 현재 불가. `TossPaymentsClient` 호출부에 Timer 추가 필요 (planned).

### 2. SLO burn rate alert (Prometheus recording rules)

단순 임계값 알람("5xx > 3%이면 경보")은 Error Budget 소진 속도를 반영하지 못한다.
SLO burn rate alert는 "현재 속도로 계속되면 Error Budget이 언제 소진되는가"를 기반으로 알람을 발생시킨다.

예시 (payment /confirm availability 99.9% SLO 기준):

```yaml
# recording rule
- record: job:payment_confirm_error_rate:ratio_rate5m
  expr: |
    rate(http_server_requests_seconds_count{status=~"5..", uri="/api/v1/payments/confirm"}[5m])
    /
    rate(http_server_requests_seconds_count{uri="/api/v1/payments/confirm"}[5m])

# burn rate alert (1시간 window에서 14.4배 burn → 월 budget의 2%를 1시간에 소진)
- alert: PaymentConfirmSLOBurnRate
  expr: job:payment_confirm_error_rate:ratio_rate5m > 14.4 * 0.001
  for: 5m
  labels:
    severity: P1
```

### 3. SLO dashboard in Grafana

별도의 SLO 대시보드가 필요하다. 현재 [`docs/observability/observability.md`](../observability/observability.md)의 Grafana panel들은 지표 모니터링 목적이지 SLO 준수 현황을 한눈에 볼 수 없다.

필요한 panel:
- 서비스별 30일 rolling availability (SLO 목표선 포함)
- Error Budget 잔여량 (% 및 시간으로 표현)
- payment APPROVED/FAILED/CANCEL_FAILED 비율 시계열
- Business SLI (APPROVED 비율) 7일 트렌드

---

## Trade-offs

### 현재 SLO (proposed)의 한계

**인프라 단일점 문제**: MySQL single instance, Redis single node로 운영하므로 availability SLO 99.9% 달성이 인프라 이중화 없이는 어렵다. 한 번의 MySQL 재시작만으로도 수분간 5xx가 발생하고 월 43분 Error Budget을 상당 부분 소진할 수 있다. 현재 SLO는 인프라 한계를 인정한 상태에서의 aspirational target이다.

**TossPayments 외부 의존**: payment /confirm의 P95 < 5000ms SLO는 TossPayments 응답 시간에 강하게 의존한다. PG가 느려지면 시스템이 정상이어도 SLO를 위반하게 된다. 이는 외부 의존성을 가진 서비스의 SLO 설정에서 피할 수 없는 트레이드오프다.

**Business SLI의 측정 지연**: Payment APPROVED 비율은 현재 DB 쿼리로만 측정 가능하다. Prometheus에서 실시간으로 볼 수 없으므로 SLO 위반을 즉각 감지하는 것이 어렵다.

### 왜 booking-app SLO (99.5%)가 payment-app (99.9%)보다 낮은가

payment-app은 금전적 영향이 직접적이므로 더 엄격한 SLO가 적합하다.
booking-app은 좌석 선점을 담당하는데, 선점 실패는 사용자 불편이지만 금전적 피해는 없다.
다만 booking-app이 payment-app의 upstream dependency이므로, booking-app이 다운되면 payment도 불가능해진다는 점에서 실질적 가용성은 연동된다.

---

## Planned Improvements

1. **Micrometer counter 등록**: payment-app에 `payment.confirm.total{result="success/pg_error/cancel_failed/booking_failed"}` 단일 counter(Prometheus 노출명: `payment_confirm_total`)를 추가해 Business SLI를 Prometheus에서 실시간 측정한다. `booking_failed`는 booking confirm 실패로 보상 취소가 트리거된 횟수를 의미한다. <!-- 2026-03-18 메트릭 명칭 통일 -->
1-A. **Idempotency 메트릭 등록**: `payment.idempotency.hit` (cache hit 횟수)와 `payment.idempotency.conflict` (PROCESSING 충돌 횟수)를 `IdempotencyManager`에 추가해 멱등성 방어 실효성을 수치로 추적한다.
1-B. **PG 응답 시간 histogram 등록**: `payment.pg.duration_seconds{operation="confirm/cancel"}`를 `TossPaymentsClient` 호출부에 추가해 내부 처리 지연과 TossPayments 응답 지연을 분리 측정한다.
2. **SLO burn rate recording rules**: Prometheus alerting rule을 구성해 Error Budget 소진 속도 기반 알람을 Alertmanager로 전달한다.
3. **Grafana SLO 대시보드**: 서비스별 availability, latency P95/P99, Error Budget 잔여량을 한 화면에서 확인할 수 있는 전용 대시보드를 구성한다.
4. **SLO 수치 재검토**: k6 부하 테스트([`docs/performance/performance-test-runbook.md`](./performance-test-runbook.md)) 결과와 실제 운영 데이터 축적 이후, proposed 수치의 현실성을 검증하고 보정한다.
5. **외부 의존성 SLO 분리**: TossPayments 응답 시간을 별도 SLI로 추적해, "시스템 자체 레이턴시 SLO"와 "PG 포함 레이턴시 SLO"를 구분한다. PG 장애 시 시스템 SLO와 E2E SLO를 독립적으로 판단할 수 있게 된다.

---
