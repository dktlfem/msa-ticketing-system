---
title: "Queue Design: waitingroom-app 대기열 설계"
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

# Queue Design: waitingroom-app 대기열 설계

> 이 문서는 waitingroom-app의 구조, Redis Sorted Set 기반 대기열 흐름, 토큰 생명주기, 장애 시나리오를 다룬다.
> MSA 서비스 전체 구조는 [`docs/architecture/why-msa.md`](../../architecture/why-msa.md)를 참조한다.
> Redis 키 패턴 전체 목록은 [`docs/data/database-cache-design.md`](../../data/database-cache-design.md)를 참조한다.
> 관측성 설정(traceId, Prometheus, Grafana)은 [`docs/observability/observability.md`](../../observability/observability.md)를 참조한다.

---

## Background

티켓팅 오픈 직후 수천 명이 동시에 좌석 선점을 시도한다. 이 트래픽을 모든 서비스가 그대로 받으면:
- concert-app 좌석 조회: DB 풀 고갈
- booking-app reservation save: 동시 트랜잭션 충돌
- payment-app: PG 연동 과부하

waitingroom-app을 별도 서비스로 분리한 이유는 이 트래픽을 흡수하는 완충 계층이 필요하기 때문이다.

**왜 waitingroom-app이 별도 서비스여야 하는가:**
- 대기열 로직(Redis Sorted Set + rate limiting)과 예약 로직(DB 트랜잭션)은 생명주기가 다르다.
- 대기열이 예약 서비스와 같은 프로세스에 있으면 대기열 Redis 부하가 예약 DB 트랜잭션에 영향을 준다.
- waitingroom-app만 집중 스케일아웃하면 나머지 서비스는 대기열을 통과한 허용 인원만 처리하면 된다.

---

## Problem

대기열 설계에서 해결해야 하는 문제:

1. **중복 진입 방지**: 같은 사용자가 같은 이벤트 대기열에 두 번 들어가면 안 된다.
2. **진입 순서 보장**: 먼저 들어온 사람이 먼저 통과해야 한다.
3. **throughput 제어**: 대기열을 통과하는 사용자 수를 초당 100명으로 제한해 downstream 서비스를 보호한다.
4. **토큰 만료**: 대기열을 통과했으나 예약을 완료하지 않은 사용자의 토큰을 만료시켜야 한다.
5. **Redis 장애**: Redis가 다운되면 대기열 전체가 동작하지 않아야 하는가, 아니면 일부 degraded mode가 가능한가.

---

## Current Design

### 데이터 저장 계층 구조

waitingroom-app은 두 개의 저장소를 사용한다.

```
Redis Sorted Set   → 대기열 순번 관리 (진입~통과 구간)
MySQL DB           → 통과 후 발급된 토큰 영속화 (active_tokens 테이블)
```

**왜 이 구분인가:**
- 순번은 실시간 계산이 필요하고 이벤트 종료 후 자동 소멸이 자연스럽다 → Redis TTL 기반
- 발급된 토큰은 booking-app이 검증해야 한다 → DB 영속화 필요

### Redis Sorted Set 구조

```
KEY:    waiting-room:event:{eventId}
TYPE:   Sorted Set
SCORE:  epochMilli (System.currentTimeMillis() at 진입 시각)
MEMBER: userId (String)

예시:
  waiting-room:event:1001
    userId=5001 → score=1710000000100  (가장 먼저 진입)
    userId=5002 → score=1710000000200
    userId=5003 → score=1710000000300
```

score가 작을수록 먼저 진입한 사용자다. `ZRANK` 명령은 0-based rank를 반환한다 (rank=0 → 첫 번째).

### Redis ZADD NX Lua 스크립트 (중복 진입 방지)

```lua
return redis.call('ZADD', KEYS[1], 'NX', ARGV[1], ARGV[2])
```

- `NX`: member가 이미 존재하면 score를 덮어쓰지 않고 0 반환
- Lua 스크립트로 원자적 실행 → Java 레벨 분기 없이 중복 진입 차단
- 반환값 1: 신규 진입, 0: 이미 존재

**Reactive(WebFlux) 사용 이유:**
waitingroom-app은 `ReactiveRedisTemplate`을 사용한다. 대기열 polling은 수천 명이 동시에 주기적으로 `/status`를 호출하는 패턴이다. Spring MVC의 thread-per-request 모델은 이 패턴에서 Tomcat thread 고갈이 발생한다. Netty 기반 비동기 I/O는 스레드 수와 무관하게 동시 연결을 처리할 수 있어 polling 집중 트래픽에 적합하다.

