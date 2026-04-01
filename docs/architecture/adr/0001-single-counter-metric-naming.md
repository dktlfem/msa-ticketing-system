---
title: "ADR 0001 — 결제 메트릭 단일 카운터 + result 레이블 패턴 채택"
last_updated: "2026-03-18"
author: "민석"
reviewer: ""
---

## 목차

- [상태](#상태)
- [컨텍스트](#컨텍스트)
- [결정](#결정)
- [결과](#결과)
- [참고 자료](#참고-자료)

---

# ADR 0001 — 결제 메트릭 단일 카운터 + result 레이블 패턴 채택

## 상태

> **Accepted**

**날짜**: 2026-03-18

---

## 컨텍스트

세 문서(`sli-slo.md`, `observability.md`, `incident-runbook.md`)가 동일한 결제 confirm 흐름을
서로 다른 메트릭 명칭으로 참조하고 있었다.

| 문서 | 기존 메트릭 명칭 | 문제 |
|------|----------------|------|
| `docs/observability/observability.md` | `payment.confirm.total{result="success/pg_error/booking_failed/cancel_failed"}` | 기준 문서 — 단일 counter + result 레이블 |
| `docs/performance/sli-slo.md` | `payment.approved`, `payment.pg_error`, `payment.cancel_failed` (독립 counter 3개) | PromQL 불일치: 각각 별도 metric 이름 |
| `docs/operations/incident-runbook.md` | `payment_confirm_total{result="cancel_failed"}` | ✅ 이미 `observability.md`와 일치 |

**핵심 문제**: `sli-slo.md`가 독립 counter 3개를 사용함에 따라,
Prometheus Alertmanager 규칙을 작성할 때 `observability.md`의 PromQL과 직접 호환되지 않고,
실제 코드에서도 `MeterRegistry`에 등록하는 카운터 이름이 불일치하여 오염된 메트릭 데이터가 발생할 위험이 있었다.

### 고려한 대안

| 대안 | 설명 | 미채택 이유 |
|------|------|-----------|
| 독립 counter 유지 | `payment_approved_total`, `payment_pg_error_total`, `payment_cancel_failed_total` 3개 유지 | 전체 confirm 시도 횟수를 단일 쿼리로 집계 불가, 레이블 축 추가 시 metric 수 증가 |
| Histogram 단일 사용 | 결제 결과를 Histogram의 버킷으로 표현 | 이산 카테고리(성공/실패 분류)에 Histogram은 부적합 |
| **단일 counter + result 레이블** | `payment.confirm.total{result=...}` 1개 | **채택** — 기존 `observability.md` 기준에 부합, PromQL 집계 단순화 |

---

## 결정

**결제 confirm 흐름의 모든 결과를 단일 Micrometer Counter `payment.confirm.total`에 `result` 레이블로 기록한다.**

### 메트릭 명칭 대응표 (Micrometer → Prometheus)

| Micrometer 등록명 (코드) | Prometheus 노출명 (PromQL) | 의미 |
|--------------------------|---------------------------|------|
| `payment.confirm.total` + `result=success` | `payment_confirm_total{result="success"}` | confirm 성공 |
| `payment.confirm.total` + `result=pg_error` | `payment_confirm_total{result="pg_error"}` | PG 오류로 인한 실패 |
| `payment.confirm.total` + `result=booking_failed` | `payment_confirm_total{result="booking_failed"}` | booking confirm 실패 후 보상 트리거 |
| `payment.confirm.total` + `result=cancel_failed` | `payment_confirm_total{result="cancel_failed"}` | CANCEL_FAILED 상태 발생 |
| `payment.idempotency.hit` | `payment_idempotency_hit_total` | idempotency cache hit |
| `payment.idempotency.conflict` | `payment_idempotency_conflict_total` | PROCESSING 상태 충돌 |
| `payment.pg.duration_seconds` (Histogram) | `payment_pg_duration_seconds_{bucket,count,sum}` | TossPayments API 응답 시간 |

> **Micrometer → Prometheus 변환 규칙**: 점(`.`)은 언더스코어(`_`)로 변환, Counter 타입에는 `_total` 접미사 자동 추가.

### 코드 적용 예시

```java
// ADR-0001: 단일 counter + result 레이블 패턴
// 독립 counter 3개 대신 result 레이블 분기 → 전체 confirm 시도 횟수를 단일 쿼리로 집계 가능
meterRegistry.counter("payment.confirm.total", "result", "success").increment();
meterRegistry.counter("payment.confirm.total", "result", "pg_error").increment();
meterRegistry.counter("payment.confirm.total", "result", "cancel_failed").increment();
meterRegistry.counter("payment.confirm.total", "result", "booking_failed").increment();
```

### 표준 PromQL 패턴

```promql
# 전체 confirm 시도 TPS
sum(rate(payment_confirm_total[5m]))

# 결제 성공률 (SLO 측정용)
rate(payment_confirm_total{result="success"}[5m])
  / (rate(payment_confirm_total{result="success"}[5m]) + rate(payment_confirm_total{result="pg_error"}[5m]))

# CANCEL_FAILED 발생 건수 (Alert 기준)
increase(payment_confirm_total{result="cancel_failed"}[1h])
```

---

## 결과

### 긍정적 효과

- **PromQL 단순화**: 전체 confirm 시도 횟수를 `sum(rate(payment_confirm_total[5m]))` 한 줄로 집계 가능
- **확장성**: 새로운 result 값(`partial_success` 등) 추가 시 기존 쿼리/Alert 규칙 수정 불필요
- **문서 정합성**: `observability.md`(기준), `sli-slo.md`, `incident-runbook.md` 세 문서 모두 동일한 메트릭 명칭 사용
- **Alertmanager 일관성**: 기존 `PaymentCancelFailed` alert 규칙(`payment_confirm_total{result="cancel_failed"}`)과 정합

### 부정적 효과 / 트레이드오프

- **기존 코드 수정 필요**: 현재 `payment-app` 구현이 독립 counter를 사용하고 있다면 코드 변경 필요
- **히스토리 단절**: 기존 Prometheus TSDB에 저장된 `payment_approved_total` 등의 과거 데이터는 새 메트릭 이름으로 자동 이관되지 않음 (신규 메트릭 시작 시점 이전은 쿼리 불가)

### 변경된 파일 / 영향 범위

| 파일 | 변경 내용 |
|------|----------|
| `docs/performance/sli-slo.md` | 독립 counter 3개 → `payment.confirm.total{result=...}` 단일 counter 통일 (7곳 수정) |
| `docs/observability/observability.md` | 기준 문서 — 변경 없음 |
| `docs/operations/incident-runbook.md` | 이미 일치 — 변경 없음 |

---

## 참고 자료

- [`docs/observability/observability.md`](../../observability/observability.md) — 메트릭 명칭 기준 문서
- [`docs/performance/sli-slo.md`](../../performance/sli-slo.md) — 변경 적용 대상 문서
- [Prometheus Best Practices: Metric and Label Naming](https://prometheus.io/docs/practices/naming/)
- [Micrometer Concepts: Meters](https://micrometer.io/docs/concepts#_meters)
