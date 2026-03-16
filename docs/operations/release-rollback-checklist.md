# 배포 및 롤백 운영 체크리스트

## Background

MSA 기반 티켓팅 시스템은 6개 서비스(scg-app, waitingroom-app, concert-app, booking-app, payment-app, user-app)로 구성된다. 각 서비스는 독립적으로 배포되며, 배포 실패나 장애가 발생했을 때 신속하고 정확한 절차 없이는 서비스 간 정합성이 깨질 수 있다.

payment-app은 특히 민감하다. 외부 결제 대행사(Toss Payments)와 연동되어 있고, 결제 상태가 중간에 꼬이면 고객 돈이 실제로 묶일 수 있다. 이 문서는 그 위험을 최소화하기 위한 실제 운영 절차를 기록한다.

## Problem

배포 과정에서 발생할 수 있는 주요 문제:

1. **환경변수 오설정**: `TOSS_PAYMENTS_SECRET_KEY`에 test 키 대신 live 키가 들어가거나 그 반대 — staging에서 실결제가 발생하거나, production에서 결제가 안 되는 상황
2. **ddl-auto=update 위험**: JPA가 스키마를 자동으로 변경한다. 컬럼 삭제나 타입 변경이 엔티티에 반영되면 배포 시점에 기존 데이터가 손상되거나 서비스가 시작 불가 상태가 될 수 있다
3. **CANCEL_FAILED 상태 잔류 중 롤백**: 결제는 승인됐지만 취소 요청이 실패한 상태. 이 상태에서 rollback하면 고객 돈이 실제로 묶인다
4. **Blue/Green 전환 중 in-flight 요청**: 슬롯 전환 시점에 처리 중이던 결제 요청의 orderId/paymentKey 정합성이 깨질 수 있다
5. **SCG route 인덱스 변경**: `spring.cloud.gateway.routes[3]`이 payment-service를 가리키고 있는데, route 순서가 바뀌면 요청이 엉뚱한 서비스로 라우팅된다

## Current Design

> CI/CD 파이프라인 다이어그램, Blue/Green 배포 설계 이유, 전체 네트워크 토폴로지는 `docs/02-architecture-infrastructure.md` 참조.

이 문서에서는 운영 절차에 필요한 최소한의 배포 흐름만 설명한다.

```
GitLab push
  → Jenkins pipeline (build → test → Docker image → push)
  → EC2 비활성 슬롯에 app.jar / Docker container 배포
  → /actuator/health 확인
  → Nginx upstream을 활성 슬롯으로 전환
  → 이전 슬롯은 롤백용으로 대기
```

**스테이징 환경**
- 애플리케이션 서버: `192.168.124.100` (WSL2 + Docker Compose)
- Redis: `192.168.124.101:6379`
- MySQL: `192.168.124.100:33066`
- payment-app 스키마: `ticketing_payment`
- payment-app health: `GET /actuator/health`
- payment-app metrics: `GET /actuator/prometheus`

**payment-app 주요 환경변수**

| 변수명 | 설명 |
|--------|------|
| `TOSS_PAYMENTS_SECRET_KEY` | `test_sk_` 접두사 = 테스트 모드, `live_sk_` 접두사 = 실결제 |
| `BOOKING_APP_URL` | booking-app 직접 주소 (production에서 nginx_proxy 경유 금지) |
| `CONCERT_APP_URL` | concert-app 주소 |
| `SPRING_DATASOURCE_URL` | `ticketing_payment` 스키마를 가리켜야 함 |
| `SPRING_DATA_REDIS_HOST` | Redis 호스트 |
| `REDIS_PASSWORD` | Redis 인증 |

---

## Operational Procedure

### 배포 전 체크리스트 (Pre-deployment)

**빌드 확인**

- [ ] 빌드 성공 확인
  ```bash
  ./gradlew :payment-app:build -x test
  ```
- [ ] 테스트 포함 빌드 (CI에서 통과 여부)
  ```bash
  ./gradlew :payment-app:test
  ```

