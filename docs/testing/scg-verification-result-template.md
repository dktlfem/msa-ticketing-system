# SCG Gateway 검증 결과 — {시나리오 번호}: {시나리오 이름}

> 실행일: {YYYY-MM-DD}
> run_id: {RUN_TAG}
> 실행자: {이름}
> 브랜치: feat/SCRUM-49-scg-hardening-phase2

---

## 1. 목적

{이 시나리오가 무엇을 증명하려는지 1~2줄로 기술}

예시 (scenario12):
> SCG 필터 체인의 보안 제어장치(인증, 헤더 위변조 차단, Queue-Token 검증, Request-Id 전파, 에러 응답 형식)가
> 100% 일관성 기준으로 동작하는지 정량적으로 검증한다.

예시 (scenario13):
> waitingroom-service 지연/장애 시 SCG가 504/503 ProblemDetail을 100% 일관되게 반환하고,
> 정상 서비스(concert-app)가 격리되어 영향받지 않음을 증명한다.

---

## 2. 사전조건

### 공통
- [ ] VPN 연결 확인 (`ping 192.168.124.100`)
- [ ] scg-app 실행 중 (`curl -s http://192.168.124.100:8090/actuator/health`)
- [ ] k6 설치 확인 (`k6 version`)
- [ ] results 디렉토리 생성 (`mkdir -p results/YYYY-MM-DD/{html,json,csv,logs}`)

### scenario12 전용
- [ ] concert-app 실행 중 (인증 성공 테스트 대상)
- [ ] booking-app 실행 중 (Queue-Token 차단 테스트 대상)
- [ ] `QUEUE_TOKEN_VALIDATION_ENABLED=true` 설정 확인
- [ ] JWT_SECRET 값 확인 (scg-app 설정과 k6 --env 일치)

### scenario13 전용 (택 1)
- [ ] **[A] timeout 유발**: `docker compose exec waitingroom-app tc qdisc add dev eth0 root netem delay 12000ms`
- [ ] **[B] CB 유발**: `docker compose stop waitingroom-app`
- [ ] 장애 주입 후 확인: `curl -s http://192.168.124.100:8090/api/v1/waiting-room/status` → 504 또는 502

---

## 3. 실행 명령

```bash
# scenario12 단독 실행
cd load-test/scripts/k6
k6 run \
  --env SCG_BASE_URL=http://192.168.124.100:8090 \
  --env JWT_SECRET=<실제 시크릿> \
  --env RESULT_DIR=results/YYYY-MM-DD \
  --out json=results/YYYY-MM-DD/json/scenario12_raw.json.gz \
  scenarios/scenario12-gateway-security-verification.js \
  2>&1 | tee results/YYYY-MM-DD/logs/scenario12.log

# scenario13 단독 실행
k6 run \
  --env SCG_BASE_URL=http://192.168.124.100:8090 \
  --env JWT_SECRET=<실제 시크릿> \
  --env RESILIENCE_MODE=timeout \
  --env RESULT_DIR=results/YYYY-MM-DD \
  --out json=results/YYYY-MM-DD/json/scenario13_raw.json.gz \
  scenarios/scenario13-gateway-resilience.js \
  2>&1 | tee results/YYYY-MM-DD/logs/scenario13.log

# 또는 run-tests.sh 활용
./run-tests.sh security     # 12 + 13 순차 실행
./run-tests.sh 12           # 12만
./run-tests.sh 13           # 13만
```

---

## 4. 기대 결과

### scenario12 — 100% 일관성 기준

| # | 검증 항목 | 기대 응답 | 판정 기준 |
|---|----------|----------|----------|
| 1 | 인증 성공 응답시간 | 200 OK | P50/P95 baseline 측정값 기록 |
| 2 | 만료 JWT | 401 + ProblemDetail | = 100% |
| 3 | JWT 없음 | 401 + ProblemDetail | = 100% |
| 4 | 위조 헤더 (Auth-User-Id + Auth-Passport) | 401 (헤더 strip 후 JWT 검증 실패) | bypass = 0, 차단 = 100% |
| 5 | Queue-Token 없음 | 403 + ProblemDetail | = 100% |
| 6 | X-Request-Id propagation | 응답 헤더에 동일 값 | = 100% |
| 공통 | 에러 응답 형식 | RFC 7807 ProblemDetail (status + title) | = 100% |

### scenario13 — 100% 일관성 기준

| # | 검증 항목 | 기대 응답 | 판정 기준 |
|---|----------|----------|----------|
| 1 | Timeout (504) | 504 + ProblemDetail | = 100% |
| 2 | CB Fallback (503) | 503 + ProblemDetail | = 100% |
| 3 | 정상 서비스 격리 | concert-app 200/404 | = 100% |

---

## 5. 실제 결과

