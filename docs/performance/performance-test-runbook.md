---
title: "HTTP 부하 테스트 Runbook (k6)"
last_updated: 2026-03-18
author: "민석"
reviewer: ""
---

## 목차
- [Background](#background)
- [Problem](#problem)
- [Current Design](#current-design)
- [Measurement / Validation](#measurement-validation)
- [Failure / Bottleneck Scenarios](#failure-bottleneck-scenarios)
- [테스트 전 데이터 준비 절차](#테스트-전-데이터-준비-절차)
- [테스트 결과 해석법](#테스트-결과-해석법)
- [Trade-offs](#trade-offs)
- [Planned Improvements](#planned-improvements)


## 실행 전제 조건
- [ ] (작성 필요)

## 예상 소요 시간
- (작성 필요)

## 롤백 절차
- [ ] (작성 필요)

# HTTP 부하 테스트 Runbook (k6)

> **범위**: 이 문서는 k6를 사용한 HTTP 레벨 부하 테스트를 다룬다.
> JVM 내부 동시성 검증(CountDownLatch 시나리오 A/B/C, Black-box+White-box 원칙, SQL 확인 쿼리)은
> [`docs/07-performance-test-runbook.md`](../07-performance-test-runbook.md)에 있으며 이 문서는 그 내용을 반복하지 않는다.
> 관측성 스택(Prometheus, Grafana, Jaeger) 개요와 payment confirm 흐름 trace 절차는 [`docs/observability/observability.md`](../observability/observability.md)를 참고한다.

---

## Background

CountDownLatch 기반 단위 테스트는 단일 JVM 내부에서 스레드 경합을 검증한다.
그러나 실제 운영 트래픽에서 발생하는 병목은 다른 위치에 있다.

- Spring Cloud Gateway(Netty 기반)가 실제로 병목인지
- SCG → booking-app → concert-app 구간의 네트워크 홉 레이턴시 합산
- HikariCP 커넥션 풀(기본 ~10개)이 동시 요청 아래에서 어떻게 소진되는지
- TossPayments 외부 호출(connect 3s, read 10s)이 P99에 미치는 실제 영향
- Redis Sorted Set O(log N) ZADD/ZRANK가 대규모 VU 아래에서 어떻게 동작하는지

이 모든 것은 JVM 내부 테스트만으로는 관측할 수 없다.
k6는 실제 HTTP 클라이언트를 다수 생성하고 전체 스택을 통과하는 요청을 보내므로,
운영 환경과 가장 유사한 조건에서 레이턴시와 오류율을 측정할 수 있다.

---

## Problem

현재 시스템에서 HTTP 레벨 부하 테스트 없이 답하기 어려운 질문들:

1. 좌석 선점 경쟁에서 SCG와 booking-app 중 어느 쪽이 P95 레이턴시를 지배하는가
2. Idempotency-Key Redis 처리가 100 VU 아래에서 실제로 PG 호출을 1회로 제한하는가
3. E2E 흐름(대기열 → 선점 → 결제)에서 단계별 레이턴시 분포는 어디가 가장 넓은가
4. SCG의 Netty 기반 라우팅이 실제로 throughput 병목인가, 아니면 upstream 서비스인가
5. HikariCP가 고갈될 때 사용자에게 5xx가 노출되는가, 아니면 큐잉되어 timeout으로 표출되는가

---

## Current Design

### 테스트 계층 구분

이 프로젝트의 테스트는 두 계층으로 분리된다. 두 계층은 서로 다른 질문에 답한다.

| 계층 | 도구 | 위치 | 검증 대상 | 참조 |
|------|------|------|----------|------|
| Layer 1: JVM 동시성 | CountDownLatch (JUnit) | 단일 JVM | 낙관적 락 충돌률, 멱등성 DB 정합성, 상태 전이 정확성 | [docs/07](../07-performance-test-runbook.md) |
| Layer 2: HTTP 부하 | k6 | 외부 클라이언트 | 전체 스택 레이턴시, SCG throughput, HikariCP 고갈, TossPayments P99 | 이 문서 |

Layer 1은 "맞게 동작하는가"를 확인한다.
Layer 2는 "부하 아래에서도 맞게 동작하는가, 그리고 얼마나 빠른가"를 확인한다.
두 계층 모두 통과해야 배포 가능 상태로 판정한다.

---

## Measurement / Validation

### 시나리오 1: 좌석 선점 경쟁 부하 테스트

**목적**: 동시에 같은 좌석에 N명이 예약을 시도할 때, optimistic lock 충돌이 올바른 비즈니스 오류로 수렴하는지 확인한다.

**설정**:
- VU: 50명 동시
- 대상: 동일한 `seatId` 1개
- 각 VU는 서로 다른 `userId`와 유효한 waiting room token을 보유
- Duration: 단발성 (ramp-up 없이 즉시 50 VU)

**기대 결과**:
- 성공(200/201): 정확히 1건
- 나머지 49건: `409 SEAT_ALREADY_HELD` (비즈니스 오류)
- 5xx: 0건
- `SEAT_ALREADY_HELD` 외 4xx: 0건 (예: 401, 400은 fixture 설정 오류를 의미)

**임계값 기준**:

```
thresholds: {
  http_req_failed: ['rate==0'],                    // 5xx = 0
  http_req_duration: ['p(95)<500'],                // P95 < 500ms
  'checks{type:unexpected_4xx}': ['rate==0'],      // SEAT_ALREADY_HELD 이외 4xx = 0
}
```

**주의사항**:
- 충돌 99건은 오류가 아니라 정상 동작이다. `docs/07 시나리오 A`의 데이터 정합성 기준(seats.status = HOLD 1건만)을 함께 확인해야 한다.
- k6 응답 통계만으로 합격 판정하지 않는다. 테스트 후 SQL로 최종 상태를 검증한다.

---

### 시나리오 2: 결제 confirm 중복 요청 부하 테스트

**목적**: 동일한 `orderId`와 `Idempotency-Key`로 N번 confirm을 시도했을 때, TossPayments가 정확히 1회만 호출되고 DB에 payment 1건만 APPROVED 상태로 남는지 확인한다.

**설정**:
- VU: 100명 동시
- 동일 `orderId`, 동일 `paymentKey`, 동일 `amount`, 동일 `Idempotency-Key` 헤더
- 사전에 payment READY 상태 1건 fixture 준비

**기대 결과**:
- 200 (첫 번째 성공): 1건 또는 소수의 거의 동시 응답
- `409 P006 PAYMENT_IDEMPOTENCY_CONFLICT`: 나머지
- 5xx: 0건
- DB `ticketing_payment.payments` WHERE `order_id = ?`: 정확히 1건, status = APPROVED
- TossPaymentsClient confirm 호출 로그: 정확히 1회 (Kibana 또는 application log 기준)

**임계값 기준**:

```
thresholds: {
  http_req_failed: ['rate==0'],              // 5xx = 0
  'checks{type:pg_single_call}': ['rate==1'] // TossPayments 호출 = 1회 (화이트박스 검증)
}
```

**주의사항**:
- 이 시나리오는 HTTP 응답만으로는 검증 불완전하다. 반드시 화이트박스(DB 행 수, PG 호출 로그)를 함께 확인한다.
- Redis `processing` key의 TTL과 `setIfAbsent` 경쟁 구간이 핵심이다. 자세한 내용은 [`docs/observability/observability.md`](../observability/observability.md)의 idempotency 흐름을 참고한다.

---

### 시나리오 3: 전체 흐름 E2E 부하 테스트

**목적**: 대기열 진입부터 결제 confirm까지 전체 흐름에서 각 단계별 레이턴시 분포를 측정한다. 단계별로 어느 구간이 P95를 지배하는지 파악하는 것이 목표다.

**설정**:
- VU: 30명 동시
- 각 VU는 서로 다른 `userId`와 서로 다른 `seatId`를 사용 (경합 없음)
- 단계: 대기열 진입 → polling → 좌석 선점 → 결제 준비 → 결제 confirm

**단계별 임계값 기준**:

| 단계 | 엔드포인트 | P95 임계값 | 근거 |
|------|-----------|-----------|------|
| 대기열 진입 | `POST /waiting-room/join` | < 200ms | Redis ZADD 1회, 외부 호출 없음 |
| 좌석 선점 | `POST /api/v1/reservations` | < 800ms | waiting token 검증 + concert-app 내부 호출 + DB save |
| 결제 준비 | `POST /api/v1/payments/request` | < 1000ms | booking 조회 + seat 가격 조회 + DB save |
| 결제 confirm | `POST /api/v1/payments/confirm` | < 5000ms | TossPayments 외부 호출 포함 (read timeout 10s) |

**주의사항**:
- `POST /payments/confirm`의 P95 < 5000ms 기준은 TossPayments sandbox 환경 기준이다. sandbox 응답이 불규칙한 경우 결과가 왜곡될 수 있다.
- VU 간 좌석이 겹치지 않도록 fixture를 사전에 준비해야 한다. 겹치면 시나리오 1(경쟁 테스트)과 혼재된다.

---

### 시나리오 4: SCG 라우팅 throughput 테스트

**목적**: SCG 자체가 throughput 병목인지 확인한다. Netty 기반이므로 동기 서비스보다 높은 처리량이 예상되며, 이를 수치로 입증한다.

**설정**:
- VU: 200명 동시
- 대상: `GET /api/v1/events/{id}` (concert-app, read-only, Caffeine L1 캐시 적용 엔드포인트)
- Duration: 30s steady-state

**임계값 기준**:

```
thresholds: {
  http_req_duration: ['p(95)<100'],          // Caffeine 캐시 히트 시 P95 < 100ms
  http_reqs: ['rate>1000'],                   // throughput > 1000 req/s
  http_req_failed: ['rate==0'],
}
```

**해석 방법**:
- P95 < 100ms이지만 throughput이 1000 req/s를 넘지 못한다면 SCG 또는 Tomcat 스레드 풀이 병목이다.
- P95 > 100ms라면 Caffeine 캐시 히트율을 먼저 확인한다. 캐시 미스라면 concert-app의 DB 조회가 지배하는 것이다.
- SCG gateway route latency와 upstream service latency를 Grafana에서 분리해서 비교한다.

---

## Failure / Bottleneck Scenarios

다음 표는 부하 테스트 중 발생할 수 있는 병목 위치와 진단 방법을 정리한 것이다.
각 항목은 실제 인프라 제약(Redis single node, MySQL single instance, HikariCP ~10 connections)에서 도출됐다.

| 구간 | 병목 위험 | 증상 | 확인 지표 및 방법 |
|------|---------|------|-----------------|
| Redis Sorted Set (waitingroom-app) | ZADD/ZRANK는 O(log N)이지만, single node Redis에 모든 서비스가 붙으므로 커넥션 경합 발생 가능 | 대기열 join P95 급등 | Redis `LATENCY HISTORY`, `INFO stats`의 `total_commands_processed`; Lettuce pool max-active=20 소진 여부 |
| optimistic lock 충돌 (concert-app) | 재시도 없이 409 반환 설계이므로 충돌률 자체는 높아도 정상 | P95 낮음에도 불구하고 예약 성공률이 낮음 | S001 에러 비율; `SELECT version FROM seats WHERE seat_id=?`로 lock contention 빈도 확인 |
| HikariCP 고갈 (booking-app, payment-app) | HikariCP 기본 max-pool-size ~10, 동시 요청 > 10이면 connection wait 발생 | P99 급등, `ConnectionAcquisitionTimeout` 예외 | Micrometer `hikaricp.pending` > 0 지속; Grafana에서 `hikaricp.connections.active` vs `hikaricp.connections.max` |
| TossPayments 외부 호출 (payment-app) | read timeout 10s의 동기 블로킹 호출; sandbox 응답 지연 시 Tomcat 스레드 점유 | payment confirm P99 > 5s | application log의 TossPaymentsClient 응답 시간; Jaeger에서 `toss-confirm` span duration |
| Tomcat 스레드 고갈 | 동기 블로킹 서비스(booking, payment, concert, user) 모두 Tomcat ~200 스레드. TossPayments 블로킹 중 다른 요청이 들어오면 스레드 고갈 | P95 급등 후 일제히 503 | `tomcat.threads.busy` >= `tomcat.threads.config.max`; Grafana panel |
| SCG 자체 (scg-app) | Netty event loop 기반이므로 상대적으로 낮은 위험. 그러나 필터 체인 처리(GatewayAccessLogGlobalFilter 등)가 추가되면 일부 오버헤드 | gateway latency와 upstream latency 차이가 크게 벌어짐 | Grafana에서 `spring_cloud_gateway_requests_seconds` vs upstream service P95 비교 |
| MySQL single instance 공유 | 5개 schema가 동일 MySQL 인스턴스에 있으므로, 한 서비스의 heavy query가 다른 서비스에 영향 | 특정 서비스만 쿼리했는데 무관한 서비스 레이턴시 동반 상승 | MySQL `SHOW PROCESSLIST`; slow query log; `information_schema.innodb_trx` |

---

## 테스트 전 데이터 준비 절차

k6 테스트는 외부에서 실제 HTTP를 보내므로, JUnit fixture와 달리 데이터를 사전에 DB에 직접 삽입해야 한다.

### MySQL fixture

시나리오 1 (좌석 선점 경쟁) 준비:

```sql
-- ticketing_concert 스키마에 테스트용 좌석 1개 준비 (AVAILABLE 상태)
INSERT INTO ticketing_concert.seats (schedule_id, seat_no, status, price, version)
VALUES (1001, 'A-01', 'AVAILABLE', 50000, 0)
ON DUPLICATE KEY UPDATE status = 'AVAILABLE', version = 0;

-- 테스트 종료 후 상태 검증
SELECT seat_id, seat_no, status, version
FROM ticketing_concert.seats
WHERE schedule_id = 1001 AND seat_no = 'A-01';
-- 기대: status = 'HOLD', 1건만 존재
```

시나리오 2 (결제 중복 confirm) 준비:

```sql
-- ticketing_payment 스키마에 READY 상태 payment 1건 준비
INSERT INTO ticketing_payment.payments
  (reservation_id, user_id, order_id, amount, status)
VALUES (90001, 1001, 'order-k6-test-001', 50000, 'READY');
```

시나리오 3 (E2E) 준비:

```sql
-- 30개 VU에 대해 서로 다른 seat 30개 준비
INSERT INTO ticketing_concert.seats (schedule_id, seat_no, status, price, version)
SELECT 1001, CONCAT('B-', LPAD(seq, 2, '0')), 'AVAILABLE', 50000, 0
FROM (SELECT @row := @row + 1 AS seq FROM information_schema.columns, (SELECT @row:=0) r LIMIT 30) t;
```

### Redis 초기화

테스트 간 격리를 위해 이전 테스트의 대기열 키를 삭제한다:

```
# 대기열 키 삭제 (테스트 격리)
DEL waiting-room:event:1001:queue
DEL waiting-room:event:1001:*

# idempotency 키 삭제 (시나리오 2 재실행 시)
DEL payment:idempotency:order-k6-test-001:*
```

주의: 운영 Redis와 동일 노드를 사용하므로 `FLUSHALL`은 절대 금지한다. 패턴을 명시해서 삭제한다.

### Waiting Room Token 처리

k6 E2E 시나리오에서 waiting room token을 매번 발급받으면 테스트가 복잡해진다.
두 가지 접근 방법이 있으며, 현재 시스템의 구현에 따라 선택한다:

- **방법 A (권장)**: 테스트 시작 전 각 VU에 대한 token을 사전 발급하고 k6 `data/tokens.json`으로 주입
- **방법 B**: 개발 환경에서 waiting room 검증을 bypass하는 내부 헤더를 지원할 경우 사용 (단, 운영 코드에 bypass 로직이 없어야 함)

현재 [`docs/07-performance-test-runbook.md`](../07-performance-test-runbook.md)의 사전 준비 절차에서 언급한 것처럼 "waiting token 또는 reservation/payment fixture 준비"가 필요하다.

### TossPayments sandbox 환경

결제 관련 시나리오(2, 3)는 TossPayments sandbox 환경을 사용한다:

- `test_sk_` 접두사 API 키 사용
- sandbox에서의 confirm 응답 시간은 실제 운영과 다를 수 있으므로 P99 결과 해석 시 주의
- sandbox 결제 후 실제 정산은 발생하지 않음

---

## 테스트 결과 해석법

### "5xx = 0이면 합격?" — 아니다

k6 결과에서 5xx = 0은 **필요조건이지 충분조건이 아니다**.

좌석 선점 경쟁 후 반드시 DB 정합성을 확인한다:

```sql
-- 같은 seatId에 HOLD가 2건 이상이면 optimistic lock이 제대로 동작하지 않은 것
SELECT seat_id, status, COUNT(*)
FROM ticketing_concert.seats
WHERE status = 'HOLD'
GROUP BY seat_id
HAVING COUNT(*) > 1;
-- 기대: 0건 반환
```

결제 중복 confirm 후:

```sql
-- 같은 order_id에 APPROVED가 2건 이상이면 idempotency가 뚫린 것
SELECT order_id, status, COUNT(*)
FROM ticketing_payment.payments
WHERE status = 'APPROVED'
GROUP BY order_id
HAVING COUNT(*) > 1;
-- 기대: 0건 반환
```

자세한 SQL 확인 쿼리는 [`docs/07-performance-test-runbook.md` 섹션 7](../07-performance-test-runbook.md)을 참고한다.

### k6 output과 Grafana panel을 함께 보는 법

k6가 실행되는 동안 Grafana에서 다음 panel을 열어둔다:

1. `http_server_requests_seconds` by service — 서비스별 P95/P99
2. `hikaricp.connections.pending` — 커넥션 대기 발생 여부
3. `tomcat.threads.busy` — 스레드 고갈 여부
4. Redis latency (Grafana Redis dashboard) — O(log N) 명령 지연
5. `spring_cloud_gateway_requests_seconds` — SCG 자체 오버헤드

테스트 시작/종료 시각을 Grafana annotation으로 추가하면 spike 구간을 명확하게 식별할 수 있다.
Jaeger에서는 P99 해당 request의 traceId를 찾아 어느 span이 전체 레이턴시를 지배했는지 확인한다.

### optimistic lock 충돌 허용 수준

시나리오 1에서 50 VU가 같은 좌석에 요청하면 49건의 충돌은 **정상**이다.
이것이 오류처럼 보이는 이유는 HTTP 관점에서 409 응답이기 때문이다.
실제로 충돌률 = (VU - 1) / VU 는 설계된 동작이다.

경계해야 할 경우:
- 409 외에 5xx가 섞이는 경우 (인프라 문제)
- 충돌 후 DB에 HOLD가 0건인 경우 (성공이 없음 — 락 구현 버그)
- 충돌 후 DB에 HOLD가 2건 이상인 경우 (락이 제대로 동작하지 않음)

### 재테스트 기준

다음 조건 중 하나에 해당하면 재테스트가 필요하다:

- 배포 후 각 시나리오의 P99 기준선 대비 20% 이상 증가
- HikariCP 커넥션 고갈 이벤트 발생 (pooling 설정 변경 필요 가능성)
- 5xx 비율이 0.1%를 초과
- DB에서 데이터 정합성 위반 발견 (즉시 배포 롤백 검토)

---

## Trade-offs

### k6 선택 이유와 대안 비교

| 도구 | 장점 | 단점 | 선택 근거 |
|------|------|------|----------|
| k6 | JavaScript 시나리오, 임계값 선언형, CLI 기반 CI 통합 용이 | JVM 기반 아님 (Gatling보다 Java 친화성 낮음) | 단순한 스크립트 작성, 빠른 피드백 |
| Gatling | Scala DSL, 상세 HTML 리포트, JVM 기반 | 학습 곡선, 설정 복잡 | 차기 고도화 후보 |
| JMeter | GUI 지원, 풍부한 플러그인 | XML 기반 설정, CI 통합 번거로움 | 채택하지 않음 |

### 현재 설계의 한계

1. **sandbox 의존성**: TossPayments sandbox 응답 시간이 실제 운영과 다를 수 있다. P99 임계값이 sandbox 기준으로 설정되어 있어 운영 배포 후 재보정이 필요하다.
2. **단일 인프라 공유**: 테스트 중 Redis와 MySQL을 운영 환경과 공유하면 테스트 트래픽이 다른 서비스에 영향을 줄 수 있다. 전용 테스트 환경이 없는 현재 구조의 제약이다.
3. **HikariCP 기본값**: ~10개 커넥션은 운영 환경에서 과소하다. 부하 테스트로 고갈 지점을 파악한 뒤 설정값을 조정해야 한다.
4. **PG stub 없음**: 결제 테스트에서 TossPayments 응답을 제어할 수 없으므로 deterministic한 실패 시나리오(PG 503, timeout) 재현이 어렵다.

---

## Planned Improvements

1. **PG stub/mock 계층 도입**: WireMock 또는 별도 stub 서버로 TossPayments 응답을 제어해 timeout, 5xx, 정상 응답을 deterministic하게 테스트한다.
2. **k6 시나리오 CI nightly job 통합**: [`docs/07-performance-test-runbook.md`](../07-performance-test-runbook.md) 섹션 10에서 언급한 것처럼 CountDownLatch 테스트와 함께 k6 시나리오도 nightly로 실행해 P99 기준선을 지속적으로 추적한다.
3. **Grafana annotation 자동화**: k6 실행 전후에 Grafana annotation API를 호출해 테스트 구간을 자동으로 표시한다.
4. **HikariCP 설정 보정**: 부하 테스트 결과를 기반으로 booking-app과 payment-app의 HikariCP max-pool-size 최적값을 도출한다.
5. **전용 테스트 스키마 격리**: 운영 스키마와 분리된 `ticketing_*_test` 스키마를 사용해 테스트 데이터가 운영 데이터를 오염시키지 않도록 한다.

---