**환경변수 확인**

- [ ] `TOSS_PAYMENTS_SECRET_KEY` 값 확인
  - staging: `test_sk_` 접두사여야 함
  - production: `live_sk_` 접두사여야 함
  - **두 환경이 바뀌어 있으면 배포 중단**
- [ ] `BOOKING_APP_URL` 확인
  - production에서 `nginx_proxy` 경유하면 안 됨 (내부 서비스 직접 통신)
- [ ] `CONCERT_APP_URL` 확인
- [ ] `SPRING_DATASOURCE_URL` 확인
  - `ticketing_payment` 스키마를 가리키는지 확인
  - 다른 서비스(ticketing_booking 등)를 가리키면 데이터 오염
- [ ] `SPRING_DATA_REDIS_HOST` / `REDIS_PASSWORD` 확인

**DB 상태 확인**

- [ ] `ddl-auto=update` 위험 체크: 이번 배포에서 엔티티 변경 사항 확인
  - 컬럼 추가: 안전 (기존 row는 null 또는 default)
  - 컬럼 삭제: **위험** — 배포 전 schema diff 필수, 기존 데이터 손실 가능
  - 타입 변경: **위험** — 서비스 시작 실패 또는 데이터 손상 가능
  - 엔티티 삭제: **위험** — JPA가 테이블을 drop하지는 않지만 연관 로직 확인 필요

- [ ] `CANCEL_FAILED` 상태 잔류 확인 (이 상태가 있으면 배포 절대 금지)
  ```sql
  SELECT COUNT(*) FROM ticketing_payment.payments
  WHERE status = 'CANCEL_FAILED';
  ```
  - 결과가 0이 아니면: 수동으로 Toss Payments 콘솔에서 취소 처리 후 상태 업데이트 선행

- [ ] `READY` 상태 장시간 잔류 확인 (10분 이상 READY면 이상 징후)
  ```sql
  SELECT COUNT(*) FROM ticketing_payment.payments
  WHERE status = 'READY'
    AND created_at < NOW() - INTERVAL 10 MINUTE;
  ```
  - 결과가 있으면: 원인 확인 후 처리 (Toss Payments 웹훅 누락 여부 확인)

**인프라 확인**

- [ ] SCG route 인덱스 확인: `spring.cloud.gateway.routes[3].predicates[0]=Path=/api/v1/payments/**`
  - scg-app 설정 파일에서 payment-service가 index 3번인지 확인
  - route 순서 변경 시 반드시 SCG 재배포와 함께 진행
- [ ] 현재 blue/green 활성 슬롯 확인 (어느 슬롯이 live인지 기록)
  ```bash
  # Nginx upstream 설정 확인
  cat /etc/nginx/conf.d/payment-upstream.conf
  ```

---

### 배포 중 체크리스트 (During Deployment)

- [ ] Jenkins pipeline 단계별 확인 (빌드 → 테스트 → 이미지 빌드 → 배포)
- [ ] 비활성 슬롯에 `app.jar` 배포 (활성 슬롯 건드리지 않음)
- [ ] payment-app 기동 후 `/actuator/health` 응답 확인
  ```bash
  curl -s http://192.168.124.100:<비활성슬롯포트>/actuator/health
  # 기대 응답: {"status":"UP"}
  ```
- [ ] `/actuator/prometheus` 응답 확인 (metrics endpoint 정상 여부)
  ```bash
  curl -s http://192.168.124.100:<비활성슬롯포트>/actuator/prometheus | head -20
  ```

- [ ] payment-app 기동 로그 확인
  ```bash
  docker logs payment-app-green 2>&1 | grep -E "(TossPayments|Redis|HikariPool|ERROR|WARN)" | tail -50
  ```
  확인 항목:
  - [ ] TossPayments client 초기화 로그 — 예외 없이 정상 초기화
  - [ ] Redis connection 확인 — `Connected to Redis` 또는 유사 성공 로그
  - [ ] DB connection pool 확인 — HikariPool 초기화 성공, `ticketing_payment` 스키마 접근 확인

