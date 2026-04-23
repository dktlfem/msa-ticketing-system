# SCG Gateway 검증 결과 보고서

**작성일:** 2026-04-08
**대상 브랜치:** `feat/SCRUM-49-scg-hardening-phase2`
**대상 커밋:** `d199349`
**테스트 환경:** 홈 스테이징 서버 (`192.168.124.100:8090`)

---

## 1. 목적

SCG 필터 체인의 보안·복원력 제어장치가 **설계대로 동작하는지** 실측 수치로 증명한다.

"설계상 그럴 것이다"가 아닌, k6 부하테스트 + Prometheus 메트릭 + Elasticsearch 로그 + Jaeger 의존성 맵으로 교차 검증한다.

---

## 2. 검증 결과 요약

### scenario12 — 보안 검증 ✅ PASS (전 항목)

| # | 검증 항목 | 판정 기준 | 결과 |
|---|---|---|---|
| 1 | auth_success: 유효 JWT → 200 OK | 성공률 100% | ✅ PASS |
| 2 | auth_expired: 만료 JWT → 401 ProblemDetail | 100% | ✅ PASS |
| 3 | auth_missing: JWT 없음 → 401 ProblemDetail | 100% | ✅ PASS |
| 4 | spoofed_header: Auth-User-Id 위조 → 401, bypass 0건 | bypass=0건, 차단=100% | ✅ PASS |
| 5 | queue_token: POST + Queue-Token 없음 → 403 | 100% | ✅ PASS |
| 6 | request_id: X-Request-Id echo | 100% | ✅ PASS |
| 7 | error_format: 모든 4xx → ProblemDetail 형식 | 100% | ✅ PASS |

### scenario13 — 복원력 검증 ⚠️ 부분 PASS

| # | 검증 항목 | 판정 기준 | 결과 |
|---|---|---|---|
| 8 | cb_fallback: waitingroom 장애 시 503 + ProblemDetail | 100% | ✅ PASS |
| 9 | normal_isolation: 장애 중 concert-app 정상 응답 유지 | 100% | ✅ PASS |
| — | timeout(504): waitingroom 응답 지연 12s → 504 + ProblemDetail | 100% | ⚠️ 미검증 |