### Rate Limiter (throughput 제어)

`WaitingRoomRateLimiter.isAllowedToEnter(eventId)`:

```
KEY: rate_limit:event:{eventId}:{epochSecond}
TTL: 2초
VALUE: INCR (카운터)
MAX: 100 / 초
```

```
INCR rate_limit:event:1001:1710000000
  count=1 → EXPIRE 2  (첫 호출 시 TTL 설정)
  count <= 100 → true (통과 허용)
  count > 100  → false (통과 거부)
```

초당 100명이 통과 허용 상한이다. 이 값을 초과하면 대기열 polling에서 아직 통과 불가 응답을 반환한다.

**TTL을 2초로 설정한 이유:** 초 단위 key가 정확히 1초에 만료되면 직전 초의 카운터가 남아있는 상태에서 새 초 카운터가 0으로 시작한다. 2초 TTL은 key 만료가 초 경계와 겹치는 edge case에서 이전 초 카운터가 살아있는 시간을 허용한다.

### 대기열 통과 후 토큰 발급 — DB 영속화

`WaitingRoomManager.createToken(eventId, userId)`:

```
1. findTokenByUser(userId, eventId) → 기존 ACTIVE 토큰 있으면 재사용
2. WaitingToken 생성
   - tokenId: UUID (예측 불가능한 값)
   - status: ACTIVE
   - expiredAt: issuedAt + 10분
3. waitingRoomWriter.save(token) → MySQL active_tokens INSERT
4. waitingRoomWriter.removeFromQueue(eventId, userId) → Redis ZREM
```

**UUID PK를 선택한 이유:** Long(Auto Increment) PK는 순차적이어 예측 가능하다. 토큰은 클라이언트에 노출되는 값이므로 순차 값이면 공격자가 타인의 토큰을 유추할 수 있다.

### 토큰 소비 (consumeToken — booking-app 호출)

`WaitingRoomWriter.consumeIfActive(tokenId, now)`:
```sql
UPDATE active_tokens
SET status = 'USED'
WHERE token_id = ? AND status = 'ACTIVE' AND expired_at > ?
```

WHERE 조건에 `status='ACTIVE' AND expired_at > now`를 포함시켜 만료된 토큰으로 예약을 시도하는 것을 차단한다. 이미 USED 처리된 토큰으로의 재사용도 차단된다.

### 토큰 만료 스케줄러

`WaitingRoomScheduler.cleanupExpiredTokens()`:
```
@Scheduled(cron = "0 0/5 * * * *")  ← 5분마다 실행
DELETE FROM active_tokens
WHERE expired_at < NOW() OR status IN ('EXPIRED', 'USED')
```

만료된 토큰(시간 초과) 또는 이미 소비된 토큰(USED)을 정리한다.

---

## State / Flow

### 사용자 흐름

```
[1] POST /api/v1/waiting-room/join
    waitingRoomService.joinQueue(eventId, userId)
      → WaitingRoomWriter.addToToken(eventId, userId) : Redis ZADD NX
      → WaitingRoomReader.getRank(eventId, userId) : Redis ZRANK
    응답: {status: WAITING, rank: 1023, estimatedWaitSeconds: 11}

[2] GET /api/v1/waiting-room/status (polling)
    waitingRoomService.getQueueStatus(eventId, userId)
      → WaitingRoomReader.getRank(eventId, userId)
      → rank == 0 또는 통과 조건 충족 시:
          WaitingRoomRateLimiter.isAllowedToEnter(eventId) → true 시
          WaitingRoomManager.createToken(eventId, userId)
            → UUID 토큰 발급 → DB INSERT → Redis ZREM
      응답:
        대기 중: {status: WAITING, rank: 500, estimatedWaitSeconds: 6}
        통과:    {status: ACTIVE, tokenId: "uuid-...", expiredAt: ...}

[3] 클라이언트가 tokenId를 가지고 POST /api/v1/reservations 호출 (booking-app)

[4] booking-app → waitingroom-app internal: validateToken(tokenId, userId, eventId)
      WaitingRoomManager.verifyToken(tokenId)
        → DB: findById(tokenId) → ACTIVE + not expired 검증

[5] booking-app → waitingroom-app internal: consumeToken(tokenId)
      WaitingRoomWriter.consumeIfActive(tokenId, now)
        → DB UPDATE: ACTIVE → USED (원자적)
```

### 대기 시간 추정 (WaitingRoomCalculator)