- [ ] Staging 결제 smoke test (Nginx 전환 전, 비활성 슬롯 직접 호출)
  ```bash
  # test_sk_ 키 사용 중인지 반드시 확인 후 진행
  curl -X POST http://192.168.124.100:<비활성슬롯포트>/api/v1/payments/request \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer <test_token>" \
    -d '{"bookingId": "<test_booking_id>", "amount": 1000}'
  ```

- [ ] smoke test 결과 확인
  ```sql
  SELECT id, order_id, status, amount, created_at
  FROM ticketing_payment.payments
  ORDER BY created_at DESC
  LIMIT 1;
  ```

- [ ] 이상 없으면 Nginx upstream을 비활성 슬롯으로 전환
  ```bash
  # 예시: Nginx 설정 변경 후 reload
  nginx -s reload
  ```

---

### 배포 후 검증 체크리스트 (Post-deployment)

- [ ] **Grafana**: payment-service 메트릭 수신 확인
  - 태그: `management.metrics.tags.application=payment-service`
  - 확인 지표: JVM heap, HTTP request rate, DB connection pool
- [ ] **Kibana**: payment-app 로그 수신 확인
  - 최근 5분 내 로그가 Kibana에 유입되는지 확인
- [ ] **SCG route 검증**: 실제 gateway를 통한 요청이 라우팅되는지 확인
  ```bash
  curl -X POST http://192.168.124.100/api/v1/payments/request
  # 401 (인증 없음) 또는 400 (요청 오류) 응답 = 라우팅 정상
  # 404 응답 = SCG route 설정 오류
  # Connection refused = payment-app 미기동
  ```
- [ ] 배포 5분 후 에러율 확인 — 5xx 급증 없음 확인
- [ ] P99 레이턴시 기준선 확인 (이전 배포 대비 이상 증가 없음)
- [ ] 이전 슬롯은 롤백 대기 상태로 유지 (즉시 내리지 않음)

---

### 롤백 조건 (Rollback Triggers)

다음 중 하나라도 해당하면 즉시 롤백을 고려한다.

- [ ] `/actuator/health` 응답 `DOWN` 또는 미응답
- [ ] 5xx 에러율이 배포 전 기준선 대비 급증 (Grafana 알림 기준)
- [ ] `CANCEL_FAILED` 상태 신규 발생
  ```sql
  SELECT COUNT(*) FROM ticketing_payment.payments
  WHERE status = 'CANCEL_FAILED'
    AND created_at > '<배포시각>';
  ```
- [ ] Redis 연결 실패 로그 반복 발생
- [ ] Toss Payments 인증 실패 (에러코드 `P005`) 급증 — `TOSS_PAYMENTS_SECRET_KEY` 오설정 의심
- [ ] DB 연결 풀 고갈 — HikariPool timeout 로그 반복

---

### 롤백 절차 (Rollback Procedure)

**롤백 전 필수 확인**

```sql
-- CANCEL_FAILED 상태 잔류 여부 확인 (롤백 전 반드시 0이어야 함)
SELECT id, order_id, payment_key, status, amount, created_at
FROM ticketing_payment.payments
WHERE status = 'CANCEL_FAILED';
```

- **CANCEL_FAILED가 있으면 롤백 전에 수동 처리 선행**
  - Toss Payments 콘솔 또는 API로 해당 결제 취소 완료
  - DB 상태 수동 업데이트 후 롤백 진행

**롤백 실행**

```bash
# 1. Nginx upstream을 이전 슬롯으로 전환
# (실제 명령은 환경에 따라 다름 — Nginx 설정 파일 기준으로 수정)
nginx -s reload

# 2. 전환 확인
curl -s http://192.168.124.100/api/v1/payments/health
# 또는
curl -s http://192.168.124.100/actuator/health
```

**롤백 후 확인**