> scenario13의 504 timeout 경로는 `tc netem delay` 명령 실행 환경 제약으로 검증하지 못했다. 503 CB fallback 및 정상 서비스 격리는 모두 PASS. 자세한 내용은 [9절 한계](#9-한계-및-주의사항) 참조.

---

## 3. 실행 정보

| 항목 | scenario12 (보안 검증) | scenario13 (복원력 검증) |
|---|---|---|
| 실행 시각 | 2026-04-08 13:07:22 KST | 2026-04-08 13:22:01 KST |
| run_id | `20260408-130752` | `20260408-132503` |
| 실행 시간 | 30초 | 181초 |
| 총 반복 수 | 1,205회 | waitingroom 901건 + concert 1,801건 |
| 재확인 run_id | `20260408-143417` (scenario12 재실행) | — |

```bash
# 재현 명령어
cd ~/projects/ci-cd-test/load-test/scripts/k6

# scenario12 (보안 검증)
./run-tests.sh 12

# scenario13 (복원력 검증) — 사전에 waitingroom 중단 필요
ssh FAMILY@192.168.124.100 "cd ~/devops_lab && docker compose stop waitingroom-app"
./run-tests.sh 13
ssh FAMILY@192.168.124.100 "cd ~/devops_lab && docker compose start waitingroom-app"
```

---

## 4. 검증 항목 상세

### 4-1. 인증 필터 (JwtAuthenticationFilter +4)

**[1] auth_success — 유효 JWT → 200 OK**

| 메트릭 | 측정값 |
|---|---|
| 성공률 | 100% (300/300건) |
| P50 응답시간 | ~18.9ms |
| P95 응답시간 | 26.5ms (재실행 기준) |

유효 JWT 요청이 필터 체인을 모두 통과하여 downstream까지 도달함을 확인.

**[2] auth_expired — 만료 JWT → 401**

| 메트릭 | 측정값 |
|---|---|
| 401 반환율 | 100% (150/150건) |
| ProblemDetail 형식 | 100% |

Prometheus 교차 검증: scenario12 시간대 `status=401` 카운트 **~600건** 관측 (`s12_401_403.json`).

> 600건 = auth_expired(150건) + auth_missing(151건) + spoofed_header(301건) × 일부 중복 집계. Prometheus는 30초 increase 쿼리이므로 절댓값 해석 시 윈도우 경계 효과를 감안해야 한다.

**[3] auth_missing — JWT 없음 → 401**

| 메트릭 | 측정값 |
|---|---|
| 401 반환율 | 100% (151/151건) |

---

### 4-2. 헤더 위조 차단 (RequestSanitizeFilter +3)

**[4] spoofed_header — Auth-User-Id / Auth-Passport 주입 → 401, bypass 0건**

| 메트릭 | 측정값 |
|---|---|
| 200 bypass | 0건 |
| 차단율 | 100% (301/301건) |

RequestSanitizeFilter(+3)가 외부에서 주입한 `Auth-User-Id`, `Auth-Passport`, `Internal-Token` 헤더를 strip한 뒤, JwtAuthenticationFilter(+4)에 도달할 때 Authorization 헤더가 없으므로 401 반환. 단 1건의 bypass도 없음.

---

### 4-3. Queue Token 검증 (QueueTokenValidationFilter +5)

**[5] queue_token — POST + Queue-Token 없음 → 403**

| 메트릭 | 측정값 |
|---|---|
| 403 차단율 | 100% (151/151건) |
| ProblemDetail 형식 | 100% |

**⚠️ 설계 사실: QueueTokenValidationFilter는 "쓰기 계열 메서드 전용" 보호 필터입니다.**

이 필터는 `POST / PUT / PATCH / DELETE` 요청에 대해서만 Queue-Token 헤더를 요구합니다. `GET` 요청은 의도적으로 통과시킵니다.

이것은 버그가 아니라 **ADR-0008에 명시된 설계 결정**입니다.

> 근거: 예약 조회(GET)는 대기열을 통과하지 않아도 된다. 대기열이 필요한 것은 예약 생성/변경/취소(쓰기 연산)뿐이다. GET 요청에도 Queue-Token을 강제하면 예약 내역 조회마저 대기열에 묶여 사용성이 심각하게 저하된다.

만약 "GET은 왜 통과하죠?"라는 질문을 받는다면: 설계 의도이며, GET에도 Queue-Token을 강제해야 한다면 `QueueTokenValidationFilter.PROTECTED_METHODS` Set에 `HttpMethod.GET`을 추가하고 ADR-0008을 개정해야 합니다.

```java
// QueueTokenValidationFilter.java
private static final Set<HttpMethod> PROTECTED_METHODS = Set.of(
    HttpMethod.POST,
    HttpMethod.PUT,
    HttpMethod.PATCH,
    HttpMethod.DELETE
    // GET은 의도적으로 제외 — 조회는 대기열 불필요 (ADR-0008)
);
```

**직접 증거 — Elasticsearch 로그 151건:**

```
[QUEUE_TOKEN] Missing header path=/api/v1/reservations method=POST clientIp=172.19.0.1
logger: com.koesc.ci_cd_test_app.filter.QueueTokenValidationFilter
level: WARN
requestId: 1440848e-852a-42a6-a76d-b3858935c0e8
traceId: c52d04815709be259035c1bd980f0429
```

`s12_queue_token_logs.json` 집계 결과 **정확히 151건**, k6 결과(151건 403)와 **1:1 일치**. 필터가 모든 요청을 정확히 차단했음을 로그로 직접 확인.

---

### 4-4. Request-Id Propagation (RequestCorrelationFilter HIGHEST_PRECEDENCE)

**[6] request_id — X-Request-Id 주입 → 응답에 동일 값 echo**

| 메트릭 | 측정값 |
|---|---|
| X-Request-Id match율 | 100% (151/151건) |

클라이언트가 `X-Request-Id: test-<run_id>-<random>` 헤더를 포함하여 요청하면, SCG가 동일한 값을 응답 헤더에 포함하여 반환함. RequestCorrelationFilter가 MDC에 requestId를 주입하여 Filebeat → Elasticsearch까지 전파되는 구조임을 위 Queue-Token 로그의 `"requestId"` 필드를 통해 간접 확인.

---

### 4-5. 에러 응답 일관성

**[7] ProblemDetail 형식 — 모든 4xx 응답이 RFC 7807 준수**

| 메트릭 | 측정값 |
|---|---|
| ProblemDetail 준수율 | 100% |
| 검증된 필드 | `status` (number), `title` (string) |

---

### 4-6. Circuit Breaker + Fallback (scenario13)

**[8] CB fallback — waitingroom 장애 시 503 + ProblemDetail**

| 메트릭 | 측정값 |
|---|---|
| 503 fallback 발생 | 901건 (k6 측정) |
| ProblemDetail 형식 | 100% (901/901건) |

**Elasticsearch 로그 건수 해석 — 901건 vs 2,707건:**

ES `s13_timeout_cb_logs.json`에서 2,707건이 집계되었으나, k6 결과는 901건이다. 차이가 나는 이유는 SCG의 필터 구조상 **요청 1건당 로그가 3개** 생성되기 때문이다.

| 로그 레이어 | 로거 | 메시지 패턴 |
|---|---|---|
| 1 | `AccessLogFilter` | `ACCESS` (요청 수신 시) |
| 2 | `FallbackController` | `[CB_FALLBACK] requestId=... path=/fallback/service-unavailable` |
| 3 | `RequestLogMaskingFilter` | `[ACCESS_DONE] GET /api/v1/waiting-room/status status=503` |

901건 × 3 = 2,703건 ≈ 2,707건 (시간 범위 경계 ±4건). Elasticsearch 조회 쿼리가 `waitingroom`, `503`, `fallback`, `CircuitBreaker` 등 OR 조건으로 넓게 잡혀 있어 세 레이어 로그가 모두 집계된 것이다. 요청 단위로는 k6의 901건이 정확한 수치이며, ES 2,707건은 **1건당 3개 로그 구조를 보여주는 보조 근거**로 해석해야 한다.

**Prometheus 교차 검증:**

`s13_cb_state.json`에서 `waitingroom-service-cb` 상태 변화 확인.

```
state=open     → 1 (8개 시계열 포인트 × 15초 간격 = 약 2분간 Open 유지)
state=half_open → 1 (1개 포인트 — 복구 시도 전환)
state=closed   → 0 (테스트 중 Closed 상태 없음)
```

Circuit Breaker가 Open 상태를 유지하다 Half-Open으로 전환되는 과정이 15초 간격 시계열로 포착됨. k6 결과(901건 503 fallback)와 Prometheus CB state 변화가 완전히 일치.

**[9] normal_isolation — 장애 중 concert-app 정상 응답**

| 메트릭 | 측정값 |
|---|---|
| concert-app 성공률 | 100% (1,801/1,801건) |
| P95 응답시간 | 40.78ms |

waitingroom 장애 중에도 concert-app의 성공률이 100%를 유지. Bulkhead + CircuitBreaker가 서비스별로 분리되어 waitingroom 장애가 concert-app으로 전파되지 않음.

---

## 5. Observability 교차 검증 요약

### 증거 위계

| 증거 강도 | 항목 | 근거 |
|---|---|---|
| **직접 증거** | Queue-Token 차단 151건 | ES `[QUEUE_TOKEN] Missing header` 로그 151건 = k6 151건과 1:1 일치 |
| **직접 증거** | CB fallback 901건 | k6 측정값 + Prometheus CB state=open 시계열 |
| **직접 증거** | CB Open→Half-Open 전환 | Prometheus `s13_cb_state.json` 포착 |
| **보조 근거** | 401/403 규모 | Prometheus `status=401` ~600건, `status=403` ~150건 (30s window 집계) |
| **보조 근거** | 서비스 간 트래픽 흐름 | Jaeger dependency: scg→concert-service 4,507건, scg→booking-service 151건 |
| **미수집** | 개별 트레이스 상세 | Jaeger API 시간 파라미터 오류로 0건 반환 — UI 직접 확인 필요 |
| **미검증** | 504 timeout 경로 | `tc netem delay` 실행 불가 — [9절](#9-한계-및-주의사항) 참조 |

### 사용한 API 호출

```bash
# [직접 증거] Elasticsearch — Queue-Token 차단 로그
# ES는 0.0.0.0:9200으로 외부 직접 접근 가능
POST http://192.168.124.100:9200/filebeat-*/_search
{
  "size": 10,
  "query": {
    "bool": {
      "must": [
        {"range": {"@timestamp": {"gte": "2026-04-08T04:06:30Z", "lte": "2026-04-08T04:08:30Z"}}},
        {"match_phrase": {"message": "QUEUE_TOKEN"}}
      ]
    }
  }
}

# [직접 증거] Prometheus — CB 상태 시계열
# Prometheus는 docker exec 경유 필요 (외부 포트 미노출, route-prefix=/prometheus)
docker exec prometheus wget -qO- \
  "http://localhost:9090/prometheus/api/v1/query_range?query=resilience4j_circuitbreaker_state{application=\"scg-service\"}&start=2026-04-08T04:21:00Z&end=2026-04-08T04:26:00Z&step=15s"

# [보조 근거] Jaeger — 서비스 의존성 맵
# Jaeger는 nginx proxy 경유 (oauth2 인증 필요 시 브라우저 사용)
GET http://192.168.124.100:8080/jaeger/api/dependencies?endTs=<unix_ms>&lookback=3600000
```

---

## 6. 수집 증거 파일 목록

```
results/2026-04-08/
├── json/
│   ├── scenario12-gateway-security-verification_20260408-130752.json
│   ├── scenario12-gateway-security-verification_20260408-143417_raw-summary.json  # 재실행
│   └── scenario13-gateway-resilience_20260408-132503.json
├── observability/
│   ├── elasticsearch/
│   │   ├── s12_queue_token_logs.json     ← 직접 증거: 151건 Queue-Token 차단 로그
│   │   └── s13_timeout_cb_logs.json      ← 보조: 2,707건 (요청 1건당 3개 로그 구조)
│   ├── prometheus/obs_prom/
│   │   ├── s12_status.json               ← HTTP status 분포 (scenario12)
│   │   ├── s12_401_403.json              ← 401/403 카운트
│   │   ├── s13_status.json               ← HTTP status 분포 (scenario13)
│   │   └── s13_cb_state.json             ← CB 상태 변화 시계열 (직접 증거)
│   └── jaeger/
│       └── service_dependencies.json     ← 보조: 서비스 호출 의존성 맵
└── summary_2026-04-08.xlsx
```

---

## 7. 수동 캡처 권장 화면 (우선순위 순)

| 우선순위 | 도구 | 화면 | 캡처 포인트 |
|---|---|---|---|
| ① | **Grafana** | SCG 대시보드 → HTTP Status Distribution | 13:07~13:08 구간 401/403 spike + 13:22~13:25 구간 503 spike가 한 화면에 보이도록 |
| ② | **Grafana** | Circuit Breaker State 패널 | `waitingroom-service-cb`: Closed → Open → Half-Open 전환 곡선 |
| ③ | **Jaeger** | scg-app → 503 에러 트레이스 상세 | `/fallback/service-unavailable` 스팬 + `error=true` 태그 |
| ④ | **Kibana** | Discover → `message: *QUEUE_TOKEN*` | 151건 로그 목록 + requestId 필드 |
| ⑤ | **AlertManager** | Alerts 목록 | waitingroom-app DOWN 알림 (scenario13 시간대) |

---

## 8. 개선 과정에서 발견된 사실 (기술적 기록)

| 항목 | 발견 내용 | 조치 |
|---|---|---|
| k6 스크립트 오류 | queue_token 시나리오가 GET으로 요청 → 필터를 정상 우회 → 0% 결과. 필터는 처음부터 정상이었음 | POST로 수정 |
| scenario13 threshold 로직 오류 | timeout 데이터 0건일 때 `rate>=1.0` threshold가 false로 판정 → FAIL | "데이터가 있을 때만 검사" 로직으로 수정 |
| Prometheus 레이블 불일치 | 수집 스크립트에서 `application="scg-app"` 사용 → 실제는 `"scg-service"` | 레이블 수정 후 재수집 |
| Prometheus 접근 방법 | 외부 포트 미노출 + route-prefix=/prometheus 설정 → 직접 curl 불가 | `docker exec prometheus wget` 경유로 해결 |

---

## 9. 한계 및 주의사항

**[1] scenario13 timeout(504) 경로 미검증**

`tc netem delay 12000ms`로 waitingroom-app에 지연을 주입하면 SCG global timeout(10s) 초과 → 504 반환이 예상된다. 그러나 Docker 컨테이너에 `NET_ADMIN` capability가 없어 `tc` 명령 실행이 불가능했다. 따라서 504 경로는 코드 리뷰(`GlobalErrorHandler.java`의 GatewayTimeoutException 처리)로 대체하며, 실 검증은 `NET_ADMIN` capability 추가 후 재시도 필요.

**[2] Jaeger 개별 트레이스 미수집**

Jaeger API의 시간 파라미터(마이크로초) 처리 과정에서 0건 반환. 서비스 의존성 맵(`/api/dependencies`)은 수집 성공했으나 개별 트레이스 상세(span별 duration, error tag, baggage)는 미확보. Jaeger UI에서 직접 확인 필요.

**[3] P95 latency는 판정 기준에서 제외**

스테이징 서버의 하드웨어 편차(RAM 128GB WSL2 환경)가 크므로, 응답 시간은 baseline 측정값으로만 기록. 프로덕션 SLA 설정 시 별도 사양 기반 기준이 필요.

**[4] Grafana/AlertManager API 수집 실패**

oauth2-proxy가 모든 외부 요청에 인증을 요구하며 내부 포트가 호스트에 미노출. Chrome 스크린샷으로 대체 예정(7절 참조).