```java
// TIME_PER_USER = 0.01 (초당 100명 처리 가정)
public Long calculate(Long rank) {
    return (long) Math.ceil(rank * 0.01);
}
```

rank=1000이면 예상 대기 시간 = 10초. 이 추정은 rate limiting 설정값(100/s)에 정비례하며, 실제 처리 속도와 다를 수 있다.

### Token 상태 전이

```
(DB 없음) ──[createToken]──► ACTIVE ──[consumeIfActive]──► USED
                                   └──[스케줄러/만료]──► EXPIRED
                                   └──[markExpiredIfActive]──► EXPIRED
```

---

## Concurrency / Consistency Risks

### 리스크 1: Redis가 source of truth이나 영속성 보장 없음

대기열 순번(Redis Sorted Set)은 영속 저장이 아니다. Redis 재시작 시 대기열이 소멸된다. 이 데이터를 DB에 동시에 저장하지 않는다.

**현재 판단**: 대기열은 이벤트 단위 임시 데이터다. 재시작 시 대기열이 초기화돼도 사용자가 다시 join하면 된다. 단, Redis가 재시작되는 동안 진행 중인 대기열 통과 요청이 유실된다.

RDB/AOF persistence 설정 여부는 현재 확인되지 않았다. **(확인 필요)**

### 리스크 2: ZADD NX와 DB 토큰 발급 사이의 비원자성

```
[1] Redis ZREM (대기열에서 제거)
[2] DB INSERT active_tokens
```

현재 구현에서 [1]과 [2] 사이에 장애가 발생하면:
- 사용자가 Redis 대기열에서 제거됐으나 DB 토큰이 없는 상태
- 사용자는 대기열에 재진입해야 함
- `createToken`에서 `findTokenByUser` → 기존 ACTIVE 토큰 재사용 로직이 있으나, 이 경우 토큰 자체가 없으므로 해당 없음

### 리스크 3: Rate Limiter 초 경계 race condition

`rate_limit:event:{eventId}:{epochSecond}` key는 epochSecond 단위다. 초 경계에서 INCR가 새 key에 발생하면 EXPIRE 설정 전 다른 요청이 추가 INCR할 수 있다. Lua 스크립트로 INCR + EXPIRE를 묶지 않았다.

현재 코드:
```java
return reactiveRedisTemplate.opsForValue().increment(key)
    .flatMap(count -> {
        if (count != null && count == 1) {
            return reactiveRedisTemplate.expire(key, Duration.ofSeconds(2)).thenReturn(count);
        }
        return Mono.just(count);
    })
```

count=1일 때만 EXPIRE를 설정한다. INCR와 EXPIRE 사이에 다른 요청의 INCR가 발생해도 카운터 정확도에는 영향이 없다. 다만 EXPIRE 설정 전에 Redis가 재시작되면 해당 key가 만료 없이 영구 잔류할 수 있다. TTL 2초이므로 실질적 영향은 낮다.

### 리스크 4: 스케줄러 단일 인스턴스 가정

`WaitingRoomScheduler`는 5분마다 만료 토큰을 삭제한다. waitingroom-app이 여러 인스턴스로 스케일아웃되면 모든 인스턴스가 동시에 DELETE를 실행한다. 중복 삭제는 멱등하므로 데이터 손상은 없지만 불필요한 DB 부하가 발생한다. **(planned: ShedLock 또는 @Profile("scheduler") 분리)**

---

## Failure Scenarios

### 시나리오 1: Redis 단일 노드 장애

- `addToToken`, `getRank`, `isAllowedToEnter` 모두 실패
- 대기열 기능 전면 중단 → 5xx 반환
- 이미 발급된 DB 토큰은 유효 (booking-app 검증 가능)
- 신규 대기열 진입 불가 → 오픈 이벤트 중 치명적

**현재 fallback 없음.** Redis 장애 = 대기열 서비스 다운. **(planned: Redis Sentinel 또는 Cluster)**

### 시나리오 2: DB 장애 시 토큰 발급 불가

- 대기열 순번 조회(Redis)는 동작
- 대기열 통과 후 토큰 발급(DB INSERT) 실패
- 사용자가 대기열을 통과했으나 토큰을 받지 못함
- Redis에서는 이미 ZREM으로 제거됨 → 재진입 필요

### 시나리오 3: polling 과부하

- 수천 명이 동시에 `/status`를 polling
- 각 요청이 `ZRANK` Redis 명령 실행
- Redis O(log N) 연산이지만 동시 수천 건
- ReactiveRedisTemplate + Netty 기반이므로 MVC보다 resilient