```sql
-- 롤백 시점 전후 READY 상태 결제 확인
-- 이 결제들은 orderId/paymentKey 정합성 수동 확인 필요
SELECT id, order_id, payment_key, status, amount, created_at
FROM ticketing_payment.payments
WHERE status = 'READY'
  AND created_at BETWEEN '<롤백시작시각>' AND '<롤백완료시각>';
```

- [ ] 롤백 완료 확인: `/actuator/health` UP 응답
- [ ] `CANCEL_FAILED` 잔류 없음 재확인
- [ ] **롤백 원인 분석 완료 전 재배포 절대 금지**
- [ ] 롤백 사유 및 조치 내용 장애 리포트에 기록

---

### payment-app 특이사항

**Toss Payments 키 모드 구분**

| 키 접두사 | 모드 | 실제 결제 발생 |
|-----------|------|---------------|
| `test_sk_` | 테스트 모드 | 아니오 (가상 결제) |
| `live_sk_` | 실결제 모드 | **예** |

- staging에 `live_sk_` 가 들어가면 실결제가 발생하므로 배포 전 반드시 확인
- production에 `test_sk_` 가 들어가면 결제가 처리되지 않음 (결제 실패처럼 보임)

**배포 중 in-flight 결제 처리**

- 슬롯 전환 시점에 `READY` 상태 결제가 있을 수 있음
- Toss Payments는 orderId로 멱등성 보장 → 같은 orderId로 재시도해도 중복 결제 발생 안 함
- 단, 롤백 시에는 orderId/paymentKey를 수동으로 확인하여 정합성 검증 필요

**ddl-auto=update 주의사항**

- 컬럼 추가: 안전 (기존 데이터 유지, 새 컬럼은 null)
- 컬럼 삭제: JPA는 테이블에서 삭제하지 않지만, 엔티티에서 제거하면 해당 컬럼 접근 불가 → 구버전 앱은 해당 컬럼에 쓰기 시도 시 오류
- 타입 변경: DB 타입과 Java 타입 불일치 시 서비스 시작 실패 또는 데이터 읽기 오류
- **Flyway/Liquibase가 없으므로 스키마 변경 이력 추적이 안 됨 → 배포 전 schema diff를 수동으로 확인해야 함**

---

## Failure Scenarios

### 시나리오 1: CANCEL_FAILED 상태에서 롤백 시도

- **현상**: 결제 취소 API 호출 → Toss Payments 취소 성공 → DB 업데이트 실패 → DB에는 여전히 `APPROVED` 상태
- **위험**: 이 상태에서 롤백하면 구버전 앱이 해당 결제를 `APPROVED`로 처리할 수 있음
- **처리**: Toss Payments 콘솔에서 결제 상태 확인 → DB를 실제 상태에 맞게 수동 업데이트 → 롤백 진행

### 시나리오 2: ddl-auto=update 스키마 변경으로 서비스 시작 실패

- **현상**: 신규 버전 배포 시 JPA 엔티티와 DB 스키마 불일치로 `HibernateException` 발생
- **처리**: 즉시 롤백 (스키마는 자동 변경이 이미 됐을 수 있음 → 수동 확인 필요)

### 시나리오 3: SCG route 인덱스 변경으로 요청 오라우팅

- **현상**: `/api/v1/payments/**` 요청이 다른 서비스로 라우팅됨 → 400/500 응답
- **처리**: scg-app 설정 확인 및 재배포, 또는 SCG 설정 롤백

### 시나리오 4: 환경변수 오설정 (test/live 키 혼용)

- **현상**: staging에서 실결제 발생, 또는 production에서 결제 전부 실패
- **처리**: 즉시 롤백 → 환경변수 수정 → 재배포. staging 실결제는 Toss Payments 콘솔에서 즉시 환불 처리

---

## Observability / Detection

**메트릭 (Grafana / Prometheus)**
- `http_server_requests_seconds_count{status="5xx", application="payment-service"}` — 5xx 에러율
- `hikaricp_connections_active{application="payment-service"}` — DB 연결 풀 사용률
- `jvm_memory_used_bytes{application="payment-service"}` — JVM 메모리

