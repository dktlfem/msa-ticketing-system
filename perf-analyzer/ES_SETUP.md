# perf-analyzer → Elasticsearch 적재 설정 가이드

## 사전 조건

perf-analyzer가 Elasticsearch에 분석 결과를 적재하려면,
ES 포트(9200)가 호스트에서 접근 가능해야 합니다.

현재 스테이징 서버의 ES는 Docker 내부 포트만 열려 있습니다.

## Step 1: ES 포트 호스트 바인딩

스테이징 서버에서 ES를 관리하는 docker-compose 파일을 찾아서
`elasticsearch` 서비스에 포트 바인딩을 추가합니다.

```yaml
# docker-compose.yml (ELK 스택)
services:
  elasticsearch:
    # 기존 설정 유지하고 ports만 추가
    ports:
      - "9200:9200"
    # ... 나머지 설정
```

변경 후 재기동:

```bash
docker compose up -d elasticsearch
```

포트 바인딩 확인:

```bash
curl -s http://localhost:9200 | jq .cluster_name
# 응답: "docker-cluster" 또는 비슷한 값이면 성공
```

## Step 2: 연결 확인

```bash
cd /path/to/ci-cd-test/perf-analyzer
PYTHONPATH=. python3 -c "
from perf_analyzer.exporter import check_es_connection, get_es_health
url = 'http://192.168.124.100:9200'
print('연결:', check_es_connection(url))
print('상태:', get_es_health(url))
"
```

## Step 3: 사용법

### 방법 A: report 명령에 ES 적재 옵션 추가

리포트 생성과 동시에 ES에 적재:

```bash
PYTHONPATH=. python3 -m perf_analyzer.cli report \
  ../load-test/scripts/k6/results/2026-03-22 \
  --prometheus http://192.168.124.100:8080/prometheus \
  --es-url http://192.168.124.100:9200
```

### 방법 B: export 명령으로 별도 적재

이미 생성된 결과를 ES에만 적재:

```bash
PYTHONPATH=. python3 -m perf_analyzer.cli export \
  ../load-test/scripts/k6/results/2026-03-22 \
  --es-url http://192.168.124.100:9200
```

### 방법 C: 이력 조회

```bash
# 전체 이력
PYTHONPATH=. python3 -m perf_analyzer.cli history \
  --es-url http://192.168.124.100:9200

# 시나리오별 필터
PYTHONPATH=. python3 -m perf_analyzer.cli history \
  --es-url http://192.168.124.100:9200 \
  --type results \
  --scenario scenario1-rate-limiter

# critical 병목만
PYTHONPATH=. python3 -m perf_analyzer.cli history \
  --es-url http://192.168.124.100:9200 \
  --type bottlenecks \
  --severity critical
```

## Step 4 (선택): run-tests.sh에 자동 적재 연동

`run-tests.sh` 실행 후 자동으로 ES에 적재하려면 환경변수를 설정합니다:

```bash
export ES_URL="http://192.168.124.100:9200"
```

## ES 인덱스 구조

### perf-test-results

k6 시나리오별 요약 데이터. Kibana에서 시간 축으로 TPS/P95/에러율 트렌드를 볼 수 있습니다.

| 필드 | 타입 | 설명 |
|------|------|------|
| scenario | keyword | 시나리오 이름 |
| test_date | date | 테스트 날짜 |
| run_id | keyword | 실행 고유 ID |
| passed | boolean | 판정 |
| total_requests | integer | 총 요청 수 |
| tps / peak_tps | float | 평균/피크 TPS |
| p50_ms / p95_ms / p99_ms | float | 레이턴시 퍼센타일 |
| error_rate | keyword | 에러율 |
| http_status_dist | object | HTTP 상태 코드 분포 |
| duration_seconds | float | 테스트 소요 시간 |

### perf-bottlenecks

식별된 병목 이력. severity별 필터링으로 반복 병목 패턴을 추적할 수 있습니다.

| 필드 | 타입 | 설명 |
|------|------|------|
| category | keyword | 병목 카테고리 |
| severity | keyword | 심각도 (critical/high/medium/low) |
| description | text | 병목 설명 |
| incident_id | keyword | 런북 ID (INC-xxx) |
| metric_name | keyword | Prometheus 메트릭 이름 |
| threshold / actual_value | float | 임계치 / 실측치 |
| recommended_actions | text | 권장 조치 |

## Kibana 대시보드 (권장)

ES에 데이터가 적재되면 Kibana에서 다음 시각화를 만들 수 있습니다:

1. **테스트 이력 테이블**: test_date × scenario별 P95/TPS/에러율
2. **P95 트렌드 차트**: 날짜별 P95 변화 (개선 전/후 비교)
3. **병목 빈도 파이차트**: category별 병목 발생 횟수
4. **severity 히트맵**: 날짜 × severity 분포

Data View(인덱스 패턴)를 `perf-test-results`와 `perf-bottlenecks`로 각각 생성하면 됩니다.
