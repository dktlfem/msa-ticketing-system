---
title: "ADR 0005 — Circuit Breaker + Bulkhead를 서비스(route)별로 분리하는 이유"
last_updated: "2026-03-20"
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

# ADR 0005 — Circuit Breaker + Bulkhead를 서비스(route)별로 분리하는 이유

## 상태

> **Accepted**

**날짜**: 2026-03-20

---

## 컨텍스트

scg-app은 5개 downstream 서비스(user, waitingroom, concert, booking, payment)로 트래픽을 라우팅한다.
각 서비스는 성격이 다르다.

| 서비스 | 특성 | 장애 시 영향 |
|--------|------|------------|
| payment-app | 돈이 관련된 상태 전이. 동시 처리 제한 필수 | 결제 흐름 전체 영향 |
| waitingroom-app | 피크 트래픽 집중. Redis 의존 | 대기열 진입 불가 |
| concert-app | read-heavy, 캐시 대상 | 공연/좌석 조회 불가 |
| booking-app | 예약 생성, 상태 전이 | 예약 흐름 영향 |
| user-app | 회원가입, 조회. leaf service | 신규 가입 불가 |

Resilience4j를 어떻게 구성할지 결정이 필요했다.
**옵션 A**: 모든 서비스에 동일한 Circuit Breaker + Bulkhead 설정을 공유한다.
**옵션 B**: 서비스(route)별로 독립적인 CB + Bulkhead 인스턴스를 구성한다.

---

## 결정

**서비스별로 독립된 CircuitBreaker와 Bulkhead 인스턴스를 구성한다.**

```yaml
# application.yml 실제 설정 (요약)
resilience4j:
  circuitbreaker:
    instances:
      payment-service-cb:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 15s
      user-service-cb:
        slidingWindowSize: 20
        failureRateThreshold: 60
        waitDurationInOpenState: 10s
      # 각 서비스별 별도 인스턴스

  bulkhead:
    instances:
      payment-service:
        maxConcurrentCalls: 10   # payment는 보수적
      default:
        maxConcurrentCalls: 20   # 그 외 서비스
```

각 route의 filter 설정에서 name으로 해당 인스턴스를 참조한다.

```yaml
routes:
  - id: payment-service
    filters:
      - name: CircuitBreaker
        args:
          name: payment-service-cb       # payment 전용 CB
          fallbackUri: forward:/fallback/service-unavailable
```

---

## 결과

### 핵심 효과: 장애 격리 (Bulkhead 패턴의 본질)

**시나리오: concert-app이 응답 지연 상태 (p99 = 5s)**

공유 CB 방식:
```
concert-app 지연 → 공유 CB의 failureRate 상승 → CB OPEN
→ payment-app, booking-app 요청도 fallback 반환
→ 결제 불가 (concert-app 때문에)
```

서비스별 CB 방식:
```
concert-app 지연 → concert-service-cb만 OPEN
→ payment-app, booking-app은 정상 처리 계속
→ 결제/예약은 영향 없음
```

**payment-app Bulkhead = 10의 이유:**

payment-app의 `/confirm`은 TossPayments API 호출(최대 10s) + DB 쓰기가 하나의 요청 처리 흐름에 포함된다.
동시 처리가 10개를 넘으면 DB connection pool(HikariCP) 고갈 위험이 있다.
10개 제한은 "결제 처리 중 커넥션을 장시간 점유하는 요청"이 pool을 소모하지 않도록 보수적으로 설정한 값이다.

다른 서비스(20개)와 payment-app(10개)의 차이는 의도적이다.
concert-app의 좌석 조회(~5ms)가 payment-app의 결제 confirm(~10s worst case)과 같은 동시성 제한을 갖는 것은 불합리하다.

### 서비스별 CB 설정 근거

| 서비스 | slidingWindowSize | failureRateThreshold | waitDurationInOpenState | 설정 이유 |
|--------|-----------------|---------------------|------------------------|---------|
| payment-service-cb | 10 | 50% | 15s | 작은 윈도우로 빠른 감지. 금융 흐름이므로 복구 대기 길게 |
| user-service-cb | 20 | 60% | 10s | 비교적 관대한 임계값. leaf service로 다른 서비스 영향 없음 |
| waitingroom-service-cb | 10 | 50% | 10s | Redis 의존. 장애 감지 빠르게 |
| concert-service-cb | 20 | 50% | 10s | read-heavy, 빠른 복구 기대 |
| booking-service-cb | 10 | 50% | 10s | 상태 전이 포함, 빠른 감지 |

### 부정적 효과 / 트레이드오프

**설정 복잡도 증가**: 서비스 수만큼 CB 인스턴스 설정이 늘어난다.
새 서비스 추가 시 CB + Bulkhead 설정을 명시적으로 작성해야 한다.

**메트릭 분리**: `resilience4j.circuitbreaker.state{name="payment-service-cb"}`처럼 서비스별로 메트릭이 분리된다.
Grafana에서 서비스별 CB 상태를 개별 패널로 봐야 한다.
→ 단점이기도 하지만, 어느 서비스 CB가 open됐는지 바로 알 수 있다는 장점이기도 하다.

---

## 고려했으나 채택하지 않은 대안

| 대안 | 설명 | 미채택 이유 |
|------|------|-----------|
| 공유 CB 인스턴스 (전체 단일) | 모든 route가 하나의 CB를 공유 | concert-app 장애가 payment-app CB를 열 수 있음. 장애 격리가 불가능 |
| CB 없이 timeout만 적용 | 서비스별 read-timeout으로만 보호 | timeout이 만료되기 전까지 요청을 계속 받음. 반열린 상태의 느린 서비스로 연결이 쌓임 |
| Bulkhead 없이 CB만 | CB는 있지만 동시 처리 제한 없음 | payment-app처럼 오래 걸리는 요청이 쌓이면 CB 발동 전에 DB connection pool이 먼저 고갈됨 |
| 서비스 메시(Istio) CB | Envoy 사이드카에서 CB 처리 | Docker Compose 기반 staging에 부적합. SCG 레벨에서 처리하면 코드 기반으로 확인 가능 |

---

## 참고 자료

- `scg-app/src/main/resources/application.yml` — CB/Bulkhead 실제 설정
- `scg-app/src/main/java/.../filter/BulkheadFilter.java`
- `scg-app/src/main/java/.../config/Resilience4jConfig.java`
- [`docs/performance/sli-slo.md`](../../performance/sli-slo.md)
- [`docs/observability/observability.md`](../../observability/observability.md) — CB 메트릭 모니터링
