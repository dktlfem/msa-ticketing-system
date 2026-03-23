# SCG Hardening - k6 부하테스트

> **브랜치**: `feat/SCRUM-45-scg-hardening-phase1`
> **모듈**: `scg-app`
> **목적**: SCG 필터 레이어(Rate Limiter / Circuit Breaker / Bulkhead)의 동작 수치 정량화

---

## 사전 준비

### 1. 스테이징 서버 스택 기동
```bash
# 스테이징 서버 SSH 접속 (OpenVPN 연결 후)
ssh dktlfem_home

# docker-compose 전체 스택 기동
docker compose up -d

# 상태 확인
docker ps
docker ps -a
docker ps | grep '서비스명'
```

### 2. SCG 포트 확인
```bash
# scg-app 포트 확인 (기본값: 8090)
docker compose port scg-app 8090
```

### 3. 결과 디렉토리 생성
```bash
mkdir -p results/$(date +%Y-%m-%d)
```

---

## 시나리오 실행 명령어

### 공통 환경변수
| 변수 | 기본값 | 설명 |
|------|--------|------|
| `SCG_BASE_URL` | `http://192.168.124.100:8090` | SCG 게이트웨이 주소 |
| `PAYMENT_PATH` | `/api/v1/payments/health` | payment-service 라우트 경로 |
| `VERBOSE` | `false` | 상세 로그 출력 여부 |

---

### 시나리오 1: 정상 부하 P99 레이턴시

**목적**: SCG 필터 오버헤드 포함 기준선 확보

```bash
k6 run \
  --env SCG_BASE_URL=http://192.168.124.100:8090 \
  --env PAYMENT_PATH=/api/v1/payments/health \
  --out csv=results/$(date +%Y-%m-%d)/01-normal-load-raw.csv \
  01-normal-load-p99.js
```

**합격 기준**
- `http_req_duration p(99)` < 500ms ✅
- `http_req_failed rate` < 1% ✅
- `scg_success_rate` > 99% ✅

**결과 해석**
```
# 테스트 종료 후 핵심 수치 확인
http_req_duration............: avg=XXms   p(95)=XXXms  p(99)=XXXms  ← 이 수치가 기준선
http_reqs....................: X/s        ← TPS
scg_payment_duration.........  p(99)=XXXms ← 커스텀 메트릭 (동일해야 함)
```

---

### 시나리오 2: Rate Limiter 트리거

**목적**: 429 차단율, Token Bucket 동작, X-RateLimit-Remaining 헤더 검증

```bash
k6 run \
  --env SCG_BASE_URL=http://192.168.124.100:8090 \
  --env PAYMENT_PATH=/api/v1/payments/health \
  --env VERBOSE=true \
  --out csv=results/$(date +%Y-%m-%d)/02-rate-limiter-raw.csv \
  02-rate-limiter-trigger.js
```

**합격 기준**
- `rate_limited_rate` > 30% ✅ (Rate Limiter 동작 확인)
- `allowed_req_duration p(95)` < 300ms ✅
- `unexpected_status_count` < 5 ✅

**결과 해석**
```
rate_limited_rate............: X%         ← 30% 이상이면 RL 정상 작동
allowed_count................: N 건       ← ~5 req/s × 30s = ~150건 예상
rate_limited_count...........: N 건       ← 나머지 전부 차단
allowed_req_duration.........: p(95)=XXms ← 허용 요청 레이턴시

# 콘솔에서 확인할 항목
[429 RATE LIMITED] X-RateLimit-Remaining: 0  ← 토큰 소진 확인
[ALLOWED] X-RateLimit-Remaining: N           ← 남은 토큰 수
```

**면접 어필 포인트**
> "10 VU 무제한 속도 요청 시 허용 TPS가 ~5 req/s에 수렴했습니다.
> Token Bucket 알고리즘이 정확히 동작하고 있으며,
> 429 응답 시간이 100ms 이내로 SCG 필터 레이어에서 upstream 호출 없이 빠르게 차단됩니다."

---

### 시나리오 2: CircuitBreaker + Fallback 검증

**목적**: CLOSED→OPEN 전이, fallback 503 응답, HALF_OPEN→CLOSED 자동 복구 검증

**⚠️ 터미널 2개 필요**

