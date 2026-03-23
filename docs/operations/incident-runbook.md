---
title: "Incident Runbook: MSA 티켓팅 시스템"
last_updated: 2026-03-18
author: "민석"
reviewer: ""
---

## 목차
- [Background](#background)
- [Problem](#problem)
- [Current Design (장애 감지 체계)](#current-design-장애-감지-체계)
- [Operational Procedure (Runbook)](#operational-procedure-runbook)
- [Failure Scenarios (장애 매트릭스)](#failure-scenarios-장애-매트릭스)
- [Observability / Detection (알림 기준)](#observability-detection-알림-기준)
- [Recovery / Mitigation (복구 절차 요약)](#recovery-mitigation-복구-절차-요약)
- [Trade-offs](#trade-offs)
- [Interview Explanation (90s version)](#interview-explanation-90s-version)


## 실행 전제 조건
- [ ] (작성 필요)

## 예상 소요 시간
- (작성 필요)

## 롤백 절차
- [ ] (작성 필요)

# Incident Runbook: MSA 티켓팅 시스템

> **대상 독자**: 온콜 운영자, 백엔드 개발자
> **관련 문서**: [`docs/observability/observability.md`](../observability/observability.md) (스택 개요, 장애 조사 체크리스트, payment confirm 흐름 상세)
> **인프라**: MySQL 8.0 @ `mysql:3306` (Docker 내부, 호스트 포트 미노출), Redis @ `192.168.124.101:6379`, Prometheus + Grafana + Alertmanager, Jaeger UI `http://192.168.124.100:8080/jaeger/`, Kibana

---

## Background

이 시스템은 6개의 마이크로서비스(scg-app, user-app, waitingroom-app, concert-app, booking-app, payment-app)로 구성된다.
각 서비스는 독립 프로세스이므로 장애가 특정 서비스에 격리될 수 있다.
단, scg-app, Redis, MySQL은 공유 인프라이며 이 셋 중 하나에 문제가 생기면 전체 서비스에 영향이 미친다.

---

## Problem

MSA 구조에서 장애의 어려움은 두 가지다:
1. **인과 관계 파악이 어렵다**: downstream 서비스 장애가 upstream 서비스의 오류로 표출된다.
2. **상태 정합성**: 결제처럼 여러 서비스에 걸친 상태 전이 중 장애가 나면 서비스 간 상태가 불일치한다.

---

## Current Design (장애 감지 체계)

```
사용자 요청
  → scg-app (correlationId 부착, gateway access log 기록)
  → 각 마이크로서비스 (traceId MDC 자동 주입, 로그 출력)
  → stdout → Filebeat → Elasticsearch → Kibana (로그 검색/알림)
  → Spring Boot Actuator → Prometheus → Grafana (메트릭 대시보드)
  → Alertmanager (임계값 초과 시 알림)
  → Jaeger (distributed trace, OTLP - planned)
```

**1차 감지**: Grafana 알림 또는 Alertmanager 알림 (메트릭 임계값 초과)
**2차 확인**: Kibana 로그 검색 (correlationId, traceId, 에러 메시지)
**3차 심층 분석**: Jaeger trace (서비스 간 호출 순서와 병목 구간)
**4차 DB 직접 확인**: 상태 정합성, 잔류 레코드 직접 SQL 조회

---

## Operational Procedure (Runbook)

---

### INC-001: payment confirm 실패 (PG 오류)

#### 증상 (Symptoms)
- `POST /api/v1/payments/confirm` HTTP 500 응답 급증
- ErrorCode `P005 PAYMENT_PG_ERROR` 반환
- TossPayments 응답 4xx 또는 5xx, 또는 10s read timeout

#### 확인할 로그/메트릭 (Logs/Metrics to check)

**Kibana - PG 실패 로그 조회:**
```
message:"TossPayments confirm failed"
```
`httpStatus`와 `body` 필드에서 TossPayments 에러 코드 확인.

**Kibana - PG 거절 후 FAILED 전이 로그:**
```
message:"Payment failed after PG rejection"
```

**Grafana - payment-app 5xx 비율:**
```promql
rate(http_server_requests_seconds_count{application="payment-service", status="500"}[5m])
```

**DB - READY 상태로 30분 이상 잔류 (timeout 의심):**
```sql
SELECT payment_id, order_id, status, created_at,
       TIMESTAMPDIFF(MINUTE, created_at, NOW()) AS minutes_elapsed
FROM ticketing_payment.payments
WHERE status = 'READY'
  AND created_at < DATE_SUB(NOW(), INTERVAL 30 MINUTE)
ORDER BY created_at ASC;
```

#### 즉시 조치 (Immediate Action)
1. TossPayments 상태 페이지 확인: https://www.tosspayments.com/status
2. TossPayments 관리자 콘솔에서 문제 orderId의 상태 직접 조회
3. 타임아웃(10s 초과) 패턴이면 PG 측 장애 가능성 높음
4. PG 장애 확인되면 결제 요청 임시 차단 검토 (circuit breaker - planned)

**TossPayments API로 orderId 상태 직접 조회 (수동):**
```bash
curl -X GET https://api.tosspayments.com/v1/payments/orders/{orderId} \
  -u {secretKey}:
```

#### 후속 조치 (Follow-up)
- READY 상태 잔류 결제 목록 수동 확인 (위 SQL)
- PG 정상화 후 해당 READY 결제들을 사용자에게 안내 (재결제 유도 또는 만료 처리)
- 반복 발생 시 circuit breaker 적용 검토
- 로그에서 httpStatus 분포 확인: 4xx(카드 문제)인지 5xx(PG 서버 문제)인지 구분

#### 사용자 영향 (User Impact)
- 결제 불가 (HTTP 500)
- 좌석은 HOLD 상태 유지 (concert-app 좌석 선점 TTL 5분 내 자동 해제됨)
- 사용자는 재시도 가능하나, 동일 reservationId로 재시도 시 orderId가 바뀌므로 새 결제 레코드 생성됨

---

### INC-002: booking confirm 실패 후 보상 취소 성공

#### 증상 (Symptoms)
- 사용자가 "결제는 됐는데 예약이 안 됐다"고 신고
- Kibana에서 `Reservation confirm failed` + `initiating refund` 로그 연속 발생
- 직후 `Payment refunded` 로그 확인 (보상 성공)

#### 확인할 로그/메트릭 (Logs/Metrics to check)

**Kibana - 보상 취소 트리거 확인:**
```
message:"Reservation confirm failed" AND message:"initiating refund"
```

**Kibana - 보상 환불 성공 확인:**
```
message:"Payment refunded"
```

**연속 발생 패턴 확인 (traceId로 묶어서 조회):**
```
traceId:"{해당 traceId}"
```

**DB - payment REFUNDED, reservation PENDING 상태 확인:**
```sql
-- payment 상태
SELECT payment_id, reservation_id, status, approved_at, cancelled_at
FROM ticketing_payment.payments
WHERE reservation_id = {reservationId};

-- booking 상태 (booking-app DB)
SELECT reservation_id, status, updated_at
FROM ticketing_booking.reservations
WHERE reservation_id = {reservationId};
```

#### 즉시 조치 (Immediate Action)
1. TossPayments 관리자 콘솔에서 paymentKey로 환불 처리 완료 확인
2. 사용자에게 환불 완료 안내 (통상 영업일 기준 1~3일 소요)
3. booking-app 장애 여부 확인 (다른 사용자에게도 동일 문제 발생하는지)

**booking-app 상태 확인:**
```bash
curl http://localhost:8083/actuator/health
```

#### 후속 조치 (Follow-up)
- booking-app 에러 로그 확인 (왜 confirm에 실패했는지)
- 해당 시간대 booking-app의 DB connection pool 상태 확인
- 재발 방지를 위한 booking-app 장애 원인 분석
- [`docs/observability/observability.md`](../observability/observability.md) 섹션 10의 절차로 booking-app 측 로그 추가 분석

#### 사용자 영향 (User Impact)
- 결제 금액 환불됨 (정상 보상 처리)
- 예약은 확정되지 않아 좌석 재선택 필요
- 환불은 보상 취소가 성공했으므로 PG 레벨에서 처리됨

---

### INC-003: CANCEL_FAILED 발생 (최고 우선순위)

> **이 인시던트는 고객 돈이 환불되지 않은 상태다. 발생 즉시 최우선으로 처리해야 한다.**

#### 증상 (Symptoms)
- Kibana에 `[CRITICAL] Payment cancel failed` 로그 발생
- payment.status = `CANCEL_FAILED`
- 고객이 "결제는 됐는데 예약도 없고 환불도 안 됐다"고 신고

#### 확인할 로그/메트릭 (Logs/Metrics to check)

**Kibana - CRITICAL 로그 즉시 조회:**
```
message:"[CRITICAL] Payment cancel failed"
```
이 로그에서 `paymentId`와 `paymentKey` 추출.

**DB - CANCEL_FAILED 레코드 전체 조회:**
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
    approved_at,
    cancelled_at,
    created_at,
    updated_at
FROM ticketing_payment.payments
WHERE status = 'CANCEL_FAILED'
ORDER BY updated_at DESC;
```

**DB - 해당 결제의 예약 상태 확인:**
```sql
-- booking-app DB
SELECT reservation_id, user_id, seat_id, status, created_at, updated_at
FROM ticketing_booking.reservations
WHERE reservation_id = (
    SELECT reservation_id
    FROM ticketing_payment.payments
    WHERE payment_id = {paymentId}
);
```

#### 즉시 조치 (Immediate Action)

**Step 1: TossPayments 관리자 콘솔에서 paymentKey로 상태 확인**
- 취소가 이미 처리됐는지, 아니면 실제로 취소 안 됐는지 확인

**Step 2: TossPayments API로 현재 상태 조회**
```bash
curl -X GET https://api.tosspayments.com/v1/payments/{paymentKey} \
  -u {secretKey}:
```
응답의 `cancels` 배열 확인.

**Step 3: 취소 안 된 경우 수동 취소 API 호출**
```bash
curl -X POST https://api.tosspayments.com/v1/payments/{paymentKey}/cancel \
  -u {secretKey}: \
  -H "Content-Type: application/json" \
  -d '{"cancelReason": "운영자 수동 환불 처리 - 예약 확정 실패"}'
```

**Step 4: TossPayments 취소 확인 후 DB 수동 업데이트**
```sql
UPDATE ticketing_payment.payments
SET status = 'REFUNDED',
    cancelled_at = NOW(),
    updated_at = NOW()
WHERE payment_id = {paymentId}
  AND status = 'CANCEL_FAILED';
```

**Step 5: 처리 완료 후 고객 안내**
- 환불 완료 안내 (영업일 기준 1~3일)
- 좌석 재선택 필요 안내

#### 후속 조치 (Follow-up)
- CANCEL_FAILED 원인 분석: 보상 취소 호출 당시 TossPayments 상태 확인
- booking-app 장애가 왜 발생했는지 추가 분석
- CANCEL_FAILED 재발 방지: booking confirm 전 별도 검증 강화 검토
- 처리 이력 내부 기록 (어떤 paymentId를 언제 수동 처리했는지)

#### 사용자 영향 (User Impact)
- **가장 심각한 케이스**: 고객 돈이 묶여 있고, 예약도 없는 상태
- 수동 처리 완료 전까지 사용자는 불이익 상태 지속
- 즉시 고객 센터를 통해 해당 사용자에게 연락하는 것을 권장

---

### INC-004: Redis 장애

#### 증상 (Symptoms)
- waitingroom-app 기능 전체 불가 (대기열 진입/조회 불가)
- payment-app idempotency 체크 실패 → HTTP 500
- Grafana Redis connection error 알림
- `redis-cli ping` 응답 없음

#### 확인할 로그/메트릭 (Logs/Metrics to check)

**Redis 연결 직접 확인:**
```bash
redis-cli -h 192.168.124.101 -p 6379 ping
# 정상: PONG
# 장애: Could not connect to Redis at 192.168.124.101:6379: Connection refused
```

**Redis 기본 정보 확인:**
```bash
redis-cli -h 192.168.124.101 -p 6379 info server
redis-cli -h 192.168.124.101 -p 6379 info memory
redis-cli -h 192.168.124.101 -p 6379 info clients
```

**Grafana - Redis 관련 지표:**
- `redis_connected_clients` 지표 (Redis exporter 구성된 경우)
- Spring Boot `spring.data.redis.*` 메트릭

**Kibana - Redis 연결 오류 로그:**
```
message:"Redis" AND level:"ERROR"
```

**waitingroom-app - 대기열 상태 직접 조회 (Redis 복구 후):**
```bash
redis-cli -h 192.168.124.101 -p 6379 ZCARD "waitingroom:{concertId}"
redis-cli -h 192.168.124.101 -p 6379 ZCARD "allowed:{concertId}"
```

#### 즉시 조치 (Immediate Action)

1. Redis 프로세스 상태 확인:
```bash
# Redis 서버에서
systemctl status redis
# 또는 Docker 환경
docker ps | grep redis
docker logs redis
```

2. Redis 재시작:
```bash
systemctl restart redis
# 또는
docker restart redis
```

3. Redis 복구 후 서비스 연결 재확인:
```bash
redis-cli -h 192.168.124.101 -p 6379 ping
```

4. payment-app, waitingroom-app 재시작 (connection pool 재초기화):
```bash
# 각 서비스 재시작
```

#### 결제 중 Redis 장애 발생 시 특수 상황

결제 confirm 요청이 처리 중일 때 Redis가 죽으면:
- idempotency SETNX 실패 → HTTP 500 반환
- 이미 "PROCESSING" 상태가 Redis에 기록됐다면 그 키가 유실됨

**DB UK가 2차 방어**: `ticketing_payment.payments`의 `uk_reservation_id`가 중복 레코드 삽입을 막는다.
단, PG(TossPayments)에 이중 호출이 갈 수 있다. TossPayments의 orderId UK 정책으로 이중 승인은 방지되나,
이중 요청 자체는 발생한다.

Redis 복구 후 PROCESSING 상태 키가 남아 있다면 재시도를 위해 수동 삭제:
```bash
redis-cli -h 192.168.124.101 -p 6379 --scan --pattern "payment:idempotency:*" | xargs redis-cli -h 192.168.124.101 -p 6379 DEL
```
**주의**: 삭제 전 DB에서 각 idempotency key에 해당하는 결제가 실제로 처리됐는지 반드시 확인.

#### 후속 조치 (Follow-up)
- Redis 장애 원인 분석 (메모리 부족, 프로세스 crash, 네트워크 단절)
- Redis 메모리 사용량 추세 확인 (`redis-cli info memory`)
- Redis Sentinel 또는 Cluster 구성 검토 (현재 단일 노드)
- waitingroom 대기열 복구 여부 확인 (대기 중이던 사용자의 토큰 재발급 필요 여부)

#### 사용자 영향 (User Impact)
- waitingroom 진입 불가 (전체 사용자)
- 일부 결제 요청 500 오류 (idempotency 체크 실패)
- Redis 복구 즉시 서비스 재개 가능 (상태 유실 범위 제한적)

---

### INC-005: MySQL 장애/지연

#### 증상 (Symptoms)
- 모든 서비스에서 응답 지연 또는 HTTP 500 급증
- JDBC connection pool exhaustion (`HikariPool` connection timeout 로그)
- Grafana DB CPU > 80% 알림
- slow query alert 발생

#### 확인할 로그/메트릭 (Logs/Metrics to check)

**Grafana - DB 관련 지표:**
- DB CPU 사용률 > 80%
- Active JDBC connections near pool limit (max-active=20 per service, HikariCP 기본)

**PromQL - JDBC pool 상태:**
```promql
hikaricp_connections_active{application="payment-service"}
hikaricp_connections_pending{application="payment-service"}
```

**Kibana - JDBC connection 오류 로그:**
```
message:"HikariPool" AND (message:"timeout" OR message:"Connection is not available")
```

**MySQL에서 직접 확인:**
```sql
-- 현재 실행 중인 쿼리 목록
SHOW PROCESSLIST;

-- 또는 상세 정보
SELECT id, user, host, db, command, time, state, info
FROM information_schema.processlist
WHERE command != 'Sleep'
ORDER BY time DESC;

-- 잠금 대기 확인
SELECT * FROM sys.innodb_lock_waits;
```

**MySQL - payment 테이블 slow query 의심 쿼리:**

status 인덱스 (`idx_status`) 활용 여부 확인:
```sql
EXPLAIN SELECT * FROM ticketing_payment.payments WHERE status = 'CANCEL_FAILED';
-- key: idx_status 인지 확인
-- type: ref 또는 range 인지 확인 (ALL이면 풀스캔)
```

**MySQL - DB CPU 점유 쿼리 식별:**
```sql
SELECT digest_text, count_star, avg_timer_wait/1000000000 AS avg_ms, max_timer_wait/1000000000 AS max_ms
FROM performance_schema.events_statements_summary_by_digest
ORDER BY avg_timer_wait DESC
LIMIT 20;
```

#### 즉시 조치 (Immediate Action)

1. slow query 즉시 확인:
```sql
SHOW VARIABLES LIKE 'slow_query_log%';
-- slow_query_log_file 경로에서 최근 slow query 확인
```

2. 오래 실행 중인 쿼리 강제 종료 (확인 후):
```sql
-- PROCESSLIST에서 time이 큰 id 확인 후
KILL QUERY {processId};
```

3. connection pool 임시 완화 필요 시 application 재시작

4. 인덱스 없는 풀스캔 쿼리가 원인이면 즉시 EXPLAIN으로 확인 후 인덱스 추가 검토

#### 후속 조치 (Follow-up)
- slow query log 분석으로 원인 쿼리 특정
- EXPLAIN으로 실행 계획 확인 (`type=ALL`, `key=NULL`인 쿼리 우선 처리)
- payment 테이블 주요 인덱스: `idx_user_id(user_id)`, `idx_status(status)`, UK `uk_reservation_id`, UK `uk_order_id`
- connection pool size 튜닝 검토 (현재 max-active=20 per service 기준)
- DB 서버 스펙 확인 (메모리, CPU, 디스크 I/O)

#### 사용자 영향 (User Impact)
- 전체 서비스 응답 지연 또는 오류 (MySQL을 사용하는 모든 서비스)
- 가장 광범위한 영향. 빠른 원인 파악과 격리가 중요

---

### INC-006: scg-app 라우팅 문제

#### 증상 (Symptoms)
- 특정 서비스로만 요청이 안 됨 (404 Not Found 또는 502 Bad Gateway)
- Grafana에서 특정 routeId만 5xx 급증
- 다른 서비스는 정상

#### 확인할 로그/메트릭 (Logs/Metrics to check)

**scg-app gateway access log에서 routeId 확인 (Kibana):**
```
routeId:"payment-service" AND status:"502"
```

**Grafana - routeId별 5xx 비율:**
```promql
rate(spring_cloud_gateway_requests_seconds_count{routeId="payment-service", status=~"5.."}[5m])
```

**scg-app 라우트 설정 확인:**

payment-app route는 `spring.cloud.gateway.routes[3]` (index 3):
- 설정 확인: scg-app `application.properties` 또는 `application.yml`
- URI: payment-app 서비스 주소 (예: `http://payment-app:8084`)

**해당 서비스 health 직접 확인:**
```bash
curl http://payment-app:8084/actuator/health
```

**Kibana - scg-app 라우팅 오류 로그:**
```
message:"Could not find route" OR message:"Connection refused" AND logger:"scg"
```

#### 즉시 조치 (Immediate Action)

1. 해당 다운스트림 서비스가 실제로 살아있는지 확인:
```bash
curl http://{service-host}:{port}/actuator/health
```

2. 서비스가 죽었다면 해당 서비스 재시작

3. 서비스는 살아있는데 502가 나면 scg-app의 routes 설정 확인:
- URI가 올바른지
- predicates(Path, Method) 설정이 올바른지

4. scg-app 자체 재시작 (설정 reload):
```bash
# scg-app 재시작
```

#### 후속 조치 (Follow-up)
- scg-app routes 설정이 최신 서비스 주소를 가리키는지 확인
- service discovery 도입 검토 (현재 정적 URL 설정)
- 각 route별 health check 설정 검토

#### 사용자 영향 (User Impact)
- 영향받는 서비스만 격리해서 불가 (다른 서비스는 정상)
- payment-service route 문제면 결제 전체 불가, 좌석 HOLD TTL 이내

---

### INC-007: TossPayments 외부 장애/타임아웃

#### 증상 (Symptoms)
- ErrorCode `P005 PAYMENT_PG_ERROR` 급증
- Kibana에 `TossPayments confirm failed` 다수 발생
- 응답 시간이 10s(read timeout) 근처
- TossPayments 상태 페이지에 장애 공지

#### 확인할 로그/메트릭 (Logs/Metrics to check)

**Kibana - PG 실패 로그 및 httpStatus 분포:**
```
message:"TossPayments confirm failed"
```
`httpStatus` 필드 확인: `408`/`503`/`504`이면 PG 측 장애 가능성 높음.

**Kibana - PG 요청 시작 로그 대비 성공 로그 비율:**
```
message:"TossPayments confirm request"
```
```
message:"TossPayments confirm success"
```
요청 수 대비 성공 수를 비교.

**Grafana - payment-app HTTP 500 비율 급증 확인:**
```promql
rate(http_server_requests_seconds_count{application="payment-service", status="500"}[5m])
```

**Grafana - p99 응답 시간 (10s 근접이면 timeout 패턴):**
```promql
histogram_quantile(0.99,
  rate(http_server_requests_seconds_bucket{
    application="payment-service",
    uri="/api/v1/payments/confirm"
  }[5m])
)
```

**DB - READY 상태 잔류 건수:**
```sql
SELECT COUNT(*) AS ready_count,
       MIN(created_at) AS oldest_ready
FROM ticketing_payment.payments
WHERE status = 'READY'
  AND created_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE);
```

#### 즉시 조치 (Immediate Action)

1. TossPayments 상태 페이지 확인: https://www.tosspayments.com/status

2. TossPayments 장애 확인 시 결제 요청 임시 차단 검토:
   - circuit breaker 적용 **(planned)**: 현재 미구현. 임시 방편으로 scg-app에서 payment route 비활성화 가능하나 위험하므로 상황 판단 필요.

3. 사용자 안내: 결제 서비스 일시 중단 공지

4. TossPayments 복구 후 READY 상태 잔류 결제 처리:
   - 5분 TTL 내 HOLD 좌석은 자동 해제됨
   - READY 상태 결제 레코드는 사용자가 재결제 시 새 orderId로 재시도 가능

#### RestClient 타임아웃 설정 (참조)

`RestClientConfig.java` 설정:
- connect timeout: 3,000ms (3s)
- read timeout: 10,000ms (10s)

10s read timeout 이후 `SocketTimeoutException` 발생 → `BusinessException(P005)` → payment status = FAILED

#### 후속 조치 (Follow-up)
- TossPayments 장애 후 READY 잔류 결제 목록 확인 및 사용자 안내
- 장애 발생 시간, 영향 건수, 복구 시간 기록
- circuit breaker (Resilience4j) 적용 검토: `resilience4j.*` 메트릭은 이미 Grafana에서 지원됨 ([`docs/observability/observability.md`](../observability/observability.md) 참조)
- TossPayments 장애 이력 패턴 분석

#### 사용자 영향 (User Impact)
- 결제 요청 전체 불가 (PG 연동 불가)
- 좌석은 HOLD 상태 유지 (5분 TTL 내 자동 해제 → 다른 사용자가 선점 가능)
- 사용자는 TTL 해제 후 재입장 필요

---

### INC-008: concert-app Optimistic Lock 충돌 폭증

#### 증상 (Symptoms)
- booking-app에서 예약 생성 성공률 급감 (409 SEAT_ALREADY_HELD 응답 급증)
- Jaeger에서 `POST /internal/v1/seats/{seatId}/hold` span이 짧고 반복적으로 실패
- Kibana에서 `OptimisticLockingFailureException` 또는 `Seat already held` 로그 다수 발생
- concert-app CPU는 낮지만 booking-app HikariCP pending 증가

#### 발생 조건
- 피크 트래픽 시 같은 좌석에 N명이 동시 요청 → concert-app `@Version` optimistic lock이 충돌
- booking-app이 seat hold 실패 시 reservation 저장을 중단하고 409 반환 → 예약 성공률 저하

#### 확인할 로그/메트릭 (Logs/Metrics to check)

**Kibana - optimistic lock 충돌 로그:**
```
message:"Seat already held" OR message:"OptimisticLockingFailureException"
```

**Kibana - booking-app seat hold 실패 패턴 (seatId별 집계):**
```
message:"seat" AND message:"hold" AND level:"ERROR"
```

**DB - 현재 HOLD 상태 좌석 수 확인:**
```sql
SELECT COUNT(*) AS hold_count, s.schedule_id
FROM ticketing_concert.seats s
WHERE s.status = 'HOLD'
GROUP BY s.schedule_id
ORDER BY hold_count DESC;
```

**DB - 버전 번호 급증 좌석 확인 (반복 충돌 지표):**
```sql
SELECT seat_id, seat_no, status, version
FROM ticketing_concert.seats
WHERE version > 10
ORDER BY version DESC
LIMIT 20;
```

**Grafana - booking-app 409 비율:**
```promql
rate(http_server_requests_seconds_count{application="booking-service", status="409"}[5m])
```

#### 즉시 조치 (Immediate Action)
1. 충돌 폭증은 **정상적인 경쟁 상황**일 수 있다. 먼저 영향받는 seatId와 동시 요청 패턴 확인
2. 특정 좌석에 비정상적으로 집중된 요청이라면 rate limiting 검토 (planned)
3. 특정 이벤트의 좌석이 HOLD 상태로 장시간 잔류하는 경우 → booking-app 만료 스케줄러 상태 확인

**booking-app 만료 스케줄러 상태 확인:**
```bash
curl -s http://booking-app:8083/actuator/health
# 스케줄러 스레드 활성 여부는 Prometheus: scheduler.tasks.* 확인
```

4. concert-app이 일시적으로 응답 불가 상태인지 확인:
```bash
curl -s http://concert-app:8082/actuator/health
```

#### 후속 조치 (Follow-up)
- 충돌률 = (409 건수 / 전체 seat hold 시도) 계산 → 80% 이상이면 SLO 위반 아님(정상 경쟁)이지만 사용자 경험 저하
- booking-app에서 seat hold 재시도 횟수 및 최대 재시도 제한 확인 (`seat-locking-design.md` 참조)
- 피크 트래픽 예상 시 AVAILABLE 좌석 수 대비 동시 요청 수 사전 계산

#### 사용자 영향 (User Impact)
- 일부 사용자 예약 실패 (409 반환, 재시도 필요)
- 좌석 자체는 AVAILABLE 상태 유지 — 데이터 정합성에는 영향 없음
- booking-app + concert-app이 살아있으면 다른 사용자는 정상 서비스 가능

---

### INC-009: user-app 계정 관련 장애

#### 증상 (Symptoms)
- `POST /api/v1/users/signup` HTTP 400/500 급증
- 동일 이메일로 반복 가입 시도로 중복 이메일 검증 오류 급증
- Kibana에서 `이미 가입된 이메일입니다` 오류 다수 발생
- (장래) user-app이 JWT 발급 담당이 되면: 로그인 실패 급증 시 전체 인증 불가

#### 발생 조건
- **A. 이메일 중복 폭증**: 동일 이메일로 빠른 재시도(네트워크 재시도, 클라이언트 버그)
- **B. DB 연결 장애**: user-app → MySQL `ticketing_user` 연결 불가
- **C. (미래) JWT 발급 서비스 장애**: user-app 다운 → 신규 로그인 불가 → 전체 예약 흐름 차단

#### 확인할 로그/메트릭 (Logs/Metrics to check)

**Kibana - 이메일 중복 오류:**
```
message:"이미 가입된 이메일입니다"
```

**Kibana - user-app 전체 오류:**
```
service:"user-service" AND level:"ERROR"
```

**Grafana - user-app 5xx:**
```promql
rate(http_server_requests_seconds_count{application="user-service", status=~"5.."}[5m])
```

**DB - 중복 이메일 분포 확인:**
```sql
-- 이메일 기준으로 중복 가입 시도 확인 (UK 위반 전 validator에서 잡힘)
SELECT email, COUNT(*) AS signup_attempts
FROM ticketing_user.users
-- 실제 저장된 건이 아닌 로그에서 확인 필요
-- 아래는 현재 users 테이블 규모 확인
SELECT COUNT(*) FROM ticketing_user.users;
```

**DB - user-app DB 연결 상태:**
```sql
SELECT user, host, db, command, time, state
FROM information_schema.processlist
WHERE db = 'ticketing_user';
```

#### 즉시 조치 (Immediate Action)

**시나리오 A (이메일 중복 폭증)**:
1. 특정 이메일/IP 패턴에서 반복 요청인지 확인 (Kibana access log)
2. rate limiting이 없으므로 현재는 user-app이 모든 요청 처리 → user-app CPU/메모리 확인
3. 단기 완화: SCG에서 `/api/v1/users/signup`에 rate limiting 적용 검토 **(planned)**

**시나리오 B (DB 연결 장애)**:
1. user-app health check 확인:
```bash
curl -s http://user-app:8080/actuator/health
```
2. MySQL `ticketing_user` 스키마 접근 가능 여부 확인:
```bash
docker exec mysql mysql -u root -p -e "USE ticketing_user; SELECT 1;"
```
3. user-app 재시작 (connection pool 재초기화)

#### 후속 조치 (Follow-up)
- 비밀번호 평문 저장 상태(R6) 확인 — DB 노출 시 즉각 비밀번호 전체 재설정 안내 필요
- user-app이 JWT 발급 담당이 되면 이 서비스의 가용성이 전체 인증 흐름의 SPOF가 됨을 설계 문서에 반영
- 이메일 중복 validator 오류는 정상 비즈니스 로직이므로 SLO 위반이 아님 (4xx)

#### 사용자 영향 (User Impact)
- 신규 가입 불가 (시나리오 A, B)
- 기존 사용자 예약/결제는 영향 없음 (user-app은 leaf service, 다른 서비스가 의존하지 않음)
- (미래) JWT 발급 담당이 되면 영향 범위가 전체 서비스로 확대됨

---

## Failure Scenarios (장애 매트릭스)

| 장애 구분 | 영향 범위 | 자동 복구 | 수동 개입 | 심각도 |
|---|---|---|---|---|
| TossPayments PG 오류 | 결제 불가 | O (FAILED 전이) | 잔류 READY 정리 | High |
| booking confirm 실패 + 보상 성공 | 해당 사용자 예약 | O (REFUNDED) | 환불 확인 안내 | Medium |
| CANCEL_FAILED | 해당 사용자 돈 묶임 | X | **즉시 수동 필요** | Critical |
| Redis 장애 | 전체 대기열 + 일부 결제 | O (재시작 후) | idempotency 키 정리 | High |
| MySQL 장애/지연 | 전체 서비스 | X | slow query 격리 | Critical |
| scg-app 라우팅 오류 | 특정 서비스 | X | routes 확인/재시작 | High |
| TossPayments 외부 장애 | 결제 전체 | O (timeout 후) | 잔류 READY 정리 | High |
| concert-app Optimistic Lock 폭증 | 예약 성공률 저하 | O (409 반환, 정상 경쟁) | 만료 스케줄러 확인 | Medium |
| user-app 계정 장애 | 신규 가입 불가 | O (재시작 후) | 비밀번호 노출 시 수동 대응 | Medium |

---

## Observability / Detection (알림 기준)

### Alertmanager 알림 규칙 (설계 기준)

```yaml
# 예시 - 실제 rules 파일과 동기화 필요
- alert: PaymentCancelFailed
  expr: increase(payment_confirm_total{result="cancel_failed"}[5m]) > 0
  severity: critical
  # (planned) 커스텀 메트릭 구현 후 활성화

- alert: PaymentHighErrorRate
  expr: rate(http_server_requests_seconds_count{application="payment-service", status=~"5.."}[5m]) > 0.05
  severity: high

- alert: DbCpuHigh
  expr: mysql_global_status_threads_running > 20
  severity: high

- alert: RedisDown
  expr: redis_up == 0
  severity: critical

- alert: PaymentConfirmSlowP95
  expr: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{uri="/api/v1/payments/confirm"}[5m])) > 1.5
  severity: warning
```

### 즉시 Kibana 저장 검색으로 등록해야 할 쿼리

| 이름 | KQL 쿼리 | 알림 주기 |
|---|---|---|
| CANCEL_FAILED Critical | `message:"[CRITICAL] Payment cancel failed"` | 실시간 (1건 이상 즉시) |
| PG 오류 급증 | `message:"TossPayments confirm failed"` | 5분마다 |
| 보상 취소 트리거 | `message:"Reservation confirm failed"` | 5분마다 |
| Idempotency 충돌 | `message:"Idempotency conflict"` | 15분마다 |

---

## Recovery / Mitigation (복구 절차 요약)

### 우선순위별 복구 체계

1. **CANCEL_FAILED (Critical)**: 즉시 INC-003 절차 실행. 고객 돈이 묶인 상태.
2. **MySQL 장애 (Critical)**: 즉시 INC-005 절차 실행. 전체 서비스 불가.
3. **Redis 장애 (High)**: INC-004 절차. 대기열 및 idempotency 영향.
4. **PG 장애 (High)**: INC-007 절차. 결제만 불가, 다른 서비스는 정상.
5. **scg-app 라우팅 (High)**: INC-006 절차. 특정 서비스만 영향.
6. **booking confirm 실패 (Medium)**: INC-002 절차. 자동 보상 후 사용자 안내.
7. **concert-app Optimistic Lock 폭증 (Medium)**: INC-008 절차. 예약 성공률 저하, 데이터 정합성 영향 없음.
8. **user-app 계정 장애 (Medium)**: INC-009 절차. 신규 가입 불가, 기존 사용자 영향 없음.

---

## Trade-offs

### 1. 수동 개입 의존성 (CANCEL_FAILED)

CANCEL_FAILED 상태의 자동 재시도 메커니즘이 없다.
이유: PG 취소 API가 왜 실패했는지 모르는 상태에서 자동 재시도하면 이중 취소나 상태 불일치가 생길 수 있다.
트레이드오프: 운영 부담이 늘지만 정합성이 보장된다.
개선 방향: 재시도 가능한 실패(네트워크 타임아웃)와 재시도 불가능한 실패(이미 취소됨)를 구분해 선택적 자동 재시도 구현.

### 2. Redis 단일 노드

현재 Redis는 단일 노드(`192.168.124.101:6379`)다.
Redis 장애 시 대기열 전체와 idempotency 체크가 불가해진다.
DB UK가 2차 방어이나 PG 이중 호출은 막지 못한다.
개선 방향: Redis Sentinel 또는 Cluster 구성.

### 3. circuit breaker 미구현

TossPayments 외부 장애 시 요청이 10s씩 타임아웃될 때까지 DB connection을 소모한다.
Resilience4j 메트릭은 이미 Grafana에서 지원하므로, circuit breaker 적용 시 즉시 모니터링 가능.

### 4. reconciliation job 미구현

APPROVED 상태에서 booking confirm이 실패하고 보상 취소까지 실패(CANCEL_FAILED)한 경우를 제외하면,
APPROVED이지만 booking CONFIRMED가 아닌 케이스를 주기적으로 탐지하는 배치가 없다.
현재는 CANCEL_FAILED 로그와 DB 직접 쿼리로 수동 감지한다.

---

## Interview Explanation (90s version)

"이 런북은 결제 흐름을 중심으로 7가지 장애 유형을 정의합니다. 가장 중요한 구분은 자동 복구가 되는 장애와 수동 개입이 필요한 장애입니다.

TossPayments PG 오류는 payment가 FAILED로 전이하면서 자동 종결됩니다. booking confirm 실패 후 보상 취소 성공도 자동 처리됩니다. 이 두 케이스는 고객 안내만 하면 됩니다.

가장 심각한 케이스는 CANCEL_FAILED입니다. PG 승인은 됐지만 booking 확정도 실패하고 보상 취소마저 실패한 상태입니다. 고객 돈이 묶여 있으므로 [CRITICAL] 로그가 발생하는 즉시 수동으로 TossPayments API를 직접 호출해 취소하고, DB를 수동 업데이트해야 합니다.

Redis 장애는 대기열과 idempotency를 무너뜨리지만, DB UK가 2차 방어를 합니다. MySQL 장애는 전체 서비스에 영향을 주므로 slow query 격리가 최우선입니다.

각 장애는 Kibana 실제 로그 메시지, DB SQL 쿼리, Redis CLI 명령어로 즉시 확인 가능하도록 구체적으로 정의했습니다."

---

## 추가 검토 필요 시나리오

> 아래 시나리오는 설계 문서(queue-design.md, seat-locking-design.md, payment-architecture.md, security-design.md)에서 언급된 장애 포인트 중 현재 런북에 대응 절차가 없는 항목입니다. 향후 INC-008 이후로 절차를 추가할 것을 권장합니다.

| 시나리오 | 영향 범위 | 심각도 | 현재 대응 여부 |
|---------|----------|--------|--------------|
| waitingroom-app 전면 장애 (Redis SPOF 외 app 자체 크래시) | 대기열 진입 전체 불가, 티켓팅 오픈 시 치명적 | High | ❌ 미대응 |
| concert-app Optimistic Lock 충돌 폭증 (피크 트래픽 시 @Version 재시도 루프) | booking-app 예약 성공률 급감, 좌석 HOLD 지연 | High | ✅ INC-008 |
| booking-app Redisson 분산락 획득 타임아웃 (락 경합 과다) | 동일 좌석 동시 예약 요청 모두 실패 → 500 응답 | High | ❌ 미대응 |
| Redis idempotency 키 PROCESSING 상태 만료 전 잔류 (정상 재시도 차단) | 특정 결제 재시도 영구 불가, idempotency 키 TTL 만료까지 결제 불가 | Medium | ❌ 미대응 |
| waitingroom rate_limit 키 TTL 미만료로 영구 통과 차단 (rate limiter bug) | 대기열 통과 불가 — 순번 1위도 입장 불가 상태 | Medium | ❌ 미대응 |
| oauth2-proxy / VPN 장애로 운영 도구 접근 불가 (Grafana, Jenkins, Kibana) | 장애 감지 · 배포 · 로그 조회 전체 불가 | High | ❌ 미대응 |
| Filebeat → Elasticsearch 수집 중단 (로그 파이프라인 장애) | Kibana 기반 장애 감지 불가 — 장애 인지 시간 지연 | Medium | ❌ 미대응 |
| booking-app 예약 만료 스케줄러 중단 (PENDING 좌석 HOLD 미해제) | 만료된 예약의 좌석이 계속 HOLD 상태로 잔류 → 다른 사용자 선점 불가 | Medium | ❌ 미대응 |
| SCG 전면 장애 (scg-app 프로세스 다운) | 외부에서 모든 서비스 접근 불가 (내부 서비스 간 통신은 유지) | Critical | ❌ 미대응 |
| Auth-Passport 헤더 없는/위조된 요청 처리 (SCG 필터 버그 or bypass) | 서비스 레벨 인증 없으므로 임의 userId로 동작 — 데이터 오염 가능 | High | ❌ 미대응 |
