# Payment Confirm Flow Observability Guide

> **범위**: 이 문서는 `payment-app`의 결제 confirm 흐름에 특화된 관측성 가이드다.
> 전체 스택 개요(메트릭 목록, Jaeger 도입 근거, Pinpoint 계획, 일반 로그 설계, 장애 조사 체크리스트)는
> `docs/05-observability.md`에 있으며 이 문서는 그 내용을 반복하지 않는다.

---

## Background

티켓팅 시스템에서 결제 confirm은 가장 복잡한 구간이다.
단일 요청 안에 외부 PG(TossPayments) 호출, 두 개의 internal service 호출(booking-app, concert-app),
Redis 기반 idempotency 체크, DB 상태 전이가 순차적으로 얽혀 있다.

어느 단계에서든 실패가 발생하면 사용자 돈과 예약 상태 간 정합성이 어긋날 수 있다.
이 구간을 빠르게 진단하려면 로그, 메트릭, 트레이스 세 시그널을 함께 봐야 한다.

---

## Problem

결제 confirm 흐름에서 발생하는 실제 문제들:

1. **PG 응답 지연/실패**: TossPayments가 응답하지 않거나 오류를 반환하면 payment 상태가 READY에 머문다.
2. **booking confirm 실패 후 정합성 깨짐**: PG 승인은 됐지만 booking-app이 CONFIRMED로 전이하지 못하면 돈은 나갔는데 예약이 없는 상태가 된다.
3. **보상 취소 실패(CANCEL_FAILED)**: 위의 상황에서 자동 환불마저 실패하면 고객 돈이 묶인다. 수동 개입이 필요하다.
4. **멱등성 충돌**: 네트워크 재시도나 사용자 중복 클릭이 동시 처리되면 의도치 않은 이중 결제가 발생할 수 있다.

---

## Current Design

### traceId vs correlationId - 중요한 구분

이 두 식별자는 서로 다른 시스템에서 생성되며 목적도 다르다.

| 식별자 | 생성 주체 | 전파 방식 | 용도 |
|---|---|---|---|
| `traceId` | Micrometer Tracing (자동) | W3C TraceContext HTTP header → MDC 자동 주입 | Jaeger span 연결, 로그-트레이스 연결 |
| `correlationId` | `scg-app` GatewayAccessLogGlobalFilter | `X-Correlation-Id` HTTP header | 게이트웨이 레벨 요청 추적, Kibana 로그 검색 |

`traceId`는 OpenTelemetry/Micrometer가 자동으로 MDC에 주입하기 때문에 로그 패턴 `%X{traceId:-no-trace}`로 출력된다.
`correlationId`는 SCG가 진입 시점에 UUID를 생성해 헤더로 붙이며, 각 서비스가 이 헤더를 읽어 로그에 포함해야 한다.

실제 payment-app 로그 패턴:
```
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId:-no-trace},%X{spanId:-no-span}] [%thread] %-5level %logger{36} - %msg%n
```

예시 출력:
```
2026-03-16 14:32:01.123 [abc123def456,span789] [http-nio-8084-exec-3] INFO  c.k.c.i.manager.PaymentManager - Payment created - paymentId=42, reservationId=10, orderId=RES10_1742097121123, amount=150000
```

### 결제 confirm 3가지 흐름

#### 흐름 A: 정상 결제 confirm 성공

```
클라이언트
  → [POST /api/v1/payments/confirm]
  → scg-app (Idempotency-Key 헤더 확인, correlationId 부착)
  → payment-app

payment-app 처리 순서:
1. IdempotencyManager.startProcessing(key)   → Redis SETNX payment:idempotency:{key} PROCESSING
2. PaymentReader.readByOrderId(orderId)       → DB 조회
3. PaymentValidator.validateConfirmable()     → status == READY 검증
4. PaymentValidator.validateAmount()          → 금액 일치 검증
5. TossPaymentsClient.confirmPayment()        → POST https://api.tosspayments.com/v1/payments/confirm
6. PaymentWriter.updateToApproved()           → DB status = APPROVED, paymentKey 저장
7. BookingInternalClient.confirmReservation() → POST /internal/v1/reservations/{id}/confirm
8. IdempotencyManager.complete(key, response) → Redis SET payment:idempotency:{key} {responseJson}
```