> 아래는 테스트 실행 후 채워넣는 영역입니다.

### scenario12 실행 결과

| # | 검증 항목 | 측정값 | 판정 |
|---|----------|-------|------|
| 1 | 인증 성공 P50/P95 | {  }ms / {  }ms | BASELINE |
| 2 | 만료 JWT → 401 | {  }% | {PASS/FAIL} |
| 3 | JWT 없음 → 401 | {  }% | {PASS/FAIL} |
| 4 | 위조 헤더 bypass | {  }건 / 차단 {  }% | {PASS/FAIL} |
| 5 | Queue-Token → 403 | {  }% | {PASS/FAIL} |
| 6 | X-Request-Id 매칭 | {  }% | {PASS/FAIL} |
| 공통 | ProblemDetail 형식 | {  }% | {PASS/FAIL} |

### scenario13 실행 결과

| # | 검증 항목 | 측정값 | 판정 |
|---|----------|-------|------|
| 1 | Timeout(504) 발생 | {  }건 / PD: {  }% | {PASS/FAIL/NO_DATA} |
| 2 | CB Fallback(503) | {  }건 / PD: {  }% | {PASS/FAIL/NO_DATA} |
| 3 | 정상 서비스 격리 | {  }% / P95: {  }ms | {PASS/FAIL} |

---

## 6. 관측 포인트 (Observability 증거 수집)

### 6.1 Jaeger — 분산 트레이싱

**캡처 대상:**
- [ ] `http://192.168.124.100:8080/jaeger/search?service=scg-app`
- [ ] 인증 성공 트레이스 1건: scg-app → concert-app 스팬 전파 확인 (스크린샷)
- [ ] 인증 실패 트레이스 1건: scg-app 스팬에서 401 반환 지점 확인 (스크린샷)
- [ ] (scenario13) timeout 트레이스: scg-app → waitingroom-app 스팬에서 timeout error 태그 확인 (스크린샷)

**검색 방법:**
1. Service: `scg-app` 선택
2. Tags: `requestId={X-Request-Id 값}` (k6 콘솔 로그에서 확인)
3. Operation: `GET /api/v1/events` 또는 `GET /api/v1/waiting-room/status`
4. Duration: `> 10s` (timeout 트레이스 필터)

**저장:**
`results/YYYY-MM-DD/screenshots/jaeger-{검증항목}-{run_id}.png`

### 6.2 Kibana — 로그 분석

**캡처 대상:**
- [ ] Discover → index: `filebeat-*` → 테스트 시간대 필터링 (스크린샷)
- [ ] `[SANITIZE] Stripped` 로그 존재 확인 — RequestSanitizeFilter 위조 헤더 제거 증거 (스크린샷)
- [ ] `ERROR` 레벨 로그 집계 — 테스트 중 예상치 못한 에러 여부 (스크린샷)
- [ ] (scenario13) `GatewayTimeout` 또는 `CircuitBreaker` 포함 로그 (스크린샷)

**KQL 쿼리:**
```
# 위조 헤더 strip 증거
message: "[SANITIZE]" AND message: "Stripped"

# timeout/CB 증거
log.level: ERROR AND (message: *timeout* OR message: *CircuitBreaker* OR message: *fallback*)

# 특정 요청 추적
requestId: "{X-Request-Id 값}"
```

**저장:**
`results/YYYY-MM-DD/screenshots/kibana-{검증항목}-{run_id}.png`

### 6.3 Elasticsearch — 정량 집계

**실행할 API 쿼리:**
```bash
# SANITIZE 로그 카운트 (spoofed header 차단 증거)
curl -s "http://192.168.124.100:9200/filebeat-*/_count" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"bool":{"must":[{"match_phrase":{"message":"[SANITIZE] Stripped"}},{"range":{"@timestamp":{"gte":"now-10m"}}}]}}}' | jq .

# ERROR 레벨 로그 카운트
curl -s "http://192.168.124.100:9200/filebeat-*/_count" \
  -H 'Content-Type: application/json' \
  -d '{"query":{"bool":{"must":[{"match":{"log.level":"ERROR"}},{"range":{"@timestamp":{"gte":"now-10m"}}}]}}}' | jq .
```

**저장:** 쿼리 결과를 `results/YYYY-MM-DD/elasticsearch-evidence-{run_id}.json`에 저장

### 6.4 Grafana — 대시보드 스냅샷

**캡처 대상:**
- [ ] SCG 대시보드 전체 (테스트 시간대 선택) → HTTP Status Distribution 패널 (스크린샷)
  - 401/403/504/503 spike가 테스트 시간대에 일치하는지 확인
- [ ] Request Rate 패널 — 시나리오별 트래픽 패턴 확인 (스크린샷)
- [ ] JVM Heap Memory 패널 — 메모리 누수 없음 확인 (스크린샷)
- [ ] (scenario13) Circuit Breaker State 패널 — Closed → Open → Half-Open 전환 (스크린샷)

