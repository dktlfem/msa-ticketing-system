---
title: "Capacity Planning"
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
- [Trade-offs](#trade-offs)
- [Scale-up / Scale-out 판단 기준](#scale-up-scale-out-판단-기준)
- [Planned Improvements](#planned-improvements)
- [Interview Explanation (90s version)](#interview-explanation-90s-version)

# Capacity Planning

> 이 문서는 현재 self-hosted staging 환경의 **실제 리소스 한계와 병목 예상**을 기준으로 작성합니다.
> 인프라 토폴로지: [`docs/architecture/system-overview.md`](../architecture/system-overview.md)
> SLI/SLO 목표값: [`docs/performance/sli-slo.md`](./sli-slo.md)
> 장애 대응: [`docs/operations/incident-runbook.md`](../operations/incident-runbook.md)

---

## Background

현재 시스템은 self-hosted staging 단계입니다. 단일 MySQL 인스턴스에 5개 스키마, 단일 Redis 노드, 서비스별 Tomcat 스레드 풀로 구성되어 있습니다.

이 문서의 목적은 두 가지입니다.
1. **현재 환경에서 어디가 먼저 한계에 부딪히는가**를 명확히 인식
2. **트래픽 증가 시 무엇을 언제 바꿔야 하는가**를 기준 없이 "확장성이 있습니다"라고 말하지 않기 위한 근거 마련

---

## Problem

"이 시스템은 확장 가능합니다"라는 주장은 구체적인 수치 근거가 뒷받침되어야 합니다. 다음 질문에 구체적으로 답할 수 있어야 합니다.

- **어디가 먼저 병목이 됩니까?**
- **현재 Redis가 단일 노드인데 장애 시 어떻게 됩니까?**
- **MySQL 커넥션 풀이 부족하면 어디서 증상이 납니까?**
- **TossPayments 타임아웃이 10초인데 동시에 1000명이 결제하면 어떻게 됩니까?**

---

## Current Design

### 리소스 구성 현황

| 구성 요소 | 현재 값 | 출처 |
|----------|---------|------|
| Redis 노드 | 단일 노드 (192.168.124.101:6379) | application.properties |
| Redis Lettuce pool | max-active=20, max-idle=10 (서비스당) | application.properties |
| MySQL 인스턴스 | 단일 (컨테이너 내부 `mysql:3306`, 호스트 포트 미노출) | docker-compose.yml |
| HikariCP 커넥션 풀 | 기본값 ~10 (서비스당) | 명시적 설정 없음 |
| Tomcat 스레드 풀 | 기본값 ~200 (서비스당) | 명시적 설정 없음 |
| TossPayments 타임아웃 | connect 3s, read 10s | RestClientConfig.java |
| 예약 TTL | 5분 | ReservationManager.DEFAULT_HOLD_MINUTES |
| Idempotency TTL | 24시간 | IdempotencyManager.TTL |
| JPA ddl-auto | update | application.properties |

### 서비스별 스레드/커넥션 특성

```
scg-app (Netty 이벤트 루프)
  └── 비동기 I/O, 스레드 수 적음, 높은 연결 수 처리 가능
      → gateway 자체 병목 가능성 낮음

waitingroom-app, concert-app, booking-app, payment-app, user-app (Tomcat MVC)
  └── 동기 블로킹 스레드 모델
      → 스레드당 1 요청 처리
      → 외부 HTTP 호출(내부 API, TossPayments) 중 스레드 점유
```

---

## Measurement / Validation

### 현재 측정 가능한 항목

**MySQL 커넥션:**
```sql
-- 서비스별 커넥션 수 확인
SHOW STATUS LIKE 'Threads_connected';
SHOW PROCESSLIST;

-- 대기 중인 쿼리 확인
SELECT * FROM information_schema.INNODB_TRX WHERE trx_state = 'LOCK WAIT';
```

**Redis 연결:**
```bash
redis-cli -h 192.168.124.101 -p 6379 INFO clients
# connected_clients: 현재 연결 수
# blocked_clients: BLPOP 등 블로킹 명령 대기 수
```

**Tomcat 스레드:**
- Prometheus: `tomcat_threads_busy_threads`, `tomcat_threads_config_max_threads`
- Grafana: `tomcat.threads.busy` / `tomcat.threads.config.max` 비율

**HikariCP 커넥션:**
- Prometheus: `hikaricp_connections_pending`, `hikaricp_connections_active`
- 위험 신호: `pending > 0`이 지속되면 커넥션 부족

### k6 부하 테스트 측정 방법

참조: [`docs/performance/performance-test-runbook.md`](./performance-test-runbook.md)

부하 테스트 중 동시에 확인해야 하는 지표:
- Grafana: `tomcat.threads.busy` (각 서비스)
- Grafana: `hikaricp.connections.pending`
- Redis: `INFO stats` → `total_commands_processed`
- MySQL: `SHOW STATUS LIKE 'Threads_running'`

---

## Failure / Bottleneck Scenarios

### 병목 순서 예측 (단일 공연 오픈 시)

트래픽이 순차적으로 증가할 때 어디가 먼저 포화되는가:

```
1순위 병목: Redis (waitingroom-app)
  └── 대기열 진입 시 ZADD O(log N), 상태 조회 시 ZRANK O(log N)
  └── Redis 단일 노드 → 장애 = 전체 대기열 기능 중단
  └── Lettuce pool max-active=20 → 20개 이상 동시 Redis 요청 시 대기

2순위 병목: HikariCP (booking-app, payment-app)
  └── HikariCP 기본 10 커넥션
  └── payment /confirm: TossPayments 10s read timeout 동안 스레드 + 커넥션 점유
  └── 10명이 동시에 결제 승인 요청 → pool 고갈 가능성

3순위 병목: MySQL (optimistic lock 충돌)
  └── 같은 좌석에 N명 동시 시도 → 대부분 ROLLBACK
  └── 충돌이 많을수록 실제 DB 쓰기는 적으나 DB round-trip 증가

4순위 병목: Tomcat 스레드 (payment-app)
  └── TossPayments 10s timeout 동안 스레드 점유
  └── 200스레드 기본, 200명 동시 결제 confirm 시 모두 점유 가능
  └── 201번째 요청 → HTTP 503 (connection refused 또는 queue timeout)

5순위: scg-app (상대적으로 낮음)
  └── Netty 비동기 → 높은 연결 수 처리 가능
  └── 다운스트림 서비스가 느리면 gateway 메모리 압박
```

### 시나리오별 병목 분석

#### 시나리오 A: 1만 명이 동시에 대기열 진입

| 단계 | 병목 | 영향 | 해결 방향 |
|------|------|------|----------|
| Redis ZADD | Redis 단일 노드 처리 한계 | 응답 지연 | Redis Cluster 또는 샤딩 |
| Lettuce pool 포화 | max-active=20 초과 | 커넥션 대기 | pool 크기 증가 |
| waitingroom-app Tomcat | 동기 처리 200스레드 한계 | 503 | 인스턴스 추가 |

#### 시나리오 B: 100명이 동시에 결제 confirm

| 단계 | 병목 | 영향 |
|------|------|------|
| TossPayments 동기 호출 (10s) | Tomcat 스레드 100개 점유 | 다른 API 응답 지연 |
| HikariCP 고갈 | 동시에 10개 초과 DB 접근 | 커넥션 대기 발생 |
| Redis idempotency | max-active=20, 100명 중 최대 20만 동시 처리 | 순차 처리 → latency 증가 |

**핵심 계산:**
- TossPayments read timeout 10s
- HikariCP 기본 10 커넥션
- payment confirm 1회에 최소 2 DB 접근 (READ + UPDATE)
- **동시 처리 가능 결제 = min(10 ÷ 2, 20) = 5 ~ 10건 수준**

#### 시나리오 C: MySQL 단일 인스턴스 과부하

현재 5개 서비스가 하나의 MySQL 인스턴스 공유. 한 서비스의 slow query가 전체 서비스에 영향.

```sql
-- 영향 범위 확인
SHOW STATUS LIKE 'Innodb_row_lock_waits';
SHOW STATUS LIKE 'Innodb_row_lock_time_avg';
```

### 시나리오 D: user-app 부하 특성 분석

user-app은 다른 서비스와 다른 부하 패턴을 가진다.

```
user-app 부하 특성:
  └── 외부 서비스 호출 없음 (leaf service)
  └── Redis 미사용 → Redis 장애로부터 완전 독립
  └── 주요 쿼리: existsByEmail (UK 인덱스), findById (PK)
  └── 병목 원인: 오직 DB 커넥션(HikariCP) + Tomcat 스레드
```

**user-app 고부하 시나리오 (향후 로그인 API 구현 시)**:

| 상황 | 병목 | 추정 영향 |
|------|------|---------|
| 대규모 이벤트 오픈 전 동시 회원 가입 폭증 | Tomcat 스레드(200) + HikariCP(~10) | 동시 가입 10건 초과 시 커넥션 대기 |
| BCrypt 해싱 추가 후 (planned) | CPU 집약적 해싱(~100ms/요청) | Tomcat 스레드 점유 시간 증가 → 처리량 감소 |
| 로그인 API 추가 후 JWT 검증 집중 | 현재는 SCG가 검증 담당 → user-app 직접 부하 없음 | — |

**BCrypt 도입 시 용량 계산 예시**:
- BCrypt cost factor 10: 해싱 약 100ms/요청
- Tomcat 스레드 200개 기준 동시 처리 가능 가입 요청: 200 / 0.1s = 약 2,000 TPS 이론값
- 단, HikariCP 기본 10 커넥션 = 실질 병목은 **10 TPS** (DB 쓰기 대기)
- HikariCP `maximumPoolSize`를 50으로 늘리면 ~50 TPS까지 가능

```
BCrypt 도입 전/후 비교:
  현재 (평문 저장):  HikariCP 10 커넥션 = 약 10 TPS 한계
  BCrypt 추가 후:    CPU 100ms 해싱 추가 → 스레드 점유 증가, HikariCP 병목은 동일
  → HikariCP 확장이 BCrypt 도입 전에 선행되어야 함
```

**user-app 용량 권장 설정**:
```properties
# 현재 (기본값 사용)
# spring.datasource.hikari.maximum-pool-size=10

# user-app 권장 (회원 가입 집중 이벤트 대비)
spring.datasource.hikari.maximum-pool-size=30
spring.datasource.hikari.connection-timeout=3000
spring.datasource.hikari.idle-timeout=300000
```

---

## Trade-offs

### Redis 단일 노드 선택

| | 현재 (단일 노드) | 대안 (Redis Cluster / Sentinel) |
|--|----------------|-------------------------------|
| 운영 복잡도 | 낮음 | 높음 |
| 장애 내성 | 없음 | Sentinel: 자동 failover |
| 처리량 | 단일 노드 한계 | 샤딩으로 수평 확장 |
| 비용 | 낮음 | 노드 수 증가 |

**현재 선택 근거:** staging 환경에서 운영 복잡도 최소화. Redis 장애 시 대기열과 idempotency는 영향받지만, 결제 자체는 DB UK로 보호됨. 서비스 트래픽이 증가하면 Redis Sentinel(고가용성) 또는 Cluster(수평 확장) 전환 필요.

### MySQL 단일 인스턴스 vs 서비스별 DB 서버

| | 현재 (단일 인스턴스, 스키마 분리) | 대안 (서비스별 독립 DB 서버) |
|--|----------------------------------|------------------------------|
| 운영 복잡도 | 낮음 | 높음 (서버 N개 관리) |
| 장애 격리 | 없음 (한 서버 문제 = 전체 영향) | 서비스별 독립 장애 격리 |
| 비용 | 낮음 | 서버 수 × 비용 |
| 성능 | 공유 I/O, 공유 버퍼풀 | 서비스별 독립 튜닝 |

**현재 선택 근거:** 스키마 분리로 논리적 경계는 유지. 물리적 분리는 트래픽 증가 후 필요 시 점진적 마이그레이션 가능.

### TossPayments 동기 호출

| | 현재 (동기 RestClient) | 대안 (비동기 + 웹훅) |
|--|----------------------|-------------------|
| 구현 복잡도 | 낮음 | 높음 |
| 스레드 점유 | TossPayments 응답 대기(최대 10s) 동안 점유 | 비동기 → 스레드 즉시 반환 |
| 에러 처리 | 단순 (catch → 보상) | 복잡 (웹훅 수신 + 상태 추적) |
| 타임아웃 처리 | READY 상태 잔류 (현재 미처리) | 웹훅으로 자동 처리 가능 |

**현재 선택 근거:** 구현 단순성 우선. 동시 결제 100건 이하에서는 허용 가능한 수준. 수백 건 동시 결제 시 비동기 전환 필요.

---

## Scale-up / Scale-out 판단 기준

### Scale-up (수직 확장) 먼저 고려하는 경우

| 지표 | 임계값 | 조치 |
|------|--------|------|
| MySQL CPU | > 70% 지속 | RDS 인스턴스 타입 업그레이드 |
| Redis 메모리 사용률 | > 80% | 메모리 크기 증가 |
| JVM Heap | GC 빈도 급증 | Xmx 증가 또는 G1GC 튜닝 |
| HikariCP pending | > 0 지속 | maximumPoolSize 증가 (단, MySQL max_connections 확인) |

### Scale-out (수평 확장) 고려하는 경우

| 지표 | 임계값 | 조치 |
|------|--------|------|
| Tomcat thread busy | > 80% of max, 지속 | 서비스 인스턴스 추가 |
| Redis 처리량 한계 | LATENCY > 10ms 지속 | Redis Cluster 전환 |
| MySQL 읽기 부하 | Threads_running 지속 증가 | Read Replica 추가 (concert-app 등 read-heavy) |

### 현재 환경에서 Scale-out 시 필요한 사전 작업

1. **Redis 단일 노드 → Sentinel 또는 Cluster 전환 필요**
   - 현재: `spring.data.redis.host` 단일 호스트 설정
   - 변경: `spring.data.redis.cluster.nodes` 또는 Sentinel 설정

2. **Session/Sticky Session 없음 확인**
   - 현재 서비스는 Stateless (application.properties: SessionCreationPolicy.STATELESS)
   - 수평 확장 시 별도 세션 처리 불필요

3. **SCG 라우팅에 로드밸런싱 추가 (planned)**
   - 현재: `http://booking-app:8080` (컨테이너 단일 라우팅)
   - 확장 시: Kubernetes Service 또는 Nginx upstream 로드밸런싱

---

## Planned Improvements

| 개선 항목 | 우선순위 | 근거 |
|----------|---------|------|
| HikariCP 명시적 설정 (maximumPoolSize, connectionTimeout) | 높음 | 기본값 10으로 동시 결제 처리 한계. user-app도 동일하게 30으로 증가 권장 |
| payment-app Tomcat thread pool 증가 또는 비동기 전환 | 높음 | TossPayments 10s 동기 호출 스레드 점유 |
| Redis Sentinel 구성 | 중간 | 단일 노드 장애 시 대기열/idempotency 기능 중단 |
| MySQL HikariCP per-service 튜닝 | 중간 | 서비스별 부하 패턴이 다름 (user-app: 낮은 부하, payment-app: 높은 부하) |
| TossPayments 웹훅 수신 | 중간 | 타임아웃 시 READY 상태 결제 자동 해소 |
| user-app BCrypt 도입 후 스레드 점유 재측정 | 중간 | BCrypt cost 10 → ~100ms/요청 CPU 점유. Tomcat 유효 TPS 재산정 필요 |
| MySQL Read Replica (concert-app) | 낮음 | 공연/좌석 조회 read-heavy, write는 seat HOLD만 |
| AWS RDS + ElastiCache 전환 | 낮음 (staging 이후) | 관리형 서비스로 운영 부담 감소 |

---

## 비용 계획 (Cost Planning)

> 아래 표는 인프라 구성 요소별 월 비용 추정 구조 템플릿이다. 구체적인 금액은 실제 AWS 견적 및 전력 비용 측정 후 채워야 한다.
> staging 환경(홈서버 + 미니PC)과 production 환경(AWS)을 분리해서 추적한다.

### Staging 환경 비용 (홈서버 기반)

| 구성 요소 | 사양 | 월 비용 추정 | 비고 |
|----------|------|------------|------|
| 메인 서버 (WSL2 Docker Compose) | RAM 128GB | ₩ (전기료 기준) | 192.168.124.100, 모든 서비스 + 인프라 컨테이너 포함 |
| Redis 전용 미니PC (에코비 A1 N100) | RAM 16GB | ₩ (전기료 기준) | 192.168.124.101, Redis 단독 운영 |
| OpenVPN (NAS or 라우터) | — | ₩ 0 (자체 구성) | 외부 접근용 |
| 합계 (staging) | — | ₩ | |

### Production 환경 비용 추정 (AWS)

| 구성 요소 | 타입 / 사양 | 수량 | 월 비용 추정 (USD) | 월 비용 추정 (KRW) | 비고 |
|----------|-----------|------|--------------------|-------------------|------|
| EC2 (각 마이크로서비스 인스턴스) | t3.small (2vCPU, 2GB) | 6 | $ | ₩ | scg, booking, payment, concert, waitingroom, user |
| EC2 (Nginx Blue/Green) | t3.micro | 2 | $ | ₩ | Blue/Green 각 1대 |
| RDS MySQL | db.t3.micro (단일 AZ) | 1 | $ | ₩ | 5개 스키마 공유; 운영 전환 시 Multi-AZ 검토 |
| ElastiCache Redis | cache.t3.micro | 1 | $ | ₩ | 현재 미니PC 대체; Sentinel 구성 시 추가 비용 |
| ALB (Application Load Balancer) | — | 1 | $ | ₩ | SCG 앞단 |
| EBS (각 EC2 볼륨) | gp3 20GB | 8 | $ | ₩ | |
| S3 (로그/스냅샷 아카이브) | — | — | $ | ₩ | Grafana/Kibana 스냅샷, slow query 보관 |
| 데이터 전송 (외부 아웃바운드) | — | — | $ | ₩ | TossPayments API 호출 포함 |
| **합계 (production)** | | | **$** | **₩** | |

### 비용 최적화 포인트 (planned)

| 항목 | 현재 | 최적화 방향 | 예상 절감 |
|------|------|-----------|---------|
| EC2 On-Demand → Reserved Instance | On-Demand | 1년 약정 RI (t3.small) | 약 30~40% 절감 |
| RDS Single-AZ → Multi-AZ (운영 이후) | Single-AZ | 장애 허용성 향상, 비용 약 2배 | — (비용 증가, 안정성 투자) |
| Redis ElastiCache → Sentinel 2-node | 단일 노드 | 가용성 향상, 비용 약 2배 | — (비용 증가, 안정성 투자) |
| 서비스별 EC2 → Fargate (장기) | EC2 고정 비용 | 요청 기반 과금, 피크 외 절감 | 트래픽 패턴에 따라 상이 |

### 비용 추적 기준

- **측정 주기**: 월 1회 AWS Cost Explorer 확인
- **알람 기준**: 월 예산 대비 80% 도달 시 Billing Alert 설정
- **스케일 트리거**: EC2 CPU 70% 지속 1시간 이상 또는 RDS IOPS 제한 도달 시 스펙 업그레이드 검토

---

## Interview Explanation (90s version)

> 현재 시스템에서 가장 먼저 병목이 될 구간은 두 곳입니다. 첫째는 Redis 단일 노드입니다. 대기열 진입이 집중되면 Redis 처리량과 Lettuce 커넥션 풀(max 20)이 한계에 부딪힙니다. Redis 장애 시 대기열 전체가 중단됩니다. 둘째는 payment-app의 TossPayments 동기 호출입니다. read timeout이 10초이고 HikariCP 기본 풀이 10개라, 동시 결제 5~10건 이상에서 커넥션 대기가 발생합니다. 수평 확장은 서비스가 Stateless이므로 인스턴스 추가만으로 가능하지만, 그 전에 Redis를 Sentinel로 전환하고 HikariCP 풀 크기를 명시적으로 설정하는 것이 우선입니다. MySQL은 스키마 분리로 논리적 경계는 있지만 물리적 분리는 아직이므로, 하나의 서비스 slow query가 전체에 영향을 줄 수 있습니다.

---

*최종 업데이트: 2026-03-19 | 비용 계획 섹션 추가*