로그 시퀀스 (INFO):
```
Payment created - paymentId=42, reservationId=10, orderId=RES10_1742097121123, amount=150000
TossPayments confirm request - orderId=RES10_1742097121123, amount=150000
TossPayments confirm success - paymentKey=tviva_20260316_abc, method=카드
Payment approved - paymentId=42, orderId=RES10_1742097121123
Reservation confirmed - reservationId=10, paymentId=42
```

#### 흐름 B: booking confirm 실패 → 보상 취소 성공

```
... (흐름 A의 1~6 단계 완료, DB는 APPROVED)
7. BookingInternalClient.confirmReservation() → 실패 (booking-app 장애 or 5xx)
8. initiateRefund() 호출
9. TossPaymentsClient.cancelPayment()         → POST /v1/payments/{paymentKey}/cancel
10. PaymentWriter.updateToRefunded()           → DB status = REFUNDED
```

로그 시퀀스:
```
Payment approved - paymentId=42, orderId=RES10_1742097121123
ERROR: Reservation confirm failed - reservationId=10, initiating refund
INFO:  Payment refunded - paymentId=42, paymentKey=tviva_20260316_abc
```

최종 상태: payment.status = REFUNDED, reservation.status = PENDING (CONFIRMED 아님)

#### 흐름 C: CANCEL_FAILED 발생 (최고 심각도)

```
... (흐름 B에서 보상 취소 시도)
9. TossPaymentsClient.cancelPayment() → 실패 (PG 장애 or 네트워크 타임아웃)
10. PaymentWriter.updateToCancelFailed() → DB status = CANCEL_FAILED
```

로그 시퀀스:
```
Payment approved - paymentId=42, orderId=RES10_1742097121123
ERROR: Reservation confirm failed - reservationId=10, initiating refund
ERROR: [CRITICAL] Payment cancel failed - paymentId=42, paymentKey=tviva_20260316_abc - MANUAL INTERVENTION REQUIRED
```

최종 상태: payment.status = CANCEL_FAILED, 고객 돈 환불 안 됨. **즉시 수동 개입 필요.**

### Idempotency 설계

Redis key: `payment:idempotency:{idempotencyKey}` (String, TTL 24h)

상태 전이:
```
존재하지 않음 → SETNX → "PROCESSING" (TTL 24h)
처리 완료     → SET    → "{responseJson}" (TTL 24h)
```

Redis 장애 시 2차 방어: `ticketing_payment.payments` 테이블의 UK(`uk_reservation_id`, `uk_order_id`)가
DB 레벨에서 중복 레코드를 방지한다. 단, PG 이중 호출은 막을 수 없다.

### Jaeger OTLP 설정 (planned)

`scg-app`이 OTLP HTTP endpoint(`http://jaeger:4318/v1/traces`)로 trace를 export하도록 설계되어 있다.
각 서비스의 실제 OTLP 연결 여부는 확인 중이며, 본 문서 작성 시점 기준으로 설정 완료가 확인된 구간만
"설정됨"으로 표기한다. Jaeger UI(`:16686`)에서 service 목록이 나타나지 않으면 OTLP export 설정을 먼저 점검해야 한다.

---

## Operational Procedure

### Metrics

현재 payment-app에서 수집되는 메트릭은 Spring Boot Actuator 자동 메트릭이다.
아래 커스텀 결제 메트릭은 도입 예정이다 **(planned)**.

| 메트릭 이름 (planned) | 타입 | 설명 |
|---|---|---|
| `payment.confirm.total{result="success"}` | Counter | 결제 confirm 성공 횟수 |
| `payment.confirm.total{result="pg_error"}` | Counter | PG 오류로 인한 실패 횟수 |
| `payment.confirm.total{result="booking_failed"}` | Counter | booking confirm 실패로 인한 보상 트리거 횟수 |
| `payment.confirm.total{result="cancel_failed"}` | Counter | CANCEL_FAILED 발생 횟수 |
| `payment.idempotency.hit` | Counter | idempotency cache hit 횟수 |
| `payment.idempotency.conflict` | Counter | PROCESSING 상태 충돌 횟수 |
| `payment.pg.duration_seconds` | Histogram | TossPayments API 응답 시간 |

