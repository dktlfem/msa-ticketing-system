---
title: "백업 및 재해 복구 (Backup & Disaster Recovery)"
last_updated: 2026-03-19
author: "민석"
reviewer: ""
---

## 목차
- [Background](#background)
- [Problem](#problem)
- [Current Design](#current-design)
- [Operational Procedure](#operational-procedure)
- [Failure Scenarios](#failure-scenarios)
- [Observability / Detection](#observability-detection)
- [Recovery / Mitigation](#recovery-mitigation)
- [Trade-offs](#trade-offs)


## 실행 전제 조건
- [ ] (작성 필요)

## 예상 소요 시간
- (작성 필요)

## 롤백 절차
- [ ] (작성 필요)

# 백업 및 재해 복구 (Backup & Disaster Recovery)

## Background

MSA 기반 티켓팅 시스템은 6개 서비스에 걸쳐 5개의 MySQL 스키마와 Redis를 사용한다. 결제 데이터(ticketing_payment)는 고객 돈과 직결되며, 예약 데이터(ticketing_booking)는 좌석 중복 방지의 source of truth다. 데이터 손실이 발생하면 단순한 서비스 장애가 아니라 고객 자산 피해로 이어질 수 있다.

이 문서는 현재 구현된 것과 아직 구현되지 않은 것을 정직하게 구분하고, 실제로 실행 가능한 복구 절차를 기록한다.

## Problem

현재 이 시스템이 가진 백업/복구 관련 문제:

1. **스키마 이력 추적 불가**: `ddl-auto=update`로 JPA가 스키마를 자동 변경하지만 Flyway/Liquibase가 없어 어떤 스키마 변경이 언제 적용됐는지 추적할 방법이 없다
2. **정기 자동 백업 없음**: mysqldump를 수동으로 실행해야 한다. 마지막 백업이 언제인지 보장할 수 없다
3. **Redis 영속성 설정 불확실**: 별도 노드(192.168.124.101)에서 실행 중이지만 RDB/AOF persistence가 설정됐는지 확인되지 않았다
4. **RPO/RTO 목표 미정의**: staging 환경이라는 이유로 복구 목표 시간이 명시적으로 설정되지 않았다
5. **CANCEL_FAILED 상태 모니터링 부재**: 결제 취소 실패 상태가 발생해도 자동으로 감지하거나 처리하는 메커니즘이 없다

## Current Design

> 전체 인프라 토폴로지와 CI/CD 파이프라인은 [`docs/architecture/system-overview.md`](../architecture/system-overview.md) 참조.
> payment 상태 머신(READY → APPROVED/FAILED → REFUNDED/CANCEL_FAILED) 상세는 [`docs/services/payment/payment-architecture.md`](../services/payment/payment-architecture.md) 참조.

### 현재 구현 상태 (솔직한 평가)

| 항목 | 현재 상태 | 비고 |
|------|-----------|------|
| MySQL 정기 백업 | **미구현** | 수동 mysqldump만 가능 |
| MySQL 백업 자동화 | **미구현** | cron 스크립트 계획 중 |
| AWS RDS automated backup | **미확인** | 설정 여부 확인 필요 (planned) |
| Redis RDB persistence | **미확인** | 192.168.124.101 설정 확인 필요 |
| Redis AOF persistence | **미확인** | 설정 여부 확인 필요 |
| 스키마 버전 관리 | **미구현** | Flyway/Liquibase 없음 |
| CANCEL_FAILED 자동 감지 | **미구현** | 수동 쿼리로만 확인 가능 |
| DR 훈련/복구 리허설 | **미실시** | 절차만 문서화된 상태 |
| RPO/RTO 목표 정의 | **미정의** | 이 문서에서 권장값 제시 |

**이 문서에서 다루는 범위**: 현재 실행 가능한 수동 절차, 각 장애 시나리오별 복구 SQL, 그리고 개선 계획의 방향.

### 환경 구성 요약

```
스테이징 서버:    192.168.124.100 (WSL2 + Docker Compose)
Redis 노드:       192.168.124.101:6379
MySQL (스테이징): mysql:3306 (Docker 내부 네트워크 전용, 호스트 포트 미노출)
MySQL (운영):     AWS RDS MySQL
```

> **주의**: 스테이징 MySQL은 docker-compose.yml 기준으로 호스트 포트를 노출하지 않는다.
> 따라서 mysqldump는 반드시 `docker exec` 명령으로 컨테이너 내부에서 실행해야 한다.

**MySQL 스키마**

| 스키마 | 서비스 | 핵심 테이블 | 백업 우선순위 |
|--------|--------|-----------|------------|
| ticketing_user | user-app | users | Medium (비밀번호 평문 저장 주의 — security-design.md R6 참조) |
| ticketing_concert | concert-app | events, event_schedules, seats | Medium (이벤트/좌석 마스터 데이터) |
| ticketing_booking | booking-app | reservations | High (예약 정합성 source of truth) |
| ticketing_payment | payment-app | payments | Critical (고객 자금 연관) |
| ticketing_waitingroom | waitingroom-app | active_tokens | Low (TTL 기반 휘발성 데이터, 재발급 가능) |

> **scg-app**: stateless 서비스로 MySQL 스키마가 없다. 라우팅 설정(`application.properties`)은 소스 코드 저장소(GitLab)에서 관리되므로 별도 DB 백업 불필요.

---

## Operational Procedure

### MySQL 백업

**현재 가능한 것: 수동 mysqldump**

결제 데이터 단독 백업 (가장 중요):
```bash
# MySQL은 Docker 내부 네트워크에서만 접근 가능 → docker exec으로 실행
docker exec mysql mysqldump \
  -u root -p \
  ticketing_payment payments \
  > payments_backup_$(date +%Y%m%d_%H%M%S).sql
```

전체 스키마 백업:
```bash
docker exec mysql mysqldump \
  -u root -p \
  --databases ticketing_payment ticketing_booking ticketing_concert ticketing_user ticketing_waitingroom \
  > full_backup_$(date +%Y%m%d_%H%M%S).sql
```

전체 DB 백업 (모든 스키마 포함):
```bash
docker exec mysql mysqldump \
  -u root -p \
  --all-databases \
  > all_databases_backup_$(date +%Y%m%d_%H%M%S).sql
```

백업 복구:
```bash
# 백업 파일을 컨테이너로 복사 후 복구
docker cp payments_backup_20240101_120000.sql mysql:/tmp/
docker exec -i mysql mysql \
  -u root -p \
  ticketing_payment \
  < /tmp/payments_backup_20240101_120000.sql
```

**계획 중 (Planned): cron 기반 자동 백업**

```bash
# /etc/cron.d/ticketing-backup (예시 — 현재 미구현)
# MySQL은 호스트 포트 미노출이므로 docker exec으로 실행
0 2 * * * root docker exec mysql mysqldump -u backup_user -p<password> \
  --databases ticketing_payment ticketing_booking \
  > /backup/ticketing_$(date +\%Y\%m\%d).sql 2>/var/log/backup.log
```

**계획 중 (Planned): AWS RDS automated backup**

- 설정 목표: 매일 자동 백업, 보존 기간 7일
- 포인트 인 타임 복구(PITR): 최근 35일 내 임의 시점 복구
- 현재 상태: RDS 인스턴스의 automated backup 활성화 여부 확인 필요

---

### Redis 백업 / 복구

**현재 상태 확인 (실행 필요)**

```bash
# Redis 설정 확인 — persistence 모드 확인
redis-cli -h 192.168.124.101 -p 6379 -a <REDIS_PASSWORD> CONFIG GET save
redis-cli -h 192.168.124.101 -p 6379 -a <REDIS_PASSWORD> CONFIG GET appendonly

# RDB 파일 위치 확인
redis-cli -h 192.168.124.101 -p 6379 -a <REDIS_PASSWORD> CONFIG GET dir
redis-cli -h 192.168.124.101 -p 6379 -a <REDIS_PASSWORD> CONFIG GET dbfilename
```

**Redis 장애 시 서비스별 영향**

| 서비스 | Redis 의존 내용 | 장애 시 영향 | 복구 방법 |
|--------|----------------|-------------|----------|
| waitingroom-app | 대기열 토큰, 순서 | 대기열 전체 초기화 | 사용자 재진입으로 자연 복구 |
| payment-app | idempotency 키 | DB UK constraint로 fallback | TTL 내 재처리 허용 |
| concert-app | 좌석 선점 캐시 | DB에서 재조회 | 자동 복구 (DB가 source of truth) |
| booking-app | 세션/캐시 | 성능 저하 | DB fallback으로 서비스 유지 |

**Redis 재시작 후 복구 절차**

```bash
# 1. Redis 재시작
docker restart redis-node

# 2. 연결 확인
redis-cli -h 192.168.124.101 -p 6379 -a <REDIS_PASSWORD> PING
# 기대 응답: PONG

# 3. 서비스 재시작 (Redis 연결 재수립)
docker restart waitingroom-app payment-app

# 4. 대기열 서비스 재개 확인 (사용자 재진입 유도)
```

대기열은 Redis 장애 후 자연 복구된다. Redis가 재시작되면 기존 대기열 토큰은 모두 사라지지만, 사용자가 재진입하면 새 토큰이 발급되고 대기열이 재구성된다. idempotency 키는 TTL 내에 동일한 요청이 들어오면 DB unique key constraint로 중복 방지가 유지된다.

---

### Payment 데이터 손실 시나리오별 복구 SQL

#### 1. CANCEL_FAILED 수동 처리

CANCEL_FAILED 상태는 Toss Payments에서 취소가 완료됐지만 DB 업데이트가 실패한 상태다. 고객 입장에서는 돈이 돌아왔지만 DB에는 아직 취소 전 상태로 기록된 것이다. 반대로 DB 업데이트가 됐지만 실제 취소가 안 된 경우도 있을 수 있으므로, **반드시 Toss Payments 콘솔에서 실제 상태를 먼저 확인**한 후 DB를 업데이트해야 한다.

```sql
-- CANCEL_FAILED 잔류 현황 조회
SELECT id, order_id, payment_key, status, amount, created_at, updated_at
FROM ticketing_payment.payments
WHERE status = 'CANCEL_FAILED'
ORDER BY created_at DESC;

-- Toss Payments 콘솔에서 실제 취소 완료 확인 후 DB 업데이트
-- (payment_key를 Toss Payments API 또는 콘솔에서 조회하여 실제 상태 확인)
UPDATE ticketing_payment.payments
SET status = 'REFUNDED',
    updated_at = NOW()
WHERE id = '<payment_id>'
  AND status = 'CANCEL_FAILED';

-- 업데이트 확인
SELECT id, order_id, status, updated_at
FROM ticketing_payment.payments
WHERE id = '<payment_id>';
```

#### 2. READY 상태 장시간 잔류 cleanup

READY 상태는 결제 요청을 생성했지만 Toss Payments 웹훅 또는 폴링 응답을 아직 받지 못한 상태다. 10분 이상 READY면 웹훅 누락 또는 처리 오류로 간주한다.

```sql
-- READY 상태 장시간 잔류 조회 (10분 이상)
SELECT id, order_id, payment_key, amount, created_at,
       TIMESTAMPDIFF(MINUTE, created_at, NOW()) AS minutes_elapsed
FROM ticketing_payment.payments
WHERE status = 'READY'
  AND created_at < NOW() - INTERVAL 10 MINUTE
ORDER BY created_at ASC;

-- Toss Payments API로 실제 결제 상태 조회 후 처리
-- 실제 결제 완료된 경우: APPROVED로 업데이트
UPDATE ticketing_payment.payments
SET status = 'APPROVED',
    updated_at = NOW()
WHERE id = '<payment_id>'
  AND status = 'READY';

-- 실제 결제 미완료(만료)된 경우: FAILED로 업데이트
UPDATE ticketing_payment.payments
SET status = 'FAILED',
    updated_at = NOW()
WHERE id = '<payment_id>'
  AND status = 'READY';
```

#### 3. Payment/Reservation 상태 불일치 조회

결제는 APPROVED됐지만 예약이 아직 PENDING인 경우 — 결제 성공 후 booking-app 업데이트가 실패한 상황이다.

```sql
-- payment APPROVED, booking PENDING 불일치 조회
-- booking_id를 통해 두 서비스 데이터를 연결 (서비스 DB 간 조인이므로 주의)
SELECT
    p.id AS payment_id,
    p.order_id,
    p.payment_key,
    p.status AS payment_status,
    p.amount,
    p.created_at AS payment_created_at,
    b.id AS booking_id,
    b.status AS booking_status,
    b.created_at AS booking_created_at
FROM ticketing_payment.payments p
JOIN ticketing_booking.bookings b ON b.id = p.booking_id
WHERE p.status = 'APPROVED'
  AND b.status = 'PENDING'
ORDER BY p.created_at DESC;

-- booking_id 컬럼이 payments 테이블에 없는 경우: order_id를 통해 연결
-- (실제 스키마 구조 확인 후 쿼리 조정 필요)
SELECT
    p.id AS payment_id,
    p.order_id,
    p.status AS payment_status,
    p.amount
FROM ticketing_payment.payments p
WHERE p.status = 'APPROVED'
  AND p.created_at > NOW() - INTERVAL 1 HOUR;
-- 위 결과의 order_id로 ticketing_booking.bookings에서 상태 수동 확인
```

이 상태가 발견되면:
1. booking-app에 예약 확정 API를 수동 호출하거나
2. [보상 트랜잭션](../services/payment/payment-architecture.md)으로 결제 환불 처리
3. 둘 다 불가하면 고객 수동 처리 후 데이터 정합성 기록

#### 4. 결제 감사 쿼리 (Daily 집계)

운영 중 매일 실행하는 감사 쿼리다. 이상 상태(CANCEL_FAILED, 장시간 READY)를 조기에 탐지하기 위한 용도다.

```sql
-- 일별 결제 상태 집계
SELECT
    DATE(created_at) AS payment_date,
    status,
    COUNT(*) AS cnt,
    SUM(amount) AS total_amount
FROM ticketing_payment.payments
WHERE created_at >= CURDATE() - INTERVAL 7 DAY
GROUP BY DATE(created_at), status
ORDER BY payment_date DESC, status;

-- 오늘 결제 이상 상태 확인
SELECT
    status,
    COUNT(*) AS cnt,
    MIN(created_at) AS oldest,
    MAX(created_at) AS newest
FROM ticketing_payment.payments
WHERE DATE(created_at) = CURDATE()
  AND status IN ('CANCEL_FAILED', 'FAILED')
GROUP BY status;

-- 전체 기간 CANCEL_FAILED 잔류 확인
SELECT id, order_id, payment_key, amount, created_at
FROM ticketing_payment.payments
WHERE status = 'CANCEL_FAILED'
ORDER BY created_at DESC;

-- 시간대별 결제 성공률 (오늘)
SELECT
    HOUR(created_at) AS hour,
    COUNT(*) AS total,
    SUM(CASE WHEN status = 'APPROVED' THEN 1 ELSE 0 END) AS approved,
    ROUND(SUM(CASE WHEN status = 'APPROVED' THEN 1 ELSE 0 END) / COUNT(*) * 100, 1) AS success_rate_pct
FROM ticketing_payment.payments
WHERE DATE(created_at) = CURDATE()
GROUP BY HOUR(created_at)
ORDER BY hour;
```

---

### RPO / RTO 목표

**현재 상태**: RPO/RTO 목표 미정의 (staging 환경이므로 SLA 없음)

**권장 목표 (Planned/Proposed)**

| 데이터 종류 | RPO 권장 | RTO 권장 | 근거 |
|-------------|----------|----------|------|
| ticketing_payment | 1시간 | 30분 | AWS RDS automated backup(hourly) + Multi-AZ failover |
| ticketing_booking | 1시간 | 30분 | 동일 |
| ticketing_user | 1시간 | 1시간 | 로그인 세션 재발급으로 복구 가능 |
| ticketing_waitingroom | N/A | 10분 | Redis 장애 시 재진입으로 자연 복구 |
| CANCEL_FAILED 처리 | - | 4시간 | 고객 자산 관련, 수동 처리 포함 |

**현재 실제 RPO**: 마지막 수동 백업 시점 (보장 불가)

---

## Failure Scenarios

### 시나리오 1: 스테이징 서버(192.168.124.100) 전체 장애

- **증상**: 모든 서비스 응답 없음, Docker Compose 컨테이너 정지
- **영향**: 서비스 전체 중단. 단, MySQL 데이터는 디스크에 보존 (컨테이너 재시작 시 복구 가능)
- **복구 절차**:
  ```bash
  # 1. 서버 재시작 후 Docker Compose 재시작
  docker compose up -d

  # 2. 서비스 기동 순서: MySQL → Redis → 각 app
  # (Docker Compose depends_on 설정에 따라 자동 처리될 수 있음)

  # 3. 헬스체크
  curl -s http://192.168.124.100/actuator/health
  curl -s http://192.168.124.100/api/v1/payments/actuator/health
  ```
- **예상 RTO**: 10-30분 (서버 부팅 + 컨테이너 재시작 + 헬스체크)

### 시나리오 2: Redis 노드(192.168.124.101) 장애

- **증상**: `RedisConnectionException` 로그 급증, 대기열 서비스 오류, payment idempotency 저하
- **영향**: 대기열 초기화, idempotency 키 손실 (DB UK constraint로 부분 보완)
- **복구 절차**:
  ```bash
  # 1. Redis 재시작
  docker restart redis-node
  # 또는
  ssh 192.168.124.101 "sudo systemctl restart redis"

  # 2. Redis 연결 확인
  redis-cli -h 192.168.124.101 -p 6379 -a <password> PING

  # 3. 애플리케이션 Redis 연결 재수립 확인
  docker restart waitingroom-app payment-app

  # 4. 대기열 재개: 사용자 재진입 안내
  ```
- **대기열 복구**: 사용자가 재진입하면 자동 복구. 별도 데이터 복원 불필요.
- **예상 RTO**: 5-15분

### 시나리오 3: AWS RDS 장애

- **증상**: MySQL 연결 실패, `HikariPool` timeout, 서비스 DB 의존 기능 전체 중단
- **현재 한계**: Multi-AZ 설정 여부 미확인 → 자동 failover 보장 불가
- **복구 절차**:
  ```
  [Planned] RDS Multi-AZ 설정 시:
  1. AWS RDS 자동 failover (보통 1-2분)
  2. 애플리케이션 재시작 (새 엔드포인트로 연결)

  [현재 가능한 방법]
  1. RDS 콘솔에서 장애 원인 확인
  2. 최신 스냅샷으로 새 RDS 인스턴스 복구
  3. SPRING_DATASOURCE_URL 업데이트 후 서비스 재배포
  ```
- **읽기 전용 모드 전환** (planned): 결제 불가 상태를 명시적으로 사용자에게 안내하는 fallback 로직 (미구현)

### 시나리오 4: 전체 장애 (스테이징 서버 + AWS RDS 동시 장애)

- **복구 절차** (모두 Planned):
  1. AWS EC2 새 인스턴스 생성
  2. Docker Compose 설정 배포
  3. AWS RDS 최신 스냅샷으로 새 인스턴스 복구
  4. 환경변수 업데이트 (새 RDS 엔드포인트)
  5. 서비스 재기동
  6. 헬스체크 및 smoke test
- **예상 RTO**: 1-2시간 (스냅샷 복구 시간 포함)
- **RPO**: 마지막 RDS 자동 백업 시점 (hourly 설정 시 최대 1시간 손실)

### 시나리오 5: ddl-auto=update 스키마 오염

- **증상**: 잘못된 엔티티 배포로 컬럼 타입 변경 또는 데이터 손상
- **위험**: Flyway가 없으므로 이전 스키마 상태로 자동 복원 불가
- **복구 절차**:
  1. 배포 직전 mysqldump 백업 확인 (있으면 복구 가능)
  2. 백업 없으면 스키마 변경 전 상태를 수동으로 재현
  3. ALTER TABLE로 수동 스키마 롤백
  ```sql
  -- 예시: 타입 변경 롤백
  ALTER TABLE ticketing_payment.payments
  MODIFY COLUMN amount BIGINT NOT NULL;
  ```
- **교훈**: 배포 전 mysqldump 백업이 유일한 안전망

---

## Observability / Detection

**메트릭으로 탐지 가능한 것**
- `hikaricp_connections_active` 급증 → DB 연결 풀 고갈 징후
- `redis_connected_clients` 감소 → Redis 연결 문제
- `http_server_requests_seconds_count{status="5xx"}` 급증 → 서비스 오류

**로그로 탐지 가능한 것**
- `RedisConnectionException` → Redis 장애
- `HikariPool-1 - Connection is not available` → DB 연결 풀 고갈
- `com.mysql.cj.jdbc.exceptions` → MySQL 연결 오류
- `TossPaymentsException` with `P005` → 결제 인증 오류

**DB 직접 쿼리로 탐지하는 것 (자동화 미구현, 수동 실행)**
```sql
-- 매일 실행 권장: 이상 상태 전수 확인
SELECT status, COUNT(*) FROM ticketing_payment.payments
WHERE created_at >= CURDATE()
GROUP BY status;

-- CANCEL_FAILED 잔류 알람 기준
SELECT COUNT(*) FROM ticketing_payment.payments WHERE status = 'CANCEL_FAILED';
-- 결과 > 0 이면 즉시 처리

-- READY 장시간 잔류 알람 기준
SELECT COUNT(*) FROM ticketing_payment.payments
WHERE status = 'READY' AND created_at < NOW() - INTERVAL 10 MINUTE;
-- 결과 > 0 이면 원인 조사
```

**현재 자동화 감지 미구현 항목**
- CANCEL_FAILED 발생 시 Slack/PagerDuty 알림
- 백업 성공/실패 알림
- RDS 백업 상태 모니터링

---

## Recovery / Mitigation

**데이터 복구 우선순위**

1. `ticketing_payment.payments` — 고객 자산 직결, 최우선
2. `ticketing_booking.bookings` — 좌석 중복 방지의 source of truth
3. `ticketing_concert` — 마스터 데이터, 재구성 가능성 상대적으로 높음
4. `ticketing_user` — 계정 재가입/복구 가능
5. `ticketing_waitingroom` — 재진입으로 자연 복구

**CANCEL_FAILED 처리 SOP (Standard Operating Procedure)**

```
1. Toss Payments 콘솔에서 payment_key로 실제 결제 상태 조회
2. 케이스 A: Toss에서 이미 취소 완료 → DB status를 REFUNDED로 업데이트
3. 케이스 B: Toss에서 아직 취소 안 됨 → Toss API로 취소 재시도 → 성공 시 REFUNDED 업데이트
4. 케이스 C: Toss 취소 재시도도 실패 → CS팀 에스컬레이션, 고객 수동 환불 처리
5. 처리 결과 기록 (어떤 케이스였는지, 언제 처리됐는지)
```

---

## Trade-offs

**ddl-auto=update vs Flyway**

ddl-auto=update는 개발 속도를 높이지만 운영 환경에서는 여러 문제를 만든다. 스키마 변경 이력이 없어 "언제 어떤 컬럼이 추가됐는지" 알 수 없고, 잘못된 변경이 자동으로 적용되면 수동으로 롤백해야 한다. Flyway는 마이그레이션 파일로 이력을 관리하고 롤백 스크립트를 미리 작성할 수 있지만, 도입 초기에 기존 스키마와 마이그레이션 파일을 맞추는 작업이 필요하다. 현재는 개발 속도를 택했지만, 운영 환경 전환 전 Flyway 도입이 필요하다.

**단일 Redis 노드**

별도 Redis 노드(192.168.124.101)를 두는 것은 애플리케이션 서버와 캐시를 분리하는 면에서 맞는 방향이다. 그러나 단일 노드이므로 Redis 장애 시 대기열과 idempotency 키가 모두 사라진다. Redis Sentinel이나 Cluster로 고가용성을 확보하면 이 위험을 줄일 수 있지만, 현재 staging 환경에서는 대기열 재진입으로 자연 복구되는 특성을 이용해 단순하게 운영한다.

**수동 백업 의존**

정기 자동 백업이 없으므로 마지막 수동 백업 이후의 데이터는 복구 불가다. 이 리스크는 낮은 개발 단계에서는 허용 가능하지만, 실제 결제가 발생하는 production 환경에서는 반드시 자동 백업과 PITR이 필요하다.

---