**로그 (Kibana)**
- `ERROR` 레벨 로그 급증
- `TossPaymentsException`, `P005` 에러코드 (인증 실패)
- `RedisConnectionException`, `JedisConnectionException` (Redis 연결 실패)
- `HikariPool` timeout 로그

**헬스체크**
- `/actuator/health`: 전체 상태 (`UP`/`DOWN`)
- 헬스체크 항목: DB, Redis, 디스크

**직접 DB 확인 쿼리**
```sql
-- 최근 1시간 결제 상태 분포
SELECT status, COUNT(*) as cnt
FROM ticketing_payment.payments
WHERE created_at > NOW() - INTERVAL 1 HOUR
GROUP BY status;

-- CANCEL_FAILED 잔류
SELECT * FROM ticketing_payment.payments
WHERE status = 'CANCEL_FAILED';
```

---

## Recovery / Mitigation

**즉각 대응 (5분 이내)**
1. Nginx upstream을 이전 슬롯으로 전환 (CANCEL_FAILED 없음 확인 후)
2. 롤백 완료 후 `/actuator/health` 확인
3. 결제 서비스 에러 알림 채널 공지

**단기 조치 (1시간 이내)**
1. CANCEL_FAILED 상태 수동 처리 (Toss Payments 콘솔 + DB 업데이트)
2. READY 상태 잔류 건 orderId/paymentKey 정합성 수동 확인
3. 장애 원인 분석 시작

**재배포 조건**
- 원인 분석 완료
- 재발 방지 조치 적용 확인
- staging 재검증 완료

---

## Trade-offs

**Blue/Green 배포**
- 장점: 즉각 롤백 가능, 배포 중 다운타임 없음
- 단점: 두 슬롯 동시 운영으로 리소스 2배 필요, 슬롯 전환 시점 in-flight 요청 처리 복잡

**ddl-auto=update**
- 장점: 엔티티 변경만으로 스키마 자동 반영, 개발 편의성 높음
- 단점: 스키마 이력 추적 불가, 컬럼 삭제/타입 변경 위험, 운영 환경 적합성 낮음
- 개선 방향: Flyway 도입으로 마이그레이션 파일 관리 (현재 미구현)

**수동 체크리스트**
- 장점: 절차가 명시적이고 이해하기 쉬움
- 단점: 사람이 단계를 건너뛸 수 있음, 자동화 스크립트보다 실수 가능성 높음
- 개선 방향: Jenkins pipeline에 pre-deploy, post-deploy 검증 단계 자동화

---

## Interview Explanation (90s Version)

**배경**: MSA 티켓팅 시스템에서 payment-app은 외부 결제 대행사와 연동된 가장 민감한 서비스다.

**문제**: 배포 중 잘못된 환경변수(test/live 키 혼용), CANCEL_FAILED 상태 잔류, ddl-auto=update로 인한 스키마 변경 위험이 동시에 존재한다. 특히 CANCEL_FAILED는 고객 돈이 실제로 묶이는 상황이라 rollback 전에 반드시 처리가 선행돼야 한다.

**설계**: Blue/Green 배포로 이전 슬롯을 롤백 대기 상태로 유지한다. 배포 전 DB에서 CANCEL_FAILED 잔류를 쿼리로 확인하고, 환경변수는 키 접두사(test_sk_ / live_sk_)로 모드를 명시적으로 구분한다. ddl-auto=update 환경에서는 엔티티 변경 내용을 컬럼 추가/삭제/타입 변경으로 분류해 위험도를 사전 평가한다.

**트레이드오프**: 수동 체크리스트는 절차를 명시하지만 자동화보다 실수 가능성이 높다. Flyway가 없어 스키마 이력 추적이 안 된다는 점은 운영 환경에서 명확한 한계다. 현재는 이 체크리스트로 위험을 관리하고, Jenkins pipeline에 자동 검증 단계 추가를 개선 방향으로 설정했다.