**Grafana 스냅샷 API:**
```bash
# 대시보드 스냅샷 저장
curl -s "http://192.168.124.100:8080/grafana/api/dashboards/uid/{대시보드UID}" \
  -H "Authorization: Bearer {grafana-api-key}" | jq . \
  > results/YYYY-MM-DD/grafana-dashboard-snapshot-{run_id}.json
```

**저장:**
`results/YYYY-MM-DD/screenshots/grafana-{패널명}-{run_id}.png`

### 6.5 Filebeat — 수집 상태 확인

- [ ] Filebeat가 각 마이크로서비스 로그를 누락 없이 수집 중인지 확인
```bash
ssh 192.168.124.100 "docker compose logs filebeat --tail=20"
```
- [ ] 수집 지연 없음 확인: Kibana Discover에서 최신 로그 timestamp 확인

### 6.6 AlertManager — 알림 이력

- [ ] `http://192.168.124.100:8080/alertmanager/#/alerts`
- [ ] (scenario13) WaitingroomServiceDown 알림 발생 여부 확인 (스크린샷)
```bash
curl -s "http://192.168.124.100:8080/alertmanager/api/v2/alerts" | jq '.[].labels.alertname'
```

---

## 7. 해석

> 결과를 1~3줄로 해석합니다.

예시:
> 모든 보안 제어장치가 100% 일관성 기준을 통과했다.
> 특히 spoofed header bypass가 0건으로, RequestSanitizeFilter(+3)가 Auth-User-Id/Auth-Passport 헤더를
> JwtAuthenticationFilter(+4) 이전에 정확히 제거함을 실측으로 증명했다.

---

## 8. 한계

- k6 클라이언트 → SCG 간 네트워크 지연이 응답시간에 포함되어 있음 (순수 필터 체인 오버헤드보다 큼)
- Queue-Token 검증은 `gateway.queue-token.enabled=true` 설정에 의존. false일 때는 403이 아닌 다른 응답 가능
- scenario13 timeout 테스트는 `tc netem` 또는 서비스 중단으로 유발하므로, 실제 느린 응답(예: DB 락)과는 시뮬레이션 차이 존재
- ProblemDetail 형식 검증은 `status`(number) + `title`(string) 최소 조건만 확인. RFC 7807 전체 스펙(type, instance) 미확인

---

## 산출물 아카이브

```
results/YYYY-MM-DD/
├── html/
│   ├── scenario12-gateway-security-verification_{RUN_TAG}.html
│   └── scenario13-gateway-resilience_{RUN_TAG}.html
├── json/
│   ├── scenario12-gateway-security-verification_{RUN_TAG}.json           # 분석 결과
│   ├── scenario12-gateway-security-verification_{RUN_TAG}_raw-summary.json  # k6 전체 메트릭
│   ├── scenario12_raw.json.gz                                            # k6 raw output
│   ├── scenario13-gateway-resilience_{RUN_TAG}.json
│   ├── scenario13-gateway-resilience_{RUN_TAG}_raw-summary.json
│   └── scenario13_raw.json.gz
├── csv/
│   ├── scenario12-gateway-security-verification_{RUN_TAG}.csv
│   └── scenario13-gateway-resilience_{RUN_TAG}.csv
├── logs/
│   ├── scenario12.log                                                    # k6 실행 로그
│   └── scenario13.log
├── screenshots/
│   ├── jaeger-auth-success-{RUN_TAG}.png
│   ├── jaeger-timeout-{RUN_TAG}.png
│   ├── kibana-sanitize-stripped-{RUN_TAG}.png
│   ├── kibana-error-log-{RUN_TAG}.png
│   ├── grafana-http-status-{RUN_TAG}.png
│   ├── grafana-cb-state-{RUN_TAG}.png
│   └── alertmanager-{RUN_TAG}.png
└── elasticsearch-evidence-{RUN_TAG}.json
```

---

## 재실행 명령 (복구 포함)

```bash
# scenario13 복구 절차
ssh 192.168.124.100 "docker compose exec waitingroom-app tc qdisc del dev eth0 root 2>/dev/null; echo done"
ssh 192.168.124.100 "cd ~/devops_lab && docker compose start waitingroom-app"
ssh 192.168.124.100 "docker compose ps waitingroom-app"

# CB 상태 확인 (Closed 복귀 대기)
curl -s http://192.168.124.100:8090/actuator/circuitbreakers | jq '.circuitBreakers["waitingroom-service-cb"].state'

# 전체 서비스 상태 확인
ssh 192.168.124.100 "docker compose ps --format 'table {{.Name}}\t{{.Status}}'"
```