현재 사용 가능한 메트릭으로 결제 상태를 모니터링하는 방법:

**Grafana PromQL - payment-app HTTP 오류율:**
```promql
rate(http_server_requests_seconds_count{application="payment-app", status=~"5.."}[5m])
/ rate(http_server_requests_seconds_count{application="payment-app"}[5m])
```

**Grafana PromQL - confirm 엔드포인트 p95 응답 시간:**
```promql
histogram_quantile(0.95,
  rate(http_server_requests_seconds_bucket{
    application="payment-app",
    uri="/api/v1/payments/confirm"
  }[5m])
)
```

**Grafana PromQL - SCG에서 payment-service 라우트 5xx 비율:**
```promql
rate(spring_cloud_gateway_requests_seconds_count{routeId="payment-service", status=~"5.."}[5m])
```

### Logs

**Kibana에서 payment confirm 흐름을 찾는 실제 쿼리:**

1. 특정 orderId의 전체 로그 흐름 추적:
```
message:"RES10_1742097121123"
```

2. PG 실패 로그 전체 조회:
```
message:"TossPayments confirm failed"
```

3. 보상 취소 트리거 로그 조회:
```
message:"Reservation confirm failed" AND message:"initiating refund"
```

4. CRITICAL 수동 개입 필요 로그 (최고 우선순위):
```
message:"[CRITICAL] Payment cancel failed"
```

5. idempotency 충돌 조회:
```
message:"Idempotency conflict"
```

6. idempotency cache hit 조회 (재시도 패턴 분석):
```
message:"Idempotency cache hit"
```

7. 특정 traceId로 payment-app 로그 전체 조회:
```
traceId:"abc123def456"
```

8. 특정 시간 범위의 결제 실패 집계 (KQL):
```
message:"Payment failed after PG rejection" AND @timestamp >= "2026-03-16T00:00:00" AND @timestamp < "2026-03-17T00:00:00"
```

9. reservationId로 결제 생성부터 완료까지 추적:
```
message:"reservationId=10"
```

**Kibana - 로그 필드 기준 검색 (Filebeat가 JSON 파싱을 하는 경우):**
```
level:"ERROR" AND logger:"PaymentManager"
```

### Traces

**Jaeger UI(`:16686`)에서 결제 흐름 추적:**

1. Service: `payment-app` 선택 → Operation: `POST /api/v1/payments/confirm`
2. 특정 traceId 직접 검색: `Search by Trace ID` 탭에 traceId 입력
3. Tags 필터: `error=true`로 실패 span만 조회
4. Min Duration: `3s` 이상으로 설정해 느린 confirm 추출

**결제 confirm 정상 span 구조 (설계 기준, OTLP 연결 확인 후 실제 검증 필요):**
```
payment-app: POST /api/v1/payments/confirm          [전체 duration]
  ├── redis: GET payment:idempotency:{key}           [~1ms]
  ├── redis: SETNX payment:idempotency:{key}         [~1ms]
  ├── mysql: SELECT payments WHERE order_id=...      [~5ms]
  ├── http: POST api.tosspayments.com/v1/payments/confirm  [2~10s]
  ├── mysql: UPDATE payments SET status='APPROVED'   [~5ms]
  ├── http: POST booking-app/internal/v1/reservations/{id}/confirm [~20ms]
  └── redis: SET payment:idempotency:{key}           [~1ms]
```

span에서 비정상 패턴:
- TossPayments HTTP span이 10s 근처면 타임아웃 직전 상황
- booking-app HTTP span에 `error=true` tag가 있으면 보상 트리거 발생
- span이 `mysql: UPDATE ... CANCEL_FAILED`로 끝나면 최고 우선순위 알림 필요

---

## Failure Scenarios

### 시나리오 1: TossPayments confirm 실패

**발생 조건**: PG가 4xx(카드 한도 초과, 잘못된 paymentKey 등) 또는 5xx를 반환하거나 10s read timeout 발생

