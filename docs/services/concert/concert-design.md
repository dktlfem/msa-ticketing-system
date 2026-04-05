# Concert-App 설계

> **관련 문서**:
> - 시스템 전체 서비스 경계: [`docs/architecture/why-msa.md`](../../architecture/why-msa.md)
> - 내부 API 계약: [`docs/api/api-spec.md`](../../api/api-spec.md) — 섹션 3 (외부), 섹션 8 (내부)
> - DB 스키마 전체: [`docs/data/database-cache-design.md`](../../data/database-cache-design.md)
>
> **코드 위치**: `concert-app/src/main/java/com/koesc/ci_cd_test_app/`

---

## 목차

- [Background](#background)
- [Problem](#problem)
- [좌석 상태 전이 설계](#좌석-상태-전이-설계)
- [낙관적 락(@Version) 설계](#낙관적-락version-설계)
- [SeatHolder — 동시성 제어 컴포넌트](#seatholder--동시성-제어-컴포넌트)
- [L1 캐시 설계 (Caffeine)](#l1-캐시-설계-caffeine)
- [booking-app과의 내부 API 계약](#booking-app과의-내부-api-계약)
- [Trade-offs](#trade-offs)
- [Failure Scenarios](#failure-scenarios)

---

## Background

concert-app은 공연(Event), 회차(EventSchedule), 좌석(Seat) 정보를 관리하는 서비스다. 시스템 내에서 다른 서비스를 호출하지 않는 leaf service다.

**이 서비스가 소유하는 것**:
- 좌석 재고 상태 (`AVAILABLE / HOLD / SOLD`)
- 공연 카탈로그 및 회차 정보
- 좌석 상태 변경 권한 (hold, release, confirm)

**이 서비스가 하지 않는 것**:
- 예약 생성/관리 → booking-app
- 결제 처리 → payment-app
- 대기열 관리 → waitingroom-app

---

## Problem

티켓팅 시스템의 좌석 선점에서 해결해야 하는 핵심 문제:

**동일 좌석에 대한 동시 요청**: 오픈 직후 수백 명이 같은 좌석을 동시에 선점 시도할 때, 단 한 명만 성공하고 나머지는 실패해야 한다.

두 가지 접근법의 비교:

| 방식 | 처리 방식 | Throughput | 데이터 정합성 |
|------|---------|-----------|------------|
| 비관적 락 (Pessimistic Lock) | DB 레벨 락 (`SELECT FOR UPDATE`) | 낮음. 락 대기 중 다른 트랜잭션이 줄을 섬 | 강함 |
| 낙관적 락 (Optimistic Lock) | 충돌 감지 후 실패 처리 (`@Version`) | 높음. 충돌 없으면 락 없이 처리 | 충분 |

티켓팅에서 같은 좌석을 동시에 선점하는 경우는 실제로 드물다 (좌석 수가 많고, 요청은 분산된다). "설마 동시에 같은 좌석을 선택하겠어"라는 낙관적 가정이 현실에 더 맞고, 충돌 시 409로 클라이언트에게 재시도를 안내하면 된다.

---

## 좌석 상태 전이 설계

```
        예약 생성 요청
             │
             ▼
        [AVAILABLE]
             │ hold()
             ▼
          [HOLD]
          /     \
  confirm()    release()
      │             │
      ▼             ▼
   [SOLD]      [AVAILABLE]
  (종료)       (재선택 가능)
```

**SeatStatus enum** (`domain/SeatStatus.java`):

| 상태 | 의미 | 전이 출발 |
|------|------|---------|
| `AVAILABLE` | 예약 가능 | → HOLD (hold) |
| `HOLD` | 임시 점유 (결제 대기 중) | → SOLD (confirm), → AVAILABLE (release) |
| `SOLD` | 판매 완료 | 최종 상태 |

**상태 전이 트리거**:

| 이벤트 | 전이 | 호출 주체 |
|--------|------|---------|
| 예약 생성 | AVAILABLE → HOLD | booking-app `/internal/v1/seats/{id}/hold` |
| 예약 취소 / TTL 만료 | HOLD → AVAILABLE | booking-app `/internal/v1/seats/{id}/release` |
| 결제 확정 | HOLD → SOLD | booking-app `/internal/v1/seats/{id}/confirm` (payment-app 확정 이후) |

---

## 낙관적 락(@Version) 설계

**SeatEntity** (`storage/entity/SeatEntity.java`):

```java
@Version
private Long version;
```

JPA가 UPDATE 쿼리를 실행할 때 자동으로 `WHERE version = ?` 조건을 추가한다.

```sql
-- JPA가 생성하는 실제 쿼리
UPDATE ticketing_concert.seats
SET status = 'HOLD', version = version + 1
WHERE seat_id = ? AND version = ?
```

동시에 두 트랜잭션이 같은 버전으로 UPDATE를 시도하면, 먼저 커밋된 쪽이 version을 올리고, 나중 트랜잭션의 WHERE 조건이 불일치해 0건이 업데이트된다. JPA는 이를 `OptimisticLockingFailureException`으로 변환한다.

**SeatWriter — 두 가지 update 메서드** (`implement/writer/SeatWriter.java`):

| 메서드 | 용도 | flush |
|--------|------|-------|
| `update(seat, expectedVersion)` | 단순 업데이트 (Dirty Checking) | 없음 |
| `updateWithFlush(seat, expectedVersion)` | 낙관적 락 조기 검출 | `saveAndFlush` — 트랜잭션 내에서 즉시 SQL 실행 |

hold 작업은 `updateWithFlush`를 사용해 같은 트랜잭션 안에서 충돌을 즉시 감지한다. 트랜잭션이 끝날 때까지 기다리지 않으므로 커넥션 점유 시간을 줄인다.

version 불일치 시 `OptimisticLockingFailureException` 발생 → InternalSeatController에서 409 SEAT_CONCURRENT_CONFLICT로 매핑.

---

## SeatHolder — 동시성 제어 컴포넌트

**위치**: `implement/manager/SeatHolder.java`

SeatManager에서 동시성 제어 로직을 분리한 전용 컴포넌트다.

```
hold(seatId)
  └── [TX 시작]
        ├── seatReader.read(seatId)          — DB 조회
        ├── seatValidator.validateAvailable() — AVAILABLE 상태 검증
        ├── seat.hold()                       — 도메인 상태 변경
        └── seatWriter.updateWithFlush(seat, version)
              ├── 성공: HOLD 상태 반환
              └── OptimisticLockingFailureException → 상위 전파
      [TX 종료]

release(seatId)
  └── [TX 시작]
        ├── seatReader.read(seatId)
        ├── seat.release()                    — 도메인 상태 변경
        └── seatWriter.update(seat, version)  — flush 없음 (HOLD→AVAILABLE은 경합 없음)
      [TX 종료]
```

**왜 SeatHolder를 별도 클래스로 분리했는가**:

향후 Redisson 분산락으로 교체 시 SeatHolder 내부만 수정하면 된다. Business Service(`SeatInternalService`)는 `seatHolder.hold(seatId)`를 호출하는 인터페이스만 알고 있으므로, 동시성 전략 교체가 Service 레이어에 영향을 주지 않는다.

---

## L1 캐시 설계 (Caffeine)

**위치**: `global/config/CacheConfig.java`

공연 정보(`Event`)처럼 변경 빈도가 낮고 읽기 비중이 높은 데이터를 WAS 메모리(L1)에서 직접 서빙한다.

```java
Caffeine.newBuilder()
    .expireAfterWrite(10, TimeUnit.MINUTES)  // 10분 TTL
    .maximumSize(1000)                        // 최대 1,000개
    .recordStats()                           // 히트율 수집
```

**L1/L2 하이브리드 전략 (현재 적용 범위)**:

현재 구현: Caffeine(L1) → DB

계획 (planned): Caffeine(L1) miss → Redis(L2) → DB

**캐시 대상 데이터**:

| 데이터 | 변경 빈도 | 캐시 적합성 |
|--------|---------|----------|
| Event (공연 정보) | 낮음 | ✅ 적합 |
| EventSchedule (회차) | 낮음 | ✅ 적합 |
| Seat.status | 높음 (HOLD/SOLD 빈번) | ❌ 캐시하지 않음 — DB가 source of truth |

좌석 상태는 캐시하지 않는다. 낙관적 락이 DB 버전을 기준으로 동작하므로, 캐시된 stale status를 믿고 hold를 시도하면 불필요한 충돌이 증가한다.

---

## booking-app과의 내부 API 계약

**InternalSeatController** (`api/controller/InternalSeatController.java`):

경로 기준: `/internal/v1/seats/**` (SCG 외부 차단, 서비스 간 직접 호출 전용)

### GET `/internal/v1/seats/{seatId}`

좌석 상세 조회. payment-app이 결제 시 좌석 가격을 조회할 때도 사용.

**응답** `SeatDetailResponse`:

```json
{
  "seatId": 10001,
  "scheduleId": 1,
  "eventId": 1,
  "seatNo": 42,
  "price": 150000.00,
  "status": "HOLD",
  "version": 3
}
```

참고: `seats` 테이블에 `event_id` 컬럼이 없다. `schedule_id → event_id` 조회를 한 번 더 수행해서 응답에 포함한다.

### POST `/internal/v1/seats/{seatId}/hold`

AVAILABLE → HOLD 상태 전이. 낙관적 락 충돌 시 409 반환.

**요청**: `{ "expectedStatus": "AVAILABLE" }`

**에러 응답**:

| 상황 | HTTP |
|------|------|
| 좌석 없음 | 404 |
| `expectedStatus` 값 오류 | 400 |
| AVAILABLE 아님 (이미 HOLD/SOLD) | 409 |
| 낙관적 락 충돌 | 409 `SEAT_CONCURRENT_CONFLICT` |

### POST `/internal/v1/seats/{seatId}/release`

HOLD → AVAILABLE 상태 전이. 예약 취소 또는 TTL 만료 시 booking-app이 호출.

### POST `/internal/v1/seats/{seatId}/confirm`

HOLD → SOLD 상태 전이. 결제 확정 후 booking-app이 호출.

---

## Trade-offs

| 결정 | 이유 | 트레이드오프 |
|------|------|------------|
| 낙관적 락 vs 비관적 락 | Throughput 우선. 동일 좌석 동시 충돌 확률 낮음 | 충돌 발생 시 클라이언트가 재시도해야 함 |
| SeatHolder 분리 | 동시성 전략 교체 용이성 | 클래스 수 증가, 코드 레이어 추가 |
| Caffeine L1 캐시 (좌석 상태 제외) | 변경 빈도 높은 데이터를 캐시하면 stale read 위험 | 좌석 상태는 항상 DB I/O 발생 |
| `updateWithFlush` (hold에만 적용) | 동일 TX 안에서 충돌 즉시 감지 | 단순 release에는 불필요한 flush 없이 성능 유지 |
| `findBySeatIdAndVersion` 쿼리 | expectedVersion을 WHERE 조건에 포함 → 영속성 컨텍스트 밖에서도 낙관적 락 의미 구현 | 조회 쿼리가 추가됨 |

---

## Failure Scenarios

### 낙관적 락 충돌 폭증 (피크 트래픽 시)

- 동일 좌석에 대해 동시 요청이 많으면 409 `SEAT_CONCURRENT_CONFLICT` 급증
- booking-app이 409를 받으면 예약 실패 처리 → reservation이 저장되지 않음
- 사용자가 다른 좌석을 선택하거나 재시도해야 함

**현재 미처리**: booking-app 레벨에서의 자동 재시도 로직 없음 **(planned)**.

### concert-app 장애 시

- booking-app이 seat HOLD 요청 실패 → reservation 저장 안 됨 (정상 실패)
- payment-app이 seat 가격 조회 실패 → payment 생성 안 됨 (정상 실패)
- 사용자에게 503 반환

### 좌석 HOLD 후 booking-app 장애 (보상 누락)

- seat는 HOLD 상태인데 reservation이 없는 상태
- booking-app의 만료 스케줄러(5분 주기)가 PENDING + expired 예약을 탐지하고 seat RELEASE를 호출
- 만료 스케줄러가 동작하지 않으면 해당 좌석이 영구 HOLD 상태로 잔류 위험

---

*최종 업데이트: 2026-03-19 | concert-app 소스 코드 기반 작성*
