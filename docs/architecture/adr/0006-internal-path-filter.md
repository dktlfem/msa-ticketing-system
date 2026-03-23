---
title: "ADR 0006 — /internal/** 차단을 라우팅 미등록이 아닌 전용 필터로 구현하는 이유"
last_updated: "2026-03-20"
author: "민석"
reviewer: ""
---

## 목차

- [상태](#상태)
- [컨텍스트](#컨텍스트)
- [결정](#결정)
- [결과](#결과)
- [고려했으나 채택하지 않은 대안](#고려했으나-채택하지 않은-대안)
- [참고 자료](#참고-자료)

---

# ADR 0006 — /internal/** 차단을 라우팅 미등록이 아닌 전용 필터로 구현하는 이유

## 상태

> **Accepted**

**날짜**: 2026-03-20

---

## 컨텍스트

시스템에는 두 종류의 API가 있다.

| 종류 | 경로 패턴 | 대상 | 예시 |
|------|----------|------|------|
| 외부 API | `/api/v1/**` | 클라이언트 | `GET /api/v1/events/1` |
| 내부 API | `/internal/**` | 서비스 간 호출 전용 | `GET /internal/v1/reservations/1` |

내부 API는 서비스 간 직접 HTTP 호출에만 사용된다.
외부 클라이언트가 `/internal/v1/reservations/{id}`에 직접 접근하면 인증 없이 예약 정보가 노출된다.

이를 막는 방법으로 두 가지 접근이 있었다.

**옵션 A (소극적 방어)**: `/internal/**` 경로를 SCG 라우팅 테이블에 등록하지 않는다.
  → 등록된 route가 없으면 SCG는 404를 반환한다.

**옵션 B (적극적 방어)**: 전용 GlobalFilter(`InternalPathBlockFilter`)를 두어 `/internal/**` 요청을 명시적으로 403으로 차단한다.

---

## 결정

**두 가지를 동시에 적용한다.**

1. `/internal/**` 경로는 SCG `routes`에 등록하지 않는다. (옵션 A 유지)
2. `InternalPathBlockFilter`를 GlobalFilter로 등록하여 `/internal/**` 요청을 명시적으로 403 반환한다. (옵션 B 추가)

```java
// InternalPathBlockFilter
@Override
public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
    String path = exchange.getRequest().getURI().getPath();
    boolean blocked = blockedPatterns.stream()
            .anyMatch(pattern -> pathMatcher.match(pattern, path));
    if (blocked) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }
    return chain.filter(exchange);
}

@Override
public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 2; // 필터 체인 매우 초기에 실행
}
```

**차단 응답: 404가 아닌 403**

404(Not Found)를 반환하면 "이 경로는 존재하지 않는다"는 정보를 노출한다.
403(Forbidden)은 "경로는 알고 있지만 접근이 거부됐다"를 의미한다.

내부 API 경로가 있다는 사실이 공격자에게 노출되는 것은 어차피 코드가 오픈소스라면 막기 어렵다.
오히려 403으로 "명시적으로 차단하고 있다"는 의도를 드러내는 것이 보안 로그 분석에 유용하다.
`[BLOCKED] path=/internal/v1/reservations/1` 로그로 시도 패턴을 관측할 수 있다.

---

## 결정: 옵션 A만으로 충분하지 않은 이유 (Defence in Depth)

라우팅 미등록만으로 충분하다고 판단할 수 있으나, 아래 세 가지 시나리오에서 취약하다.

**시나리오 1: 설정 실수**

신규 기능 추가 시 개발자가 실수로 `/internal/**`을 포함하는 wildcard route를 추가할 수 있다.

```yaml
# 실수 예시
routes:
  - id: booking-all
    uri: http://booking-app:8083
    predicates:
      - Path=/api/v1/reservations/**,/internal/**  # /internal 포함 실수
```

옵션 A만 있으면 이 실수가 즉시 내부 API 노출로 이어진다.
옵션 B(필터)가 있으면 route가 추가돼도 필터가 차단한다.

**시나리오 2: SCG 버전 업그레이드 or 라우팅 재설계**

SCG 라우팅 설정이 외부 소스(DB, Config Server)에서 동적으로 로드되는 구조로 변경될 수 있다.
이 경우 "코드에 route를 추가하지 않는다"는 컨벤션이 깨질 수 있다.

**시나리오 3: 감사(Audit) 불가**

라우팅 미등록은 404 반환만 일어난다. 누가, 언제, 어떤 경로에 접근을 시도했는지 로그에 남지 않는다.
`InternalPathBlockFilter`는 `[BLOCKED] clientIp={} path={}` 로그를 남겨 보안 감사 기록을 제공한다.

---

## 결과

### 긍정적 효과

**1. 이중 방어**: route 미등록 + 필터 차단으로 어느 한 쪽이 실패해도 다른 쪽이 보호한다.

**2. 빠른 차단**: `HIGHEST_PRECEDENCE + 2`로 필터 체인 초기에 실행된다.
JWT 검증(+4), Redis rate-limiting(route filter), Bulkhead(+7) 자원을 소모하지 않고 차단된다.

**3. 감사 로그**: `[BLOCKED] requestId={} clientIp={} path={}` 로그가 남아 Kibana에서 내부 경로 접근 시도를 탐지할 수 있다.

**4. 설정 가능**: `gateway.security.internal-block-patterns=/internal/**` 설정으로 패턴을 변경할 수 있다.
향후 `/admin/**` 등 다른 경로를 추가 차단할 때 필터 코드 수정 없이 yml 변경만으로 가능하다.

**5. k6 시나리오 7로 검증 가능**: 부하 중에도 bypass = 0을 확인하는 테스트가 있어
"설계 의도"가 아닌 "실제 동작"이 보장된다.

### 부정적 효과 / 트레이드오프

**필터 레이어 오버헤드**: 모든 요청에 대해 AntPathMatcher 매칭이 실행된다.
단, `/internal/**` 패턴 매칭은 문자열 prefix 비교 수준이므로 오버헤드는 무시 가능하다(< 0.1ms).

**의도의 명시성**: 코드를 읽는 사람이 "왜 route가 없을 뿐만 아니라 필터도 있는가"를 이해해야 한다.
이 ADR이 그 이유를 설명한다.

---

## 고려했으나 채택하지 않은 대안

| 대안 | 설명 | 미채택 이유 |
|------|------|-----------|
| 라우팅 미등록만 (옵션 A 단독) | `/internal/**` route를 SCG에 추가하지 않음 | 설정 실수, 동적 라우팅 변경 시 취약. 감사 로그 없음 |
| Nginx upstream level 차단 | Nginx가 `/internal/**` 요청을 SCG 전에 차단 | Nginx 설정과 SCG 설정이 분리되어 관리 포인트 증가. 코드 기반 검증 불가 |
| 네트워크 레이어 격리 | /internal 서비스를 다른 네트워크 세그먼트에 배치 | Docker Compose 기반 staging에서 네트워크 세그먼트 분리는 설정 복잡도 증가. 애플리케이션 레이어 방어를 대체할 수 없음 |
| Spring Security Gateway | WebFlux Security Filter로 /internal 차단 | Resilience4j + 커스텀 필터가 이미 GlobalFilter로 구성된 상황에서 Security Filter Chain을 추가하면 순서 관리가 복잡해짐 |

---

## 참고 자료

- `scg-app/src/main/java/.../filter/InternalPathBlockFilter.java`
- `scg-app/src/test/java/.../filter/InternalPathBlockFilterTest.java`
- `load-test/scripts/k6/scenario7-internal-block.js` — 부하 중 차단 검증
- [ADR 0004](./0004-jwt-validation-in-scg.md) — JWT 검증 위치 설계
- [`docs/api/api-spec.md`](../../api/api-spec.md) — 내부 API 목록
