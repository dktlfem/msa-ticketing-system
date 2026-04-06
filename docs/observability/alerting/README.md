# Prometheus Recording Rules & AlertManager 알림 규칙

> **SCRUM-59** | 최종 수정: 2026-04-06
> **관련 문서**: [SLI/SLO 정의](../../performance/sli-slo.md) · [Incident Runbook](../../operations/incident-runbook.md) · [Golden Signals 대시보드](../grafana-dashboards/README.md)

---

## 파일 구조

```
infra/
├── prometheus/
│   └── rules/
│       ├── recording-rules.yml   # SLI 사전 계산 (4개 그룹, 16개 규칙)
│       └── alert-rules.yml       # 알림 규칙 (7개 그룹, 15개 규칙)
└── alertmanager/
    └── alertmanager.yml          # 라우팅/수신자 설정
```

---

## Recording Rules 요약

| 그룹 | 규칙 수 | 용도 |
|------|---------|------|
| `sli:availability` | 3 | 서비스별 가용성, 에러율, RPS |
| `sli:latency` | 7 | 서비스별 P95/P99, 핵심 엔드포인트별 P95 |
| `sli:burn_rate` | 3 | payment/booking SLO burn rate (5m/1h window) |
| `sli:infrastructure` | 4 | HikariCP 사용률, JVM 힙, GC pause |

Recording rule 메트릭명은 `sli:` 접두사로 통일. Grafana에서 `sli:http_availability:ratio_rate5m{application="payment-service"}`로 직접 조회 가능.

---

## Alert Rules 요약

| 그룹 | 알림명 | Severity | 임계치 | Runbook |
|------|--------|----------|--------|---------|
| payment | PaymentHighErrorRate | P1 | 5xx > 3% (2m) | INC-001 |
| payment | PaymentSLOBurnRateFast | P1 | burn rate > 14.4x | — |
| payment | PaymentConfirmLatencyHigh | P1 | P95 > 5s (3m) | INC-007 |
| booking | BookingLatencyHigh | P2 | P95 > 2s (5m) | INC-005 |
| booking | BookingSLOBurnRateFast | P1 | burn rate > 14.4x | — |
| waitingroom | WaitingroomHighErrorRate | P1 | 5xx > 1% (2m) | INC-004 |
| waitingroom | WaitingroomLatencyHigh | P2 | P95 > 200ms (5m) | — |
| concert | ConcertEventsLatencyHigh | P2 | P95 > 500ms (5m) | INC-008 |
| concert | ConcertSeatHoldServerError | P1 | 5xx > 5% (3m) | INC-008 |
| user | UserHighErrorRate | P2 | 5xx > 2% (5m) | INC-009 |
| scg | CircuitBreakerOpen | P1 | state=open (즉시) | INC-006 |
| scg | SCGHighErrorRate | P2 | 5xx > 5% (3m) | — |
| infra | HikariCPPoolExhaustion | P1 | 사용률 > 80% (3m) | INC-005 |
| infra | HikariCPPendingConnections | P2 | pending > 0 (3m) | — |
| infra | JVMHeapHigh | P2 | 힙 > 85% (5m) | — |
| infra | JVMGCPressure | P2 | GC > 5% (5m) | — |
| infra | ServiceDown | P1 | up == 0 (1m) | — |

---

## 배포 방법

### 사전 요구사항

- OpenVPN 연결 (`192.168.124.100` 접근 가능)
- Prometheus, AlertManager Docker 컨테이너 실행 중
- Prometheus 설정 파일 위치 확인 (보통 `/etc/prometheus/prometheus.yml`)

### Step 1: Recording Rules + Alert Rules 배포

```bash
# 스테이징 서버 접속
ssh dktlfem@192.168.124.100

# rules 파일 복사 (Git repo에서 직접 또는 scp)
# 옵션 A: Git pull (서버에 repo clone이 있는 경우)
cd ~/ci-cd-test && git pull origin main
sudo cp infra/prometheus/rules/*.yml /etc/prometheus/rules/

# 옵션 B: 로컬에서 scp
# scp infra/prometheus/rules/*.yml dktlfem@192.168.124.100:/tmp/
# ssh dktlfem@192.168.124.100 'sudo cp /tmp/*-rules.yml /etc/prometheus/rules/'
```

### Step 2: prometheus.yml에 rules 경로 추가

현재 Prometheus 설정 파일에 `rule_files` 섹션이 없다면 추가:

```yaml
# prometheus.yml에 추가
rule_files:
  - "/etc/prometheus/rules/recording-rules.yml"
  - "/etc/prometheus/rules/alert-rules.yml"

# AlertManager 연동 (이미 설정돼 있을 수 있음)
alerting:
  alertmanagers:
    - static_configs:
        - targets:
            - "alertmanager:9093"
```

### Step 3: AlertManager 설정 배포

