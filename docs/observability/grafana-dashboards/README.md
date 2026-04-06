---
title: "Grafana 대시보드 카탈로그"
last_updated: 2026-04-06
---

# Grafana 대시보드 카탈로그

이 디렉토리는 Grafana 대시보드 JSON을 git으로 버전 관리합니다.
Grafana UI에서 직접 수정한 뒤 **Export → Save to file**로 이 폴더에 저장해주세요.

---

## 대시보드 목록

| 파일 | 제목 | 설명 |
|------|------|------|
| `golden-signals.json` | MSA Ticketing — Golden Signals | 서비스별 RPS/Error Rate/Latency/JVM/HikariCP/CB |

---

## Import 방법

### 1. Grafana UI에서 Import

1. Grafana 접속: `http://192.168.124.100:8080/grafana/`
2. 좌측 메뉴 → **Dashboards → Import**
3. **Upload dashboard JSON file** 클릭
4. 이 폴더의 JSON 파일 선택
5. **Datasource**: Prometheus 선택
6. **Import** 클릭

### 2. Grafana API로 자동 Import (CI/CD 연동용)

```bash
# 스테이징 서버에서 실행
curl -X POST \
  -H "Content-Type: application/json" \
  -d "{\"dashboard\": $(cat golden-signals.json), \"overwrite\": true, \"folderId\": 0}" \
  http://admin:admin@192.168.124.100:8080/grafana/api/dashboards/import
```

---

## Golden Signals 대시보드 패널 구성

### Row 1: HTTP Traffic (stat 패널 6개)

| 패널 | PromQL 핵심 | 설명 |
|------|------------|------|
| RPS | `rate(http_server_requests_seconds_count[$interval])` | 초당 요청 수 |
| Error Rate (%) | `rate(...status=~"5..")` / total × 100 | 5xx 비율. 임계: 1% / 5% |
| Latency p95 | `histogram_quantile(0.95, ...)` | 임계: 200ms / 500ms |
| Latency p99 | `histogram_quantile(0.99, ...)` | 임계: 500ms / 1000ms |
| Active Requests | `http_server_requests_active_seconds_active_count` | 현재 처리 중 |
| Outcome 분포 | `sum by (outcome) (rate(...))` | SUCCESS/4xx/5xx 분리 |

### Row 2: Latency 추이

| 패널 | 설명 |
|------|------|
| p50/p95/p99 시계열 | 부하 증가 시 knee point 식별 |
| URI별 p99 Top 10 | 느린 엔드포인트 식별 (병목 분석) |

### Row 3: JVM

| 패널 | 설명 |
|------|------|
| Heap 사용률 (%) | 임계: 70% / 85% |
| Heap 사용량 추이 | Used/Committed/Max |
| GC Pause rate | GC 폭증 = Heap 부족 전조 |

### Row 4: HikariCP (DB 커넥션풀)

| 패널 | 설명 |
|------|------|
| Active/Idle/Pending 추이 | Pending > 0 = DB 병목 |
| 풀 사용률 (%) | 임계: 60% / 80% |
| 커넥션 획득 p99 (ms) | 풀 고갈 시 급등 |

### Row 5: CircuitBreaker (SCG)

| 패널 | 설명 |
|------|------|
| CB State (CLOSED/OPEN) | booking/concert/payment/user 4개 CB |
| CB 호출 성공/실패율 | failureRateThreshold(50%) 도달 여부 |

### Row 6: Threads

| 패널 | 설명 |
|------|------|
| 스레드 상태별 수 | RUNNABLE/BLOCKED/WAITING |
| Live/Peak/Daemon 추이 | Thread leak 감지 |

---

## 알려진 한계 (Known Limitations)

### p95/p99 패널에 일부 서비스 미표시

**원인**: `http_server_requests_seconds_bucket` 메트릭이 `user-service`, `scg-service`에서 미노출

**해결**: 해당 서비스 `application.yml`에 아래 설정 추가:

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
```

**현재 histogram bucket 노출 서비스 (4개)**:
- `waitingroom-service` ✅
- `booking-service` ✅
- `concert-service` ✅
- `payment-service` ✅

**미노출 서비스 (2개)**:
- `user-service` ❌ → yml 설정 추가 필요
- `scg-service` ❌ → WebFlux 기반이라 설정 경로가 다를 수 있음

### SCG CircuitBreaker Lazy Initialization

SCG의 CB는 트래픽이 없으면 메트릭이 등록되지 않음.
서버 재시작 후 k6 warm-up 스크립트(`scenario0-warmup.js`)를 먼저 실행해야 CB 패널에 데이터가 표시됨.

```bash
k6 run --env SCG_BASE_URL=http://192.168.124.100:8090 \
  load-test/scripts/k6/scenarios/scenario0-warmup.js
```

---

## 관련 문서

- [observability.md](../observability.md) — 관측성 전체 설계
- [sli-slo.md](../../performance/sli-slo.md) — SLI/SLO 목표치
- [performance-test-runbook.md](../../performance/performance-test-runbook.md) — k6 부하테스트 방법
