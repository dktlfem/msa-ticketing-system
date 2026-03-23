---
title: "Seat Locking Design: booking-app 좌석 선점 및 예약 확정"
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
- [Interview Explanation (90s version)](#interview-explanation-90s-version)

# Seat Locking Design: booking-app 좌석 선점 및 예약 확정

> 이 문서는 booking-app의 책임 경계, 좌석 선점 흐름, 예약 상태 전이, 동시성 제어 방식을 다룬다.
> concert-app의 SeatEntity(@Version) 상세와 DB 스키마는 [`docs/data/database-cache-design.md`](../../data/database-cache-design.md)를 참조한다.
> payment-app의 confirm 후 booking confirm 호출 흐름은 [`docs/services/payment/payment-architecture.md`](../payment/payment-architecture.md)를 참조한다.
> MSA 서비스 전체 의존 방향은 [`docs/architecture/overview.md`](../../architecture/overview.md)를 참조한다.

---

## Background

티켓팅 시스템에서 좌석은 희소 자원이다. 동시에 수천 명이 같은 좌석을 선택하려 할 때 중복 예약이 발생해서는 안 된다. 동시에 좌석 선점은 영구적이지 않아야 한다. 결제를 완료하지 않으면 일정 시간 후 선점이 해제되고 다른 사용자가 예약할 수 있어야 한다.

이 두 요구사항은 서로 긴장 관계에 있다.
- 중복 예약 방지를 위해 강한 락이 필요하다.
- 결제 미완료 선점 해제를 위해 TTL이 필요하다.

booking-app은 이 두 요구를 모두 처리하는 서비스다.

---

## Problem

좌석 선점 설계에서 해결해야 하는 문제:

1. **동시 선점 충돌**: 두 사용자가 동시에 같은 좌석을 선점 요청하면 하나만 성공해야 한다.
2. **선점 TTL 만료**: 결제를 완료하지 않은 선점은 5분 후 자동 해제돼야 한다.
3. **결제 확정과 예약 확정의 정합성**: PG 승인이 완료된 후에만 예약이 CONFIRMED 되어야 한다.
4. **경합 시나리오**: 좌석 선점 TTL이 만료되는 시점에 payment confirm이 동시에 들어오면 어떻게 처리하는가.
5. **보상 처리**: 예약 저장 실패 시 이미 진행된 좌석 선점을 어떻게 되돌리는가.

---

## Current Design

### 서비스 책임 경계

booking-app이 **직접 하는 것**:
- reservation 생성, 상태 관리 (PENDING/CONFIRMED/CANCELLED)
- 대기열 토큰 검증 (waitingroom-app 내부 API 호출)
- 좌석 선점/해제/확정 요청 (concert-app 내부 API 호출)
- 예약 만료 처리 (`ReservationManager.expireReservation`)

booking-app이 **하지 않는 것**:
- 좌석 상태 직접 변경 → concert-app에 위임
- 결제 상태 관리 → payment-app 소관
- 사용자 신원 검증 → SCG의 Auth-Passport 헤더에 의존 (PassportCodec.decode()로 userId 추출) <!-- 2026-03-22 ADR-0007 Phase 2 완료 반영 -->

### 서비스 간 의존 관계

```
booking-app
  ├── waitingroom-app (internal)
  │     ├── POST /internal/v1/waiting-tokens/validate  → 대기열 토큰 유효성 검증
  │     └── POST /internal/v1/waiting-tokens/consume   → 토큰 USED 처리
  └── concert-app (internal)
        ├── GET  /internal/v1/seats/{seatId}            → 좌석 상세 조회 (eventId 포함)
        ├── POST /internal/v1/seats/{seatId}/hold       → 좌석 AVAILABLE → HOLD
        ├── POST /internal/v1/seats/{seatId}/release    → 좌석 HOLD → AVAILABLE
        └── POST /internal/v1/seats/{seatId}/confirm    → 좌석 HOLD → SOLD
```

payment-app은 booking-app의 **upstream consumer**다. payment-app이 booking-app의 내부 API를 호출한다. 반대 방향은 없다.

### 낙관적 락 (Optimistic Lock) — concert-app SeatEntity

좌석 동시성 제어는 concert-app의 `SeatEntity`에서 담당한다.

```java
// SeatEntity.java
@Version
private Long version;
```

`SeatHolder.hold()` 실행 시:
```
UPDATE seats SET status = 'HOLD', version = version + 1
WHERE seat_id = ? AND version = ?
```

다른 트랜잭션이 먼저 version을 올렸다면 업데이트 건수가 0 → `OptimisticLockingFailureException` 발생.

**왜 비관적 락(SELECT FOR UPDATE)이 아닌가:**
비관적 락은 트랜잭션이 끝날 때까지 해당 row를 잠근다. 티켓팅 오픈 직후 수천 건의 동시 요청 중 대부분이 같은 좌석에 몰릴 때, SELECT FOR UPDATE는 후발 요청들을 모두 대기 상태로 만든다. 낙관적 락은 DB 락 없이 진행하다가 충돌 시점에만 실패를 돌려주므로 처리량(throughput)이 더 높다.

**트레이드오프:** 충돌이 빈번하면 재시도 비용이 발생한다. 티켓팅 오픈 직후 충돌률이 높은 구간에서는 클라이언트가 409 응답을 받고 재시도해야 한다.

---

## State / Flow

### 상태 전이 전체 그림

```
concert-app SeatEntity 상태:
  AVAILABLE ──[holdSeat]──► HOLD ──[releaseSeat]──► AVAILABLE
                                └──[confirmSeat]──► SOLD

booking-app ReservationEntity 상태:
  (없음) ──[createReservation]──► PENDING ──[cancelReservation / expireReservation]──► CANCELLED
                                        └──[confirmReservation]──► CONFIRMED
```

### 예약 생성 흐름 (createReservation)

```
ReservationManager.createReservation(userId, waitingToken, seatId)

1. concertSeatInternalClient.readSeat(seatId)
   → eventId 확보 (토큰 검증에 필요)

2. waitingRoomInternalClient.validateToken(waitingToken, userId, eventId)
   → valid=false 시 IllegalStateException

3. concertSeatInternalClient.holdSeat(seatId)
   → concert-app SeatHolder.hold() 실행
   → AVAILABLE → HOLD (낙관적 락)
   → OptimisticLockingFailureException 시 409 반환

4. [try 블록]
   reservation = Reservation(userId, seatId, PENDING, expiredAt=now+5min)
   reservationWriter.saveWithFlush(reservation)

5. waitingRoomInternalClient.consumeToken(waitingToken, "booking-service")
   → 토큰 USED 처리

[catch RuntimeException]
   concertSeatInternalClient.releaseSeat(seatId)  ← 보상 처리
   → HOLD → AVAILABLE 복원
```

**보상 처리 주의점**: reservation DB 저장 실패 시 releaseSeat를 호출하지만, releaseSeat 자체도 실패할 수 있다. 이 경우 좌석이 HOLD 상태로 고아가 된다. 현재 이 경우를 처리하는 자동화된 복구 로직이 없다. 5분 만료 후 expireReservation이 호출되면 releaseSeat가 재시도되므로 최종적으로는 해소된다.

### 예약 확정 흐름 (confirmReservation — payment-app 호출)

```
ReservationManager.confirmReservation(reservationId, paymentId)

1. reservationReader.read(reservationId)

2. reservationValidator.validateConfirmable(reservation)
   → reservation.isPending() → false 시 IllegalStateException
   → reservation.isExpired(now) → true 시 IllegalStateException

3. concertSeatInternalClient.confirmSeat(reservation.getSeatId())
   → HOLD → SOLD

4. Reservation.confirm()
   → status = CONFIRMED

5. reservationWriter.updateWithFlush(confirmedReservation, entity)
```

현재 DDL에 `payment_id` 컬럼이 없어 paymentId는 confirmReservation 내부에서 사용되지 않는다. 코드 주석에 `TODO: 후속 DDL 확장 시 confirmed_at, payment_id 연계 검토`가 있다.

### 예약 만료 흐름 (expireReservation)

```
ReservationManager.expireReservation(reservationId)

1. reservationReader.read(reservationId)

2. reservationValidator.validateExpirable(reservation)
   → isPending() 아니면 실패
   → isExpired(now) 아니면 실패

3. Reservation.cancel()  ← 현재 cancel()로 구현. 추후 expire() 분리 예정
   → status = CANCELLED

4. reservationWriter.updateWithFlush(...)

5. concertSeatInternalClient.releaseSeat(reservation.getSeatId())
   → HOLD → AVAILABLE
```

**만료 처리 호출 주체**: `ReservationManager.getExpiredPendingReservations(now)` 메서드가 스케줄러용으로 설계되어 있다. 현재 booking-app에 전용 스케줄러 클래스가 없다. 만료 처리가 실제로 어떻게 트리거되는지는 확인이 필요하다. **(확인 필요)**

`ReservationEntity`의 `idx_status_expired (status, expired_at)` 복합 인덱스는 `WHERE status='PENDING' AND expired_at < NOW()` 쿼리를 위한 것이다.

---

## Concurrency / Consistency Risks

### 리스크 1: 선점 TTL 만료와 payment confirm 경합

```
시나리오:
  T=0:00  사용자 A가 좌석 선점 (예약 PENDING, expiredAt=T+5:00)
  T=4:59  payment confirm 요청 진입
  T=5:00  만료 스케줄러가 이 예약을 만료 처리 시도

가능한 결과:
  A) confirm이 먼저 validateConfirmable 통과 → 예약 CONFIRMED
     만료 스케줄러는 PENDING 아님 → 무시 (정상)
  B) 만료가 먼저 실행 → 예약 CANCELLED, 좌석 AVAILABLE
     confirm이 validateConfirmable 실패 → R002 반환
     payment-app은 booking confirm 실패로 initiateRefund() 실행 → REFUNDED
```

**현재 보호 장치:**
- `validateConfirmable`과 `validateExpirable` 모두 `PENDING + not/expired` 조건 검증
- 각각 별도 트랜잭션에서 실행되므로 완전한 직렬화 보장 없음
- 운이 나쁘면 시나리오 B가 발생하고 결제가 자동 환불됨

**이것이 버그인가:** 설계 의도 범위 내다. 예약 만료 = 결제 기회 소멸. 결제 실패 시 자동 환불이 [보상 트랜잭션](../payment/payment-architecture.md)으로 처리된다.

### 리스크 2: holdSeat 성공 후 reservation save 실패

```
3. holdSeat(seatId) → 좌석 HOLD
4. [try] reservationWriter.saveWithFlush → DB 오류 발생
   [catch] releaseSeat(seatId) → 좌석 HOLD → AVAILABLE 복원
   releaseSeat도 실패 시 → 좌석 HOLD 상태로 잔류
```

5분 후 만료 처리가 이 고아 좌석을 처리하지 못한다 (reservation이 없으므로 expireReservation 대상이 아님). concert-app 차원의 별도 정리 로직이 필요하다. **(planned)**

### 리스크 3: 낙관적 락 충돌률

티켓팅 오픈 직후 수백 명이 동시에 같은 좌석을 선점 시도하면 1명 성공, 나머지 모두 `OptimisticLockingFailureException` → 409 반환. 충돌률이 높더라도 DB 부하는 낮다. 클라이언트가 다른 좌석을 선택해야 한다.

---

## Failure Scenarios

### 시나리오 1: concert-app holdSeat 실패

- concert-app 장애 또는 OptimisticLockingFailureException
- reservation 저장 없음 (try 블록 진입 전 실패)
- waitingRoom consumeToken 없음 (토큰 유효 상태 유지)
- 사용자는 다시 시도 가능

### 시나리오 2: reservation save 성공 후 consumeToken 실패

- 예약은 PENDING으로 저장됨
- 토큰이 USED 처리 안 됨 → 토큰 재사용 가능성
- 현재 보상 처리 없음: 예약은 유효하고 토큰만 비정상 상태
- 토큰은 10분 후 EXPIRED로 자동 전환 (waitingroom 스케줄러)

### 시나리오 3: confirmReservation 중 concert-app confirmSeat 실패

- 예약이 CONFIRMED로 변경되기 전에 외부 호출 실패
- 현재 확인 필요: concert-app confirmSeat와 booking DB update 순서
- `ReservationManager.confirmReservation` 코드 기준:
  `confirmSeat` → 성공 → `Reservation.confirm()` → `updateWithFlush`
  concert-app 장애 시: Reservation은 PENDING 상태 유지, 좌석은 HOLD 상태 유지
  payment-app에는 Exception 전파 → booking confirm 실패 → initiateRefund() 실행

### 시나리오 4: cancelReservation 중 releaseSeat 실패

```
cancelReservation:
  1. updateWithFlush(CANCELLED)  ← 먼저 DB CANCELLED flush
  2. concertSeatInternalClient.releaseSeat(seatId)  ← 외부 호출

releaseSeat 실패 시:
  - 현재 @Transactional 경계 확인 필요
  - ReservationWriter.updateWithFlush는 @Transactional 내부
  - releaseSeat은 트랜잭션 외부 호출
  - releaseSeat 실패 시 reservation은 CANCELLED, 좌석은 HOLD 상태로 잔류
```

---

## Observability

현재 구현된 로그:
- `ReservationManager`: 각 유즈케이스 진입/완료 시 INFO 로그 (별도 확인 필요)
- `SeatHolder.hold()`: `[SeatHolder] 좌석 점유 시도 - seatId: {}`, `[SeatHolder] 좌석 점유 성공 - seatId: {}`, `[SeatHolder] 동시성 충돌 발생 - 다른 사용자가 먼저 점유함. seatId: {}`

이상 상태 모니터링 쿼리:
```sql
-- PENDING + 만료된 예약 (스케줄러 처리 지연 감지)
SELECT COUNT(*) FROM ticketing_booking.reservations
WHERE status = 'PENDING' AND expired_at < NOW();

-- PENDING 장기 잔류 (정상 TTL 5분 초과)
SELECT reservation_id, user_id, seat_id, expired_at
FROM ticketing_booking.reservations
WHERE status = 'PENDING' AND expired_at < NOW() - INTERVAL 10 MINUTE;
```

planned:
- 낙관적 락 충돌률 Micrometer counter (`seat.hold.conflict`)
- 예약 만료 처리 건수 추적

---

## Trade-offs

| 결정 | 이유 | 잃은 것 |
|------|------|---------|
| 낙관적 락 (비관적 락 대신) | DB row lock 없이 높은 처리량 유지 | 충돌 시 클라이언트가 재선택 필요. 충돌률이 높으면 UX 저하 |
| reservation TTL 5분 | 선점 후 미결제 좌석 자동 해제 | payment confirm이 5분 내 완료되어야 함. TossPayments 응답 지연 시 경합 |
| concert-app에 좌석 상태 위임 | 좌석 도메인 단일 책임 유지 | booking-app의 모든 좌석 상태 변경이 외부 HTTP 호출에 의존 |
| holdSeat 실패 시 예약 저장 안 함 | 좌석 선점 없이 예약 기록 생성 방지 | 낙관적 락 충돌 시 클라이언트에 명시적 실패 반환 필요 |
| confirmReservation에 paymentId 미저장 | DDL에 컬럼 없음. MVP 우선 | 결제 ID와 예약 ID가 연결되지 않음. 추적 어려움 |

---

## Planned Improvements

1. **예약 만료 스케줄러 명확화** (planned): booking-app에 전용 스케줄러 클래스 추가. 현재 `getExpiredPendingReservations()` 유즈케이스가 누가 호출하는지 불명확.
2. **ReservationEntity에 paymentId 컬럼 추가** (planned): `confirmed_at`, `payment_id` 컬럼 추가. 결제-예약 연결 추적.
3. **고아 좌석 처리** (planned): reservation save 실패 후 releaseSeat 실패 시 HOLD 상태로 잔류하는 좌석을 주기적으로 정리.
4. **예약 상태에 EXPIRED 추가** (planned): 현재 만료된 예약이 CANCELLED로 처리됨. 취소와 만료를 구분하는 EXPIRED 상태 도입.
5. **낙관적 락 → 분산 락 전환 경로** (planned): `SeatHolder`가 별도 컴포넌트로 분리되어 있어 Redisson 기반 분산 락으로 교체 시 이 클래스만 수정하면 된다.
6. **outbox 패턴** (proposed): 예약 이벤트를 outbox 테이블에 저장하고, 이벤트 기반으로 concert-app에 전달. 현재 동기 HTTP 호출의 단일 장애점 문제 해소.

---

## Interview Explanation (90s version)

> booking-app의 핵심 설계 문제는 동시성과 TTL입니다. 수천 명이 동시에 같은 좌석을 선점하려 할 때, concert-app의 SeatEntity에 @Version을 붙여 낙관적 락으로 처리합니다. 선점 성공은 1명뿐이고, 나머지는 409를 받아 다른 좌석을 선택합니다. 비관적 락(SELECT FOR UPDATE) 대신 낙관적 락을 선택한 이유는 처리량 때문입니다. 비관적 락은 한 명이 처리되는 동안 나머지를 DB에서 대기시키지만, 낙관적 락은 충돌 시점에만 실패를 반환하므로 DB 부하가 낮습니다.
>
> 예약 TTL은 5분입니다. 결제를 완료하지 않으면 5분 후 만료 처리가 좌석을 HOLD에서 AVAILABLE로 되돌립니다. 결제 confirm이 만료 직전에 들어오는 경합 시나리오는 validateConfirmable과 validateExpirable이 각각 PENDING + not expired 조건을 검증해 처리합니다. 만료가 먼저 처리되면 payment-app이 booking confirm 실패를 받고 자동 환불 보상 트랜잭션을 실행합니다.

---

*최종 업데이트: 2026-03-16 | ReservationManager.java, SeatHolder.java, ReservationEntity.java 기준*