```bash
sudo cp infra/alertmanager/alertmanager.yml /etc/alertmanager/alertmanager.yml
```

### Step 4: 설정 리로드

```bash
# Prometheus 설정 리로드 (재시작 없이)
curl -X POST http://localhost:9090/-/reload

# 또는 Docker 환경에서
docker exec prometheus kill -SIGHUP 1

# AlertManager 설정 리로드
curl -X POST http://localhost:9093/-/reload
```

### Step 5: 검증

```bash
# Recording rules 로드 확인
curl -s http://localhost:9090/api/v1/rules | python3 -m json.tool | grep "sli:"

# Alert rules 로드 확인
curl -s http://localhost:9090/api/v1/rules?type=alert | python3 -m json.tool | grep "alertname"

# AlertManager 상태 확인
curl -s http://localhost:9093/api/v2/status | python3 -m json.tool | head -20
```

Prometheus UI에서도 확인 가능: `http://192.168.124.100:8080/prometheus/rules`

---

## 알림 라우팅 구조

```
Prometheus Alert 발화
  → AlertManager 수신
  → route 매칭 (severity 기반)
  │
  ├── P0 (critical-webhook)
  │   group_wait: 0s, repeat: 5m
  │   → 즉시 알림 (CANCEL_FAILED 등)
  │
  ├── P1 (warning-webhook)
  │   group_wait: 15s, repeat: 1h
  │   → PaymentHighErrorRate, CircuitBreakerOpen 등
  │
  └── P2 (default-webhook)
      group_wait: 3m, repeat: 4h
      → BookingLatencyHigh, JVMHeapHigh 등
```

### Inhibition (알림 억제)

1. `ServiceDown` 발생 시 → 해당 instance의 모든 알림 억제
2. P1 발생 시 → 동일 service의 P2 알림 억제

---

## 수신자(Receiver) 설정 가이드

현재 webhook_configs는 플레이스홀더. 실제 연동 시 아래 참고:

### Slack 연동

```yaml
# alertmanager.yml receivers 섹션에서 주석 해제 후 수정
slack_configs:
  - api_url: '${SLACK_WEBHOOK_URL}'    # Docker secret 또는 env로 주입
    channel: '#ticketing-alerts'
    title: '[{{ .GroupLabels.severity }}] {{ .GroupLabels.alertname }}'
    text: '{{ range .Alerts }}{{ .Annotations.summary }}\n{{ end }}'
    send_resolved: true
```

### Discord 연동

```yaml
webhook_configs:
  - url: 'https://discord.com/api/webhooks/{id}/{token}'
    send_resolved: true
```

---

## Grafana 연동

Recording rule 메트릭을 Golden Signals 대시보드에서 활용할 수 있습니다:

| 기존 패널 PromQL | Recording Rule 대체 |
|-----------------|-------------------|
| `sum by(application)(rate(http_server_requests_seconds_count[5m]))` | `sli:http_requests:rate5m` |
| `sum by(application)(rate(...{status=~"5.."}[5m])) / sum by(application)(rate(...[5m]))` | `sli:http_error_rate:ratio_rate5m` |
| `histogram_quantile(0.95, sum by(application,le)(rate(..._bucket[5m])))` | `sli:http_latency_p95:seconds` |

Recording rule 사용 시 Grafana 쿼리 응답 시간이 단축되고, Dashboard와 Alert에서 동일한 계산 결과를 보장합니다.

---

## 알려진 제한사항

1. **CANCEL_FAILED 알림 미구현**: `payment_confirm_total{result="cancel_failed"}` 커스텀 메트릭이 아직 없어 P0 알림을 Prometheus에서 직접 발화할 수 없음. 현재는 Kibana 로그 기반 감지에 의존. Micrometer counter 추가 후 P0 alert rule 추가 예정.

2. **user-service/scg-service histogram bucket 미노출**: `http_server_requests_seconds_bucket`이 누락된 서비스에서는 latency recording rule이 `NaN` 반환. `management.metrics.distribution.percentiles-histogram.http.server.requests=true` 설정 추가 필요.

3. **webhook receiver 플레이스홀더**: 실제 알림 수신을 위해 Slack/Discord/Email webhook URL 설정 필요. 보안상 환경변수로 주입.

4. **Kafka consumer lag 모니터링 미포함**: Kafka가 아직 미구현이므로 관련 알림 규칙 없음. Kafka 도입 시 `kafka_consumer_fetch_manager_records_lag_max` 기반 알림 추가 예정.

---

## 향후 작업

- [ ] CANCEL_FAILED P0 알림 (커스텀 메트릭 추가 후)
- [ ] Kafka consumer lag 알림 (Kafka 도입 후)
- [ ] SLO Grafana 대시보드 (Error Budget 잔여량 시각화)
- [ ] Recording rule 기반 Golden Signals 대시보드 최적화
- [ ] Slack/Discord 실제 연동