**현재 polling 간격 제어 없음.** 클라이언트가 1초마다 polling하면 과부하 가능. **(planned: Server-Sent Events 또는 polling 간격 응답 포함)**

### 시나리오 4: 토큰 만료와 booking 요청 경합

```
T=9:50  토큰 발급 (expiredAt = T+10:00)
T=9:59  booking-app validateToken → ACTIVE + not expired → 통과
T=10:00 스케줄러 실행 → ACTIVE 상태 + expired_at < now → DELETE

consumeToken이 validateToken 직후 실행되면:
  consumeIfActive: UPDATE WHERE status='ACTIVE' AND expired_at > now
  T=9:59 실행 시 → expired_at=10:00 > 9:59 → 성공
  T=10:01 실행 시 → expired_at=10:00 < 10:01 → 실패 (0 rows updated)
```

booking-app이 consumeToken 실패를 어떻게 처리하는지는 별도 검증이 필요하다.

---

## Observability

현재 구현된 로그:
- SeatHolder와 달리 WaitingRoomManager에 별도 구조화 로그가 없다. 확인 필요.

이상 상태 모니터링:
```sql
-- 장시간 ACTIVE 토큰 잔류 (스케줄러 미작동 의심)
SELECT COUNT(*) FROM ticketing_waitingroom.active_tokens
WHERE status = 'ACTIVE' AND expired_at < NOW() - INTERVAL 10 MINUTE;

-- 이벤트별 대기열 잔류 사용자 수
-- (Redis 직접 조회)
-- ZCARD waiting-room:event:{eventId}
```

Prometheus 지표:
- `http_server_requests_seconds` — Micrometer 자동 등록
- `rate_limit:event:*` 키 패턴 — Redis 직접 확인 가능

planned:
- 대기열 통과 건수 Micrometer counter
- Redis ZCARD (대기열 크기) 주기적 수집
- 토큰 발급 / 소비 / 만료 건수 분리 추적

---

## Trade-offs

| 결정 | 이유 | 잃은 것 |
|------|------|---------|
| Redis Sorted Set으로 순번 관리 | O(log N) ZADD/ZRANK, 이벤트 종료 후 자동 소멸 | Redis 장애 = 대기열 전면 중단 |
| ZADD NX Lua 스크립트 | 원자적 중복 진입 방지, RTT 최소화 | Lua 스크립트 가독성 낮음 |
| 통과 후 DB 토큰 영속화 | booking-app 검증에 필요, TTL 이후에도 검증 가능 | Redis와 DB 두 저장소 관리 필요 |
| ReactiveRedisTemplate (WebFlux) | polling 집중 트래픽에서 Tomcat thread 고갈 방지 | MVC 대비 코드 복잡도 증가 (Mono 체인) |
| 토큰 TTL 10분 | 예약 완료에 충분한 시간 확보 | 10분 동안 토큰을 보유하면서 예약하지 않으면 좌석이 묶임 |
| Rate limiting 100/s 고정 | 단순 구현 | 이벤트 규모에 따라 동적 조정 불가. 설정값 변경 시 재배포 필요 |
| Redis가 source of truth | 실시간 순번 계산 속도 | Redis 재시작 시 대기열 소멸 |

---

## Planned Improvements

1. **Redis Sentinel 또는 Cluster** (planned): 단일 노드 장애 시 대기열 전면 중단 방지. 현재 Redis 단일 노드(192.168.124.101)는 SPOF다.
2. **대기열 polling SSE 전환** (proposed): 클라이언트가 반복 GET 대신 Server-Sent Events로 순번 변화를 수신. polling 부하 감소.
3. **Rate limiting 동적 설정** (planned): 이벤트 규모에 따라 초당 허용 인원을 설정값으로 관리. 현재 코드 상수(`MAX_ENTER_PER_SECOND = 100L`).
4. **스케줄러 분산 실행 제어** (planned): 다중 인스턴스 시 ShedLock 또는 Quartz 클러스터로 중복 실행 방지.
5. **fairness 고도화** (proposed): 현재 score = epochMilli는 클라이언트 시각에 의존한다. 서버 수신 시각 기준 score로 변경.
6. **Redis RDB/AOF persistence 확인 및 설정** (확인 필요): 현재 192.168.124.101의 persistence 설정이 불명확하다.
7. **대기열 운영 메트릭 정교화** (planned): ZCARD 주기 수집, 통과율, 토큰 발급/소비/만료 비율을 Grafana 패널로 시각화.

---
