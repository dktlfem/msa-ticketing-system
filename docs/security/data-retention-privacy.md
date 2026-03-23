---
title: "Data Retention & Privacy: 데이터 보유·삭제·최소화 원칙"
last_updated: 2026-03-18
author: "민석"
reviewer: ""
---

## 목차
- [Background](#background)
- [Problem](#problem)
- [Current Design](#current-design)
- [Risks](#risks)
- [Controls / Mitigations](#controls-mitigations)
- [Planned Improvements](#planned-improvements)
- [Trade-offs](#trade-offs)
- [Failure Scenarios](#failure-scenarios)

# Data Retention & Privacy: 데이터 보유·삭제·최소화 원칙

> **범위**: 이 문서는 서비스별 데이터 분류, 보유 기간 원칙, 로그/메트릭에서 제외해야 할 민감 정보, `pg_response_raw` 저장의 주의점을 다룬다.
> 백업 절차·mysqldump 명령·RPO/RTO 목표는 [`docs/operations/backup-dr.md`](../operations/backup-dr.md)를 참조한다. 이 문서는 그 내용을 반복하지 않는다.
> DB 스키마와 Redis 키 패턴 상세는 [`docs/data/database-cache-design.md`](../data/database-cache-design.md)를 참조한다.

---

## Background

티켓팅 시스템은 세 가지 유형의 민감 데이터를 처리한다.

1. **개인 식별 정보 (PII)**: 이름, 이메일, 전화번호 — user-app 소유
2. **금융 트랜잭션 데이터**: 결제 금액, TossPayments 응답 원문, 결제 수단 — payment-app 소유
3. **행동 데이터**: 예약 이력, 대기열 순번, 좌석 선택 패턴 — booking-app / waitingroom-app 소유

이 세 유형은 보유 기간, 삭제 방식, 로깅 허용 범위가 다르다. 현재 시스템은 이 구분을 명시적으로 구현하지 않았다. 이 문서는 현재 상태를 정직하게 기록하고 필요한 통제를 제안한다.

---

## Problem

현재 시스템에서 데이터 retention/privacy 관련 해결되지 않은 문제:

1. **보유 기간 미정의**: 결제 데이터를 얼마나 보관해야 하는지 명시된 정책이 없다. 법적 의무(국내 전자상거래법 5년, 개인정보보호법 목적 달성 후 즉시 파기)와의 관계가 정의되지 않았다.
2. **`pg_response_raw` 미검토**: TossPayments 응답 원문이 TEXT 컬럼에 저장된다. 이 JSON에 카드번호 마스킹 여부, 개인정보 포함 여부를 확인하지 않았다.
3. **로그에 민감 정보 포함 가능성**: traceId/spanId가 로그에 자동 주입되지만, 로그에 `userId`, `orderId`, `amount`가 INFO 레벨로 기록된다. 이 정보가 Kibana/Grafana 대시보드에서 어디까지 노출되는지 통제되지 않았다.
4. **삭제 메커니즘 없음**: 사용자가 계정 삭제를 요청할 경우 관련 예약·결제 데이터를 어떻게 처리할지 구현이 없다.
5. **스테이징/운영 데이터 분리 불완전**: staging 환경에서 운영 환경 포맷의 데이터를 사용하며, 실제 사용자 PII가 스테이징 DB에 들어갈 경우 보호 수준이 낮다.

---

## Current Design

### 데이터 분류표

| 데이터 | 서비스 | 분류 | 현재 저장 위치 | 민감도 |
|--------|--------|------|--------------|--------|
| 이름, 이메일, 전화번호 | user-app | PII | `ticketing_user.users` | 높음 |
| userId (내부 식별자) | 전체 서비스 | 내부 식별자 | 각 서비스 DB, 로그, 헤더 | 중간 |
| 예약 정보 (seat_id, 예약 시각) | booking-app | 행동 데이터 | `ticketing_booking.reservations` | 중간 |
| 결제 금액, 결제 수단, 상태 | payment-app | 금융 데이터 | `ticketing_payment.payments` | 높음 |
| orderId, paymentKey | payment-app | PG 식별자 | `ticketing_payment.payments` | 높음 |
| `pg_response_raw` | payment-app | PG 원문 | `ticketing_payment.payments.pg_response_raw TEXT` | **검토 필요** |
| fail_reason | payment-app | 오류 메시지 | `ticketing_payment.payments.fail_reason VARCHAR(500)` | 낮음 |
| idempotency key | payment-app | 임시 중복 방지 키 | Redis `payment:idempotency:{key}`, TTL 24h | 낮음 |
| 대기열 순번 (userId, 진입 시각) | waitingroom-app | 행동 데이터 | Redis Sorted Set (이벤트 종료 시 소멸) | 낮음 |
| traceId, spanId | 전체 서비스 | 추적 식별자 | 로그, Micrometer trace | 낮음 |

---

### pg_response_raw 저장 주의점

**저장 이유**: TossPayments 결제 분쟁 시 PG 원문 응답이 증거 자료가 된다. 스토리지 비용 < 분쟁 처리 비용이라는 판단 하에 TEXT 컬럼에 전체를 저장한다.

**검토가 필요한 이유**: TossPayments API 응답 JSON에 포함될 수 있는 정보:
- `card.number`: 마스킹된 카드번호 (예: `123412******1234`) — **PII에 해당**
- `card.ownerType`: 개인/법인 구분
- `card.issuerCode`, `card.acquirerCode`: 카드사 코드
- `receiptUrl`: 영수증 URL (개인 결제 영수증 링크)
- 기타 PG 응답 필드

**현재 처리 방식**: 응답 전체를 그대로 저장한다. 필드 선택적 저장이나 민감 필드 마스킹 없음.

**리스크**: `pg_response_raw`를 로그에 출력하거나 API 응답에 포함하면 카드번호 마스킹 정보가 노출된다. 현재 `PaymentResponseDTO`에는 `pg_response_raw` 필드가 없으므로 API 응답 노출은 없다. 그러나 `jpa.show-sql=true` 설정이 켜져 있어 SQL 로그에 값이 출력될 수 있다.

**권장 처리**:
1. TossPayments 응답에서 `card.number` 등 카드 관련 필드를 마스킹 후 저장 (planned)
2. 또는 감사 목적 전용 분리 테이블에 저장하고 조회 권한을 별도 관리 (planned)
3. `jpa.show-sql=false`를 운영 환경에서 강제 (planned)

---

### 로그/메트릭에서 제외해야 할 정보

**현재 로그 패턴** (`payment-app/application.properties`):
```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId:-no-trace},%X{spanId:-no-span}] [%thread] %-5level %logger{36} - %msg%n
```

**PaymentManager 현재 INFO 로그 예시**:
```
Payment created - paymentId=50001, reservationId=90001, orderId=RES90001_1710567600123, amount=150000
Payment approved - paymentId=50001, orderId=RES90001_1710567600123
```

**로그에 포함되면 안 되는 항목**:

| 항목 | 이유 | 현재 상태 |
|-----|------|---------|
| 카드번호 (마스킹 포함) | PII. 카드번호 마스킹도 로그 적재 금지 | pg_response_raw가 로그에 출력되지 않도록 주의 필요 |
| `paymentKey` 전체값 | TossPayments에서 결제 취소에 사용 가능한 키 — 유출 시 악용 가능 | 현재 CRITICAL 로그 등에 포함 여부 확인 필요 |
| 비밀번호, OTP, 세션 토큰 | 직접적 계정 탈취 수단 | user-app 로그에 포함 여부 확인 필요 |
| `fail_reason` 전체 내용 | PG 오류 메시지에 카드 정보가 포함될 수 있음 | 현재 로그에 포함됨 (확인 필요) |
| 이름, 이메일, 전화번호 | 직접 PII | user-app 로그 확인 필요 |
| `pg_response_raw` | 카드 정보 포함 가능 | show-sql=true 시 SQL 로그에 노출 가능 |

**Grafana/Prometheus 메트릭에서 허용/금지**:

| 메트릭 | 허용 여부 | 근거 |
|--------|---------|------|
| `http_server_requests_seconds{status=..., uri=...}` | 허용 | URI와 상태코드만 포함 |
| `payment_confirm_total{result="success/pg_error/..."}` counter | 허용 | 집계 수치, 개인 식별 없음 <!-- 2026-03-18 메트릭 명칭 통일 --> |
| `userId` label이 포함된 메트릭 | **금지** | userId가 메트릭 label이 되면 카디널리티 폭발 + PII 노출 |
| `orderId` label이 포함된 메트릭 | **금지** | 개별 트랜잭션 식별 가능 |

---

### 데이터 보유 기간 (제안)

> 아래 보유 기간은 현재 시스템에 구현된 값이 아니다. 국내 법규 해석 및 운영 정책을 기반으로 제안하는 초기값이다.

| 데이터 | 제안 보유 기간 | 근거 | 현재 구현 |
|--------|------------|------|---------|
| 결제 기록 (payments 테이블) | **5년** | 전자상거래법 제6조: 대금결제 및 재화 공급 기록 5년 보존 의무 | 자동 삭제 없음 — 수동 관리 |
| 예약 기록 (reservations 테이블) | **5년** | 위와 동일 | 자동 삭제 없음 |
| 사용자 계정 정보 (users 테이블) | 탈퇴 후 즉시 또는 최대 1년 | 개인정보보호법: 목적 달성 후 지체 없이 파기 | 삭제 기능 없음 |
| Redis idempotency key | **24시간** (TTL 자동 만료) | 재시도 창을 초과하면 불필요 | **구현됨** |
| Redis 대기열 토큰 | 이벤트 종료 시 (TTL 만료) | 대기열은 이벤트에 귀속 | **구현됨** |
| 로그 (Kibana/파일) | **90일** (제안) | 인시던트 조사 주기 기반 — 결제 분쟁 30일 + 여유 | 로그 rotation 미설정 |
| Prometheus 메트릭 | **13개월** (제안) | YoY 비교 가능, Grafana 기본 권장 보유 기간 | 설정 미확인 |
| `pg_response_raw` | 결제 기록과 동일 (5년) | 분쟁 증거 목적, 별도 삭제 어려움 | 자동 삭제 없음 |

---

### 스테이징 환경 데이터 보호 한계

현재 스테이징 환경:
- IP: 192.168.124.100 (WSL2 + Docker Compose)
- MySQL: 컨테이너 내부 `mysql:3306` (호스트 포트 미노출), 인증 `root / ${SPRING_DATASOURCE_PASSWORD:1234}`
- Redis: 192.168.124.101:6379, VPN 내부망

**알려진 한계**:

| 항목 | 현재 상태 | 리스크 |
|-----|---------|--------|
| 스테이징 DB 자격증명 | 기본값 `root / 1234` | 환경변수 미주입 시 누구나 루트 접근 |
| 스테이징 ↔ 운영 데이터 분리 | 스키마 분리됨, 데이터는 별도 | 실수로 운영 DB 주소를 스테이징에 설정하면 운영 데이터 노출 가능 |
| 스테이징에서 실 PII 사용 금지 | 정책 문서화 안 됨 | 개발자가 운영 데이터 덤프를 스테이징에 올릴 수 있음 |
| 테스트 결제 키 분리 | `test_sk_placeholder` 기본값 | TossPayments test key는 sandbox. 운영 key를 스테이징에 주입하지 않아야 함 |

**운영 환경 요구사항** (planned):

운영 환경에 이 시스템을 배포할 경우 추가로 필요한 통제:
1. 스테이징 DB에는 익명화된 테스트 데이터만 사용
2. 운영/스테이징 TossPayments key 환경변수 분리 (`prod` / `staging` profile 강제)
3. 스테이징에서 실 사용자 PII를 복사하는 절차 금지 명문화

---

## Risks

### R1: pg_response_raw 카드 정보 저장 (높음, 미검토)

TossPayments 응답에 카드번호 마스킹 값이 포함될 경우, 이 값이 TEXT 컬럼에 저장되고 `show-sql=true` 환경에서 SQL 로그에 출력된다. 로그가 Kibana에 수집되면 카드 관련 PII가 로그에 평문으로 남는다.

**완화**: `jpa.show-sql=false` 운영 환경 강제, pg_response_raw 저장 전 민감 필드 마스킹 (planned)

### R2: 데이터 삭제 기능 없음 (중간)

사용자 탈퇴, 개인정보 삭제 요청 시 처리 방법이 구현되지 않았다. 국내 개인정보보호법은 목적 달성 후 지체 없는 파기를 요구한다.

**완화**: 법적 의무 있는 데이터(결제 5년)와 파기 대상(PII)을 분리 저장하는 스키마 설계 검토 (planned)

### R3: 로그 retention 미설정 (낮음)

로그 파일 또는 Kibana 인덱스의 보유 기간이 설정되지 않아 무기한 누적될 수 있다. userId, orderId 등이 로그에 포함되므로 불필요하게 장기 보관되면 PII 최소화 원칙에 어긋난다.

---

## Controls / Mitigations

### 현재 적용된 통제

| 통제 | 구현 방식 | 효과 |
|-----|---------|------|
| idempotency key TTL | Redis 24시간 TTL 자동 만료 | 재시도 창 종료 후 자동 삭제 |
| 대기열 토큰 자동 만료 | Redis TTL (이벤트별) | 이벤트 종료 후 대기열 데이터 자동 소멸 |
| pg_response_raw API 미노출 | PaymentResponseDTO에 해당 필드 없음 | API 응답에 PG 원문 포함 안 됨 |
| 결제 금액 DB 단독 관리 | Redis에 amount 캐시 없음 | 금액 위변조 불가 |
| PG secret 환경변수 관리 | TOSS_PAYMENTS_SECRET_KEY 환경변수 | 코드 저장소에 secret 없음 |

### 즉시 적용 가능한 완화

1. **`jpa.show-sql=false`**: 운영/스테이징 프로파일에 강제 설정 — SQL 로그에 pg_response_raw 값 출력 차단
2. **`logging.level.org.hibernate.SQL=OFF`**: 프로파일 기반 SQL 로그 비활성화
3. **로그에서 paymentKey 마스킹**: `pay_****{last4}` 형식으로 출력 (코드 변경 필요)

---

## Planned Improvements

### 1. pg_response_raw 민감 필드 마스킹 (planned)

TossPayments 응답 저장 전 `card.number` 등 카드 관련 필드를 마스킹 또는 제외.
저장할 필드를 명시적으로 선택(allowlist)하는 방식 권장.

### 2. 개인정보 삭제 API (planned)

user-app에 DELETE /api/v1/users/{id} 구현 시:
- users 테이블: 이름, 이메일, 전화번호를 익명화 (삭제 대신 anonymize — 결제 FK 연결 유지)
- 결제 기록: userId 컬럼 anonymized_user_id로 교체 또는 별도 보관
- 예약 기록: 동일

### 3. 로그 retention 설정 (planned)

- Kibana 인덱스 lifecycle policy: 90일 후 삭제
- 파일 로그: logrotate 7일 압축 + 90일 삭제

### 4. 스테이징 데이터 익명화 파이프라인 (planned)

운영 데이터를 스테이징으로 이관할 경우:
- 이름, 이메일, 전화번호를 faker로 치환
- cardNumber 마스킹 값도 더미 값으로 교체
- pg_response_raw는 sandbox 응답 예시로 교체

### 5. show-sql 프로파일 분리 (planned)

```properties
# application-dev.properties
spring.jpa.show-sql=true

# application-prod.properties
spring.jpa.show-sql=false
logging.level.org.hibernate.SQL=OFF
```

---

## Trade-offs

| 결정 | 얻은 것 | 잃은 것 |
|------|---------|---------|
| pg_response_raw 전체 저장 | PG 분쟁 시 원문 증거 보유 | 카드 관련 필드가 DB에 저장됨 — 마스킹 없이 저장 중 |
| Redis TTL 기반 idempotency 만료 | 24시간 후 자동 삭제 — 별도 삭제 로직 불필요 | TTL 이후 동일 key 재사용 가능 (의도된 동작) |
| userId를 모든 테이블에 직접 저장 | 서비스 간 조회 단순화 | userId 익명화 시 전체 서비스 스키마 변경 필요 |
| 결제 금액 DB 단독 관리 | 금액 위변조 차단 | Redis 캐시 불가 — 모든 결제 검증이 DB hit |
| 스테이징에서 운영 포맷 데이터 사용 | 실제와 유사한 테스트 환경 | 실 PII 유입 시 보호 수준 낮음 |

---

## Failure Scenarios

### pg_response_raw에 카드 PII가 포함된 경우

1. DB에서 해당 레코드 조회: `SELECT pg_response_raw FROM ticketing_payment.payments WHERE payment_id = {id}`
2. 카드번호 마스킹 값이 있는 경우: 해당 컬럼 값을 마스킹 처리 후 업데이트 또는 해당 필드 제거한 JSON으로 업데이트
3. 로그에 이미 출력된 경우: Kibana 인덱스에서 해당 document 삭제 (Elasticsearch delete by query)
4. 재발 방지: `jpa.show-sql=false` 즉시 적용, pg_response_raw 마스킹 로직 추가

### 사용자 개인정보 파기 요청

현재 구현 없음. 수동 절차:
```sql
-- user-app: PII 익명화 (삭제 아닌 익명화 — 결제 이력 보존 필요)
UPDATE ticketing_user.users
SET name = 'DELETED_USER', email = CONCAT('deleted_', user_id, '@invalid'), phone = NULL
WHERE user_id = {id};
-- 결제/예약 데이터는 법적 보존 의무로 5년 유지
```

---
