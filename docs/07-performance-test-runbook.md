# 07. 성능 / 동시성 테스트 및 Enablement Runbook

## 1. 왜 이 문서가 중요한가

이 프로젝트는 티켓팅 도메인입니다.  
즉, 일반 CRUD보다 **동시성 제어와 순간 트래픽 대응**이 더 중요합니다.

핵심 경쟁 구간:

- 대기열 polling
- 좌석 HOLD 경쟁
- 동일 reservation에 대한 payment confirm 중복
- 결제 취소 중복
- 예약 만료와 사용자 취소의 경합

---

## 2. 테스트 원칙

### 2.1 응답만 보면 안 된다

예를 들어 payment confirm 동시 요청 100개에서

- 200
- 409

둘 다 정상일 수 있습니다.

진짜 중요한 것은 아래입니다.

1. PG 실승인이 1번만 발생했는가
2. payment row가 기대 상태로 남았는가
3. reservation/seat 상태가 최종적으로 일관적인가
4. failureCode가 남아 운영자 재처리가 필요한 케이스가 구분되는가

### 2.2 Black-box + White-box를 같이 본다

- Black-box: HTTP 응답 코드, latency, timeout
- White-box: DB 상태, Redis key 상태, log, trace, metric

---

## 3. 100 스레드 CountDownLatch 시나리오

## 3.1 공통 패턴

- `ready` latch: 모든 스레드가 출발선에 도달했는지 확인
- `start` latch: 동시에 출발
- `done` latch: 모든 스레드 종료 대기

이 구조를 써야 진짜 “동시에 때리는” 상황을 재현할 수 있습니다.

---

## 4. 권장 시나리오

## 시나리오 A. 같은 좌석에 100명 예약 생성

### 준비

- 동일 `seatId`
- 서로 다른 `userId`
- 각 사용자별 유효한 waiting token 준비

### 기대 결과

- 성공 1건
- 나머지는 409 또는 비즈니스 실패
- 최종적으로 `seats.status`는 `HOLD` 1건만 반영
- `reservations`는 1건만 PENDING 또는 후속으로 CONFIRMED

### 확인 포인트

- optimistic lock 예외가 정상적으로 비즈니스 오류로 매핑되는가
- 실패한 요청이 좌석 상태를 오염시키지 않는가

---

## 시나리오 B. 같은 reservation에 confirm 100개

### 준비

- 동일 `reservationId`
- 동일 `orderId`, `paymentKey`, `amount`
- 동일 `Idempotency-Key`

### 기대 결과

- 200 또는 409로만 수렴
- PG approve 실호출 1회 수준
- `payments.status = DONE`
- `reservations.status = CONFIRMED`
- `seats.status = SOLD`

### 확인 포인트

- Redis idempotency `processing` 키 경쟁 구간
- `response` 캐시 재사용
- booking confirm 중복 시 정합성 유지

---

## 시나리오 C. cancel 100개

### 준비

- 동일 `paymentId`
- 동일 또는 서로 다른 cancel idempotency key 패턴 비교

### 기대 결과

- 실제 환불 1회
- 최종 `payments.status = CANCELED`
- 최종 `reservations.status = CANCELLED`
- 최종 `seats.status = AVAILABLE`

### 확인 포인트

- cancel replay 시 failureCode가 비정상적으로 남지 않는가
- booking cancel-confirmed와 seat release가 중복으로 깨지지 않는가

---

## 5. 권장 판정 기준

## 5.1 응답 기준

- 5xx가 없어야 함
- timeout 비율이 낮아야 함
- 예상된 200/409 외의 status가 없어야 함

## 5.2 데이터 기준

- 동일 seatId에서 HOLD/SOLD가 1건만 유지되는가
- 동일 reservationId에서 payment가 1건만 활성 상태인가
- duplicate unique key 오류가 사용자 계약 밖으로 노출되지 않는가

## 5.3 관측성 기준

- 특정 test run의 correlationId 또는 태그로 Kibana/Jaeger 검색 가능해야 함
- payment confirm PG approve count와 실제 성공 수가 일치해야 함
- Grafana에서 latency spike와 Redis/DB 부하가 함께 보이는지 확인

---

## 6. 실행 체크리스트

### 사전 준비

- MySQL / Redis / SCG / 서비스 전체 기동
- 테스트용 event/schedule/seat/user 데이터 준비
- waiting token 또는 reservation/payment fixture 준비
- 필요 시 Toss stub/mock 또는 sandbox 준비

### 실행 중

- 테스트 시작 시점 타임스탬프 기록
- correlationId prefix 또는 test run id 사용
- 결과를 endpoint/응답코드/latency별로 저장

### 실행 후

- DB 최종 상태 확인
- Redis key 정리 확인
- Jaeger trace 샘플 확인
- Kibana에서 오류 로그 여부 확인
- Grafana에서 p95/p99, Redis, DB 리소스 확인

---

## 7. SQL 확인 예시

### 좌석 상태 확인

```sql
SELECT seat_id, schedule_id, seat_no, status, version
FROM ticketing_concert.seats
WHERE seat_id = 10001;
```

### 예약 상태 확인

```sql
SELECT reservation_id, user_id, seat_id, status, reserved_at, expired_at
FROM ticketing_booking.reservations
WHERE reservation_id = 90001;
```

### 결제 상태 확인

```sql
SELECT payment_id, reservation_id, user_id, order_id, payment_key, status,
       amount, balance_amount, failure_code, failure_message, approved_at, canceled_at
FROM ticketing_payment.payments
WHERE payment_id = 50001;
```

---

## 8. 트러블슈팅 문서에 꼭 넣을 것

### 증상: 5xx가 섞여 나온다

- gateway access log의 correlationId 확인
- Jaeger에서 최초 실패 span 확인
- DB lock wait timeout / deadlock 확인
- Redis timeout 여부 확인

### 증상: 응답은 200인데 PG가 여러 번 호출된다

- Idempotency-Key 재사용 방식 확인
- request hash가 요청마다 달라지는지 확인
- Redis `processing` setIfAbsent 경쟁 확인
- external client retry 정책 확인

### 증상: 결제는 취소됐는데 좌석이 안 풀린다

- `booking cancel-confirmed` 호출 실패 여부 확인
- `failureCode = PAYMENT_RESERVATION_SYNC_FAILED` 남았는지 확인
- seat release internal API 응답 확인

---

## 9. Enablement 산출물 권장 목록

- `concurrency-scenarios.md`
- `how-to-run-concurrency-test.md`
- `how-to-read-results.md`
- `troubleshooting/concurrency.md`
- `db-check-queries.sql`
- `grafana-dashboard-links.md`

이런 파일이 있으면 개인 프로젝트라도 **현업형 문서화 수준**으로 보입니다.

---

## 10. 다음 단계

- CountDownLatch 기반 JVM 테스트 외에 `k6` 또는 `Gatling`로 HTTP 레벨 부하 테스트 추가
- PG sandbox/stub 계층을 분리해 deterministic한 payment confirm 테스트 구성
- CI의 nightly job으로 동시성 회귀 테스트 실행
- 테스트 결과를 Grafana annotation 또는 리포트로 보존