**상태 전이**:
```
payment.status: READY → FAILED
reservation.status: PENDING (변화 없음)
좌석: HOLD (5분 TTL 내 자동 해제)
```

**로그**:
```
ERROR: TossPayments confirm failed - orderId=RES10_xxx, httpStatus=400, body={"code":"INVALID_CARD_COMPANY",...}
ERROR: Payment failed after PG rejection - orderId=RES10_xxx
```

**확인 쿼리**:
```sql
SELECT payment_id, order_id, status, fail_reason, created_at, updated_at
FROM ticketing_payment.payments
WHERE order_id = 'RES10_xxx';
```

### 시나리오 2: booking confirm 실패 → 보상 취소 성공

**발생 조건**: PG 승인 완료 후 booking-app 호출 실패 (booking-app 장애, 네트워크 단절, 예약 상태 불일치)

**상태 전이**:
```
payment.status: READY → APPROVED → REFUNDED
reservation.status: PENDING (변화 없음)
```

**로그**:
```
INFO:  Payment approved - paymentId=42, orderId=RES10_xxx
ERROR: Reservation confirm failed - reservationId=10, initiating refund
INFO:  Payment refunded - paymentId=42, paymentKey=tviva_xxx
```

### 시나리오 3: CANCEL_FAILED (최고 심각도)

**발생 조건**: booking confirm 실패 후 PG 취소 API마저 실패

**상태 전이**:
```
payment.status: READY → APPROVED → CANCEL_FAILED
reservation.status: PENDING
고객 돈: PG에서 승인 완료된 상태 (환불 안 됨)
```

**로그**:
```
INFO:  Payment approved - paymentId=42, orderId=RES10_xxx
ERROR: Reservation confirm failed - reservationId=10, initiating refund
ERROR: [CRITICAL] Payment cancel failed - paymentId=42, paymentKey=tviva_xxx - MANUAL INTERVENTION REQUIRED
```

---

## Observability / Detection

### 1. Kibana - CANCEL_FAILED 즉시 감지

Kibana에서 아래 쿼리를 저장된 검색(Saved Search)으로 등록하고 알림을 설정해야 한다:
```
message:"[CRITICAL] Payment cancel failed"
```
이 로그가 단 1건이라도 발생하면 즉각 수동 개입이 필요하다.

### 2. Grafana - payment 5xx 급증 패널

패널 이름: `payment-app 5xx rate`
```promql
sum(rate(http_server_requests_seconds_count{
  application="payment-app",
  status=~"5.."
}[5m]))
```
임계값: 1분 내 5회 이상이면 알람.

### 3. DB - CANCEL_FAILED 잔류 레코드 직접 조회

```sql
SELECT
    payment_id,
    reservation_id,
    user_id,
    order_id,
    payment_key,
    amount,
    status,
    fail_reason,
    created_at,
    updated_at
FROM ticketing_payment.payments
WHERE status = 'CANCEL_FAILED'
ORDER BY updated_at DESC;
```

### 4. DB - READY 상태로 30분 이상 잔류하는 결제 (PG timeout 의심)

```sql
SELECT
    payment_id,
    reservation_id,
    order_id,
    amount,
    status,
    created_at,
    TIMESTAMPDIFF(MINUTE, created_at, NOW()) AS minutes_elapsed
FROM ticketing_payment.payments
WHERE status = 'READY'
  AND created_at < DATE_SUB(NOW(), INTERVAL 30 MINUTE)
ORDER BY created_at ASC;
```

### 5. DB - 특정 reservationId의 결제 상태 확인

```sql
SELECT payment_id, reservation_id, order_id, payment_key, amount, status, fail_reason, approved_at, cancelled_at, created_at, updated_at
FROM ticketing_payment.payments
WHERE reservation_id = 10;
```

### 6. Redis - idempotency 상태 직접 확인

특정 키의 현재 상태 확인:
```bash
redis-cli -h 192.168.124.101 -p 6379 GET "payment:idempotency:{idempotencyKey}"
```

