---
title: "ADR 0003 — LocalRateLimiter에서 RedisRateLimiter(토큰 버킷)로 전환"
last_updated: "2026-03-19"
author: "민석"
reviewer: ""
---

## 목차

- [상태](#상태)
- [컨텍스트](#컨텍스트)
- [결정](#결정)
- [결과](#결과)
- [고려했으나 채택하지 않은 대안](#고려했으나-채택하지-않은-대안)
- [참고 자료](#참고-자료)

---

# ADR 0003 — LocalRateLimiter에서 RedisRateLimiter(토큰 버킷)로 전환

## 상태

> **Accepted**

**날짜**: 2026-03-19

---

## 컨텍스트

### 기존 구현: LocalRateLimiter (인메모리, 1초 고정 윈도)

SCG Phase 2에서 `LocalRateLimiter`를 직접 구현했다.
`ConcurrentHashMap` 기반 1초 고정 윈도 카운터로, Redis 의존 없이 즉시 동작하는 것이 목표였다.

### k6 부하테스트(시나리오 1)에서 발견한 두 가지 한계

**1. 고정 윈도 경계 효과 (Fixed Window Boundary Effect)**

k6 시나리오 1 실행 결과 (2026-03-19):
- Burst phase: 95.5% 차단 (PASS)
- Recovery phase: 429 지속 (FAIL — threshold `rate<0.05` 초과)

원인: 1초 고정 윈도 경계에서 burst → recovery 전환 시점에 이전 윈도의 카운터가 남아있어 소수의 429가 발생한다.
고정 윈도 알고리즘의 알려진 한계로, 윈도 경계에서 순간적으로 burst-capacity × 2 요청이 통과할 수도 있다.

```
고정 윈도:  |----10개----|----10개----|  ← 경계에서 순간 20개 통과 가능
토큰 버킷:  ●●●●● → 초당 5개씩 연속 보충  ← 경계 개념 없음
```

**2. SCG 수평 확장 시 노드별 독립 카운터**

`LocalRateLimiter`는 인메모리이므로 SCG를 2대로 확장하면 각 노드가 독립 카운터를 가진다.
실제 허용 TPS = burst-capacity × 노드 수가 되어 rate limiting 의미가 사라진다.

---

## 결정

### Spring Cloud Gateway `RedisRateLimiter`로 전환

Spring Cloud Gateway가 기본 제공하는 `RedisRateLimiter`를 사용한다.
내부적으로 Redis Lua 스크립트 기반 **토큰 버킷 알고리즘**을 구현하며, 두 가지 한계를 동시에 해결한다.

채택 이유:

| 판단 기준 | LocalRateLimiter | RedisRateLimiter |
|----------|-----------------|-----------------|
| 윈도 경계 효과 | 있음 (1초 고정 윈도) | 없음 (토큰 연속 보충) |
| 다중 인스턴스 | 노드별 독립 카운터 | Redis 단일 카운터 공유 |
| 추가 인프라 | 없음 | Redis (이미 스택에 존재) |
| 레이턴시 오버헤드 | ~0ms | ~1-2ms (Redis RTT) |
| 구현 복잡도 | 직접 구현 | SCG 기본 제공 (의존성 추가만) |

### 적용 방법

1. `spring-boot-starter-data-redis-reactive` 의존성 추가
2. `spring.data.redis` 연결 설정 추가 (기존 Redis `192.168.124.101:6379` 공유)
3. `LocalRateLimiter` 클래스에서 `@Primary` 제거, `@Component` 제거 (비활성화, 코드 보존)
4. 각 route의 `RequestRateLimiter` 필터 args에 `redis-rate-limiter.*` 설정 인라인
5. `RateLimiterConfig`에서 `@EnableScheduling` 제거 (LocalRateLimiter 스케줄러 불필요)

### Redis 장애 시 동작

`RedisRateLimiter`는 Redis 연결 실패 시 기본적으로 **요청을 거부**(deny)한다.
이 동작은 `spring.cloud.gateway.redis-rate-limiter.deny-empty-key=true` (기본값)로 제어된다.

> 결제 시스템에서 rate limiting이 "모르면 허용"보다 "모르면 차단"이 더 안전하다는 판단이다.
> Redis 장애 시 전체 통과를 허용하면 downstream 서비스가 보호되지 않는다.

---

## 결과

### 긍정적 효과

- **윈도 경계 효과 제거**: 토큰 버킷 알고리즘으로 burst → recovery 전환 시 429 지속 문제 해소
- **수평 확장 대응**: SCG 다중 인스턴스에서도 글로벌 rate limit 유지
- **커스텀 코드 제거**: 직접 구현한 `LocalRateLimiter` 대신 SCG 기본 제공 컴포넌트 사용으로 유지보수 부담 감소
- **기존 인프라 활용**: waitingroom, payment idempotency에 이미 사용 중인 Redis를 공유

### 부정적 효과 / 트레이드오프

- **Redis 의존성 추가**: SCG가 Redis에 의존하게 됨. Redis 장애 시 rate limiting뿐 아니라 전체 요청 거부 가능
- **레이턴시 증가**: 요청마다 Redis RTT(~1-2ms) 추가. 시나리오 4(필터 오버헤드 측정)에서 검증 예정
- **LocalRateLimiter 코드 비활성화**: 삭제하지 않고 보존하여 Redis 없는 로컬 개발 환경에서 참고/전환 가능

### 변경된 파일 / 영향 범위

| 파일 | 변경 내용 |
|------|----------|
| `scg-app/build.gradle` | `spring-boot-starter-data-redis-reactive` 의존성 추가 |
| `scg-app/src/main/resources/application.yml` | Redis 연결 설정 추가, route별 `redis-rate-limiter.*` 인라인, `gateway.rate-limiter` 커스텀 설정 제거 |
| `scg-app/src/.../ratelimit/LocalRateLimiter.java` | `@Primary`, `@Component` 제거 (비활성화, 코드 보존) |
| `scg-app/src/.../config/RateLimiterConfig.java` | `@EnableScheduling` 제거 |

---

## 고려했으나 채택하지 않은 대안

| 대안 | 설명 | 미채택 이유 |
|------|------|-----------|
| 슬라이딩 윈도 LocalRateLimiter | 고정 윈도를 슬라이딩 윈도로 변경 | 윈도 경계 문제는 해결하지만 다중 인스턴스 문제가 남음. 두 문제를 따로 해결하는 것보다 RedisRateLimiter로 한 번에 해결하는 것이 효율적. |
| Bucket4j + Redis | 별도 라이브러리로 토큰 버킷 구현 | SCG가 이미 `RedisRateLimiter`를 기본 제공하므로 추가 라이브러리 불필요. |
| Redis 장애 시 전체 허용 (fail-open) | `deny-empty-key=false` 설정 | 결제 경로에서 rate limiting이 무력화되면 downstream 보호가 사라짐. fail-closed가 더 안전. |

---

## 참고 자료

- k6 시나리오 1 결과: `load-test/scripts/k6/results/scenario1-rate-limiter_20260319-203722.json`
- [Spring Cloud Gateway — RequestRateLimiter](https://docs.spring.io/spring-cloud-gateway/reference/spring-cloud-gateway/gatewayfilter-factories/requestratelimiter-factory.html)
- [Redis 기반 토큰 버킷 Lua 스크립트](https://github.com/spring-cloud/spring-cloud-gateway/blob/main/spring-cloud-gateway-server/src/main/resources/META-INF/scripts/request_rate_limiter.lua)
- [`docs/data/database-cache-design.md`](../../data/database-cache-design.md) — Redis 키 패턴 설계