```bash
# 터미널 1: 테스트 시작
k6 run \
  --env SCG_BASE_URL=http://192.168.124.100:8090 \
  scenario2-circuit-breaker.js

# 터미널 2: 타이밍에 맞춰 실행
sleep 15 && docker compose stop payment-app && \
sleep 60 && docker compose start payment-app
```

**CB 설정 (payment-service-cb)**
| 항목 | 값 |
|------|-----|
| slidingWindowSize | 10 (COUNT_BASED) |
| failureRateThreshold | 50% |
| waitDurationInOpenState | **30s** (payment 전용, 기본 10s) |
| permittedNumberOfCallsInHalfOpenState | 3 |
| statusCodes | 500, 502, 503, 504 |
| Retry | 3회 GET/HEAD, backoff 50→500ms |

**타임라인**
```
0s ──────── 15s ─────────────────────────────── 75s ──────────── 105s
│ Phase1    │ Phase2 upstream 장애 → CB OPEN     │ Phase3 복구    │
│ CLOSED    │ ERROR → FALLBACK(503)              │ HALF_OPEN?     │
│ 2VU/500ms │ 3VU/400ms (rate-limit 10/s 미만)   │ 2VU/500ms      │
```

**합격 기준**
- `cb_fallback_rate{phase:baseline}` < 1% ✅ (baseline에서 fallback 없음)
- `cb_fallback_rate{phase:fault}` > 50% ✅ (CB OPEN → fallback 발생)
- `cb_fallback_duration p(95)` < 100ms ✅ (fallback 즉시 반환)
- `cb_unexpected_count` < 5 ✅ (429 등 간섭 없음)

**결과 해석**
```
cb_closed_count..............: N 건       ← CB CLOSED 정상 통과 (200/404)
cb_error_count...............: N 건       ← upstream 에러 (CB 실패 카운트)
cb_fallback_count............: N 건       ← CB OPEN fallback (503 ProblemDetail)
cb_half_open_count...........: N 건       ← HALF_OPEN 복구 성공 추정
cb_fallback_rate.............: X%         ← fault 단계 50% 이상이면 정상

# 콘솔에서 실시간 확인
🟡 [UPSTREAM ERROR] 첫 upstream 에러
🔴 [CB OPEN] 첫 fallback 감지
🟢 [CB RECOVERY] HALF_OPEN/CLOSED 복구 감지
```

**산출물**: `results/scenario2-circuit-breaker.{html,json,csv}`

**면접 어필 포인트**
> "slidingWindowSize=10, failureRate 50%로 설정해 ~5건 실패 시 CB OPEN 전이합니다.
> OPEN 상태에서 fallback P95가 50ms 이내로 upstream 호출 없이 즉각 503을 반환합니다.
> payment-service-cb의 waitDurationInOpenState=30s는 TossPayments PG 장애 복구 시간을
> 고려한 설정이며, 이를 통해 결제 장애가 booking/concert로 전파되는 것을 차단합니다."

---

## 전체 순서 실행 스크립트

```bash
#!/bin/bash
DATE=$(date +%Y-%m-%d)
BASE_URL="http://192.168.124.100:8090"
RESULTS_DIR="results/${DATE}"
mkdir -p "${RESULTS_DIR}"

echo "====== [1/2] Rate Limiter 트리거 ======"
k6 run \
  --env SCG_BASE_URL="${BASE_URL}" \
  scenario1-rate-limiter.js

echo "====== [2/2] Circuit Breaker 동작 ======"
echo "→ 터미널 2에서 실행:"
echo "   sleep 15 && docker compose stop payment-app && sleep 60 && docker compose start payment-app"
k6 run \
  --env SCG_BASE_URL="${BASE_URL}" \
  scenario2-circuit-breaker.js

echo "====== 테스트 완료. 결과: results/ ======"
```

---

## 결과 수치 기록 양식

| 시나리오 | 날짜 | VU | P95 (ms) | P99 (ms) | TPS | 에러율 | 핵심 수치 |
|---------|------|-----|----------|----------|-----|--------|---------|
| 정상 부하 | - | 50 | - | - | - | - | 기준선 P99 |
| Rate Limiter | - | 10 | - | - | - | - | 429 차단율, 허용 TPS |
| Circuit Breaker | - | 10 | - | - | - | - | fallback 비율, OPEN 전이 건수 |