PROCESSING 상태로 멈춰있는 경우 (처리 중 crash 발생):
```bash
# 현재 값이 "PROCESSING"인지 확인
redis-cli -h 192.168.124.101 -p 6379 GET "payment:idempotency:my-idempotency-key-123"

# TTL 확인 (음수면 만료됨)
redis-cli -h 192.168.124.101 -p 6379 TTL "payment:idempotency:my-idempotency-key-123"
```

모든 payment idempotency 키 목록 조회 (운영 주의 - SCAN 사용):
```bash
redis-cli -h 192.168.124.101 -p 6379 --scan --pattern "payment:idempotency:*"
```

idempotency 키 수동 삭제 (재시도 허용할 때):
```bash
redis-cli -h 192.168.124.101 -p 6379 DEL "payment:idempotency:my-idempotency-key-123"
```

### 7. Jaeger - 결제 confirm 비정상 span 탐색

Jaeger UI(`:16686`)에서:
- Service: `payment-app`
- Operation: `POST /api/v1/payments/confirm`
- Tags: `error=true`
- 기간 필터: 최근 1시간

---

## Recovery / Mitigation

### CANCEL_FAILED 수동 복구 절차

1. DB에서 CANCEL_FAILED 레코드의 `payment_key` 조회:
```sql
SELECT payment_id, payment_key, amount, reservation_id
FROM ticketing_payment.payments
WHERE status = 'CANCEL_FAILED';
```

2. TossPayments 관리자 콘솔에서 `payment_key`로 현재 취소 상태 확인

3. TossPayments가 취소 처리가 안 된 경우, 수동 API 호출:
```bash
curl -X POST https://api.tosspayments.com/v1/payments/{paymentKey}/cancel \
  -u {secretKey}: \
  -H "Content-Type: application/json" \
  -d '{"cancelReason": "운영자 수동 환불 처리"}'
```

4. TossPayments 취소 성공 확인 후 DB 수동 업데이트:
```sql
UPDATE ticketing_payment.payments
SET status = 'REFUNDED',
    cancelled_at = NOW(),
    updated_at = NOW()
WHERE payment_id = {paymentId}
  AND status = 'CANCEL_FAILED';
```

5. 고객에게 환불 완료 안내

### PROCESSING 상태 멈춘 idempotency 키 정리

처리 중 애플리케이션이 crash하면 Redis에 "PROCESSING" 값이 남을 수 있다.
TTL 24h가 지나면 자동 해제되지만, 즉시 재시도를 허용해야 한다면:
```bash
redis-cli -h 192.168.124.101 -p 6379 DEL "payment:idempotency:{key}"
```
단, DB를 먼저 확인해서 실제로 결제가 처리됐는지 검증한 후 삭제해야 한다.

---

## Trade-offs

### 1. 트랜잭션 경계 분리

**PaymentManager.confirmPayment()는 `@Transactional`이 없다.**
대신 `PaymentWriter`의 각 update 메서드가 독립 트랜잭션이다.

이유: TossPayments API 호출(최대 10s) 동안 DB 커넥션을 점유하면, connection pool(max 20) 소진 위험이 있다.

트레이드오프: APPROVED 업데이트와 booking confirm 호출 사이에 crash가 나면 DB는 APPROVED이나 booking은 PENDING이다.
이 불일치를 CANCEL_FAILED 경로 없이 복구하려면 별도의 reconciliation job이 필요하다 (현재 미구현).

### 2. Redis idempotency vs DB UK

Redis가 1차 방어이고 DB UK가 2차 방어다.
Redis 장애 시 DB UK가 `reservation_id` 중복을 막지만, PG 이중 호출은 여전히 가능하다.
PG 이중 호출 방지는 TossPayments의 orderId UK 정책에 의존한다.

### 3. OTLP Jaeger 설정 (planned)

현재 설계상 OTLP export가 설정되어 있지만, 전 서비스의 실제 연결 여부는 확인이 필요하다.
Jaeger에서 trace가 보이지 않으면 로그의 traceId를 기준으로 Kibana 검색에 의존해야 한다.

### 4. 커스텀 메트릭 미구현 (planned)

현재 결제 성공/실패/보상 횟수를 Prometheus 커스텀 메트릭으로 수집하지 않는다.
Grafana에서 결제 비즈니스 지표를 보려면 Kibana 로그 집계 또는 커스텀 메트릭 추가가 필요하다.

---

## Step-by-step: 사용자 결제 불만 접수 시 추적 절차

> 시나리오: "결제했는데 예약이 안 됐어요" 또는 "돈이 나갔는데 환불이 안 됩니다"

**1단계: 사용자로부터 수집할 정보**
- 결제 시도 시각 (분 단위)
- userId 또는 이메일
- reservationId (예약 번호)
- 결제 금액

**2단계: DB에서 결제 상태 확인**
```sql
SELECT
    p.payment_id,
    p.reservation_id,
    p.user_id,
    p.order_id,
    p.payment_key,
    p.amount,
    p.status,
    p.method,
    p.fail_reason,
    p.approved_at,
    p.cancelled_at,
    p.created_at,
    p.updated_at
FROM ticketing_payment.payments p
WHERE p.reservation_id = {reservationId}
   OR p.user_id = {userId}
ORDER BY p.created_at DESC
LIMIT 5;
```

**3단계: status에 따른 분기**

- `READY`: PG 호출이 실패하거나 아직 진행 중. orderId로 TossPayments API 조회
- `APPROVED`: PG 승인됐으나 booking이 CONFIRMED 아님. booking-app 상태 확인 필요
- `REFUNDED`: 정상 환불 완료. 고객에게 환불 완료 안내
- `CANCEL_FAILED`: 즉시 수동 복구 절차 진행
- `FAILED`: PG 거절. fail_reason 확인 후 고객 안내

**4단계: Kibana에서 로그 추적**

orderId 또는 reservationId로 검색:
```
message:"reservationId=10" OR message:"RES10_"
```

시간 범위를 신고 시각 ±30분으로 좁힌 후 INFO/ERROR 로그 확인.

**5단계: traceId 확보 후 Jaeger 조회**

Kibana 로그에서 `traceId` 필드 값을 복사 → Jaeger UI `:16686` → `Search by Trace ID` 탭에 입력.
span 목록에서 `error=true`인 구간의 태그와 로그를 확인.

**6단계: Redis idempotency 상태 확인 (재시도 관련 불만 시)**

사용자가 중복 결제를 주장하거나 "요청이 안 됐다"고 하면:
```bash
redis-cli -h 192.168.124.101 -p 6379 GET "payment:idempotency:{idempotencyKey}"
```
`PROCESSING` → 처리 중 crash 가능성. DB 확인 후 판단.
`{JSON}` → 이미 처리된 요청. 응답 내용 확인.
`(nil)` → 24h 이내에 요청한 적 없거나 TTL 만료.

---

## Interview Explanation (90s version)

"결제 confirm 흐름은 TossPayments 외부 API 호출, booking-app 내부 API 호출, Redis idempotency 체크, DB 상태 전이가
하나의 요청 안에 순차적으로 얽혀 있습니다. 이 구간을 관측하기 위해 세 가지 시그널을 연결했습니다.

첫째, 로그에는 Micrometer Tracing이 자동으로 주입하는 traceId와, SCG가 생성해 헤더로 전파하는 correlationId를
함께 출력합니다. 이 둘은 서로 다른 시스템에서 만들어지며 목적도 다릅니다. traceId는 Jaeger 구간 연결에,
correlationId는 Kibana 게이트웨이 로그 검색에 씁니다.

둘째, PaymentManager의 로그는 결제 생성부터 PG 호출, DB 업데이트, booking confirm까지 각 단계를 명시적으로 기록합니다.
CANCEL_FAILED처럼 고객 돈이 묶이는 상황은 [CRITICAL] 프리픽스로 표시해 Kibana 알림으로 즉시 탐지합니다.

셋째, DB의 idx_status 인덱스를 통해 CANCEL_FAILED나 장기 READY 상태를 SQL로 직접 조회합니다.

Jaeger OTLP 연결은 설계 완료 상태이며, 연결 확인 후에는 payment confirm span에서 PG 응답 시간과
booking-app 호출 실패 위치를 한 눈에 볼 수 있습니다."
