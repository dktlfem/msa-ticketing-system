#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════
# SCG Observability 증거 수집 스크립트
# scenario12 (보안 검증) + scenario13 (resilience) 교차검증
#
# 사용법:
#   cd ~/projects/ci-cd-test/load-test/scripts/k6
#   chmod +x collect-observability.sh
#   ./collect-observability.sh
#
# 전제 조건: OpenVPN 연결 상태
# ═══════════════════════════════════════════════════════════════════
set -euo pipefail

# ── 설정 ──────────────────────────────────────────────────────────
BASE="http://192.168.124.100:8080"
PROM="${BASE}/prometheus"
JAEGER="${BASE}/jaeger"
GRAFANA="${BASE}/grafana"
# Elasticsearch (포트 확인 필요 — 기본 9200, Kibana proxy 경유 시 다름)
ES="http://192.168.124.100:9200"

DATE="2026-04-08"
OUTDIR="results/${DATE}/observability"
mkdir -p "${OUTDIR}"/{prometheus,jaeger,elasticsearch,grafana}

# scenario12: 13:07:22~13:07:52 KST = 04:07:00~04:08:00 UTC
S12_START="2026-04-08T04:06:30Z"
S12_END="2026-04-08T04:08:30Z"
S12_RUN_ID="20260408-130752"

# scenario13: 13:22:01~13:25:03 KST = 04:22:01~04:25:03 UTC
S13_START="2026-04-08T04:21:00Z"
S13_END="2026-04-08T04:26:00Z"
S13_RUN_ID="20260408-132503"

# Jaeger용 마이크로초 타임스탬프
s12_start_us=$(date -j -u -f "%Y-%m-%dT%H:%M:%SZ" "${S12_START}" "+%s" 2>/dev/null || date -d "${S12_START}" +%s)000000
s12_end_us=$(date -j -u -f "%Y-%m-%dT%H:%M:%SZ" "${S12_END}" "+%s" 2>/dev/null || date -d "${S12_END}" +%s)000000
s13_start_us=$(date -j -u -f "%Y-%m-%dT%H:%M:%SZ" "${S13_START}" "+%s" 2>/dev/null || date -d "${S13_START}" +%s)000000
s13_end_us=$(date -j -u -f "%Y-%m-%dT%H:%M:%SZ" "${S13_END}" "+%s" 2>/dev/null || date -d "${S13_END}" +%s)000000

ok() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1 (응답 없음 — 서비스 확인 필요)"; }
save() { echo "$2" > "$1"; }

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  SCG Observability 증거 수집 시작                            ║"
echo "║  scenario12 run_id=${S12_RUN_ID}                            ║"
echo "║  scenario13 run_id=${S13_RUN_ID}                            ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""

# ═══════════════════════════════════════════════════════════════════
# 1. PROMETHEUS
# ═══════════════════════════════════════════════════════════════════
echo "━━━ [1/4] Prometheus 메트릭 수집 ━━━"

# 1-1. scenario12 시간대: HTTP status 분포
echo -n "  [1-1] scenario12 HTTP status 분포... "
RESULT=$(curl -s --connect-timeout 5 "${PROM}/api/v1/query_range" \
  --data-urlencode "query=sum by (status)(increase(http_server_requests_seconds_count{application=\"scg-app\"}[30s]))" \
  --data-urlencode "start=${S12_START}" \
  --data-urlencode "end=${S12_END}" \
  --data-urlencode "step=15s" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -m json.tool > /dev/null 2>&1; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/prometheus/s12_http_status_distribution.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/prometheus/s12_http_status_distribution.json"
  fail "s12 HTTP status"
fi

# 1-2. scenario12 시간대: 401/403 카운트 (인증 실패, QueueToken 차단)
echo -n "  [1-2] scenario12 401+403 카운트... "
RESULT=$(curl -s --connect-timeout 5 "${PROM}/api/v1/query_range" \
  --data-urlencode "query=sum by (status, uri)(increase(http_server_requests_seconds_count{application=\"scg-app\", status=~\"401|403\"}[30s]))" \
  --data-urlencode "start=${S12_START}" \
  --data-urlencode "end=${S12_END}" \
  --data-urlencode "step=15s" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -m json.tool > /dev/null 2>&1; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/prometheus/s12_auth_queue_errors.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/prometheus/s12_auth_queue_errors.json"
  fail "s12 401/403"
fi

# 1-3. scenario12 시간대: 응답 시간 P95 (latency)
echo -n "  [1-3] scenario12 응답 시간 P95... "
RESULT=$(curl -s --connect-timeout 5 "${PROM}/api/v1/query_range" \
  --data-urlencode "query=histogram_quantile(0.95, sum by (le)(rate(http_server_requests_seconds_bucket{application=\"scg-app\"}[30s])))" \
  --data-urlencode "start=${S12_START}" \
  --data-urlencode "end=${S12_END}" \
  --data-urlencode "step=15s" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -m json.tool > /dev/null 2>&1; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/prometheus/s12_latency_p95.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/prometheus/s12_latency_p95.json"
  fail "s12 latency"
fi

# 1-4. scenario13 시간대: HTTP status 분포 (503/502 spike 확인)
echo -n "  [1-4] scenario13 HTTP status 분포... "
RESULT=$(curl -s --connect-timeout 5 "${PROM}/api/v1/query_range" \
  --data-urlencode "query=sum by (status)(increase(http_server_requests_seconds_count{application=\"scg-app\"}[30s]))" \
  --data-urlencode "start=${S13_START}" \
  --data-urlencode "end=${S13_END}" \
  --data-urlencode "step=15s" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -m json.tool > /dev/null 2>&1; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/prometheus/s13_http_status_distribution.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/prometheus/s13_http_status_distribution.json"
  fail "s13 HTTP status"
fi

# 1-5. scenario13: Circuit Breaker 상태 변화
echo -n "  [1-5] scenario13 CB state... "
RESULT=$(curl -s --connect-timeout 5 "${PROM}/api/v1/query_range" \
  --data-urlencode "query=resilience4j_circuitbreaker_state{application=\"scg-app\"}" \
  --data-urlencode "start=${S13_START}" \
  --data-urlencode "end=${S13_END}" \
  --data-urlencode "step=15s" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -m json.tool > /dev/null 2>&1; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/prometheus/s13_cb_state.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/prometheus/s13_cb_state.json"
  fail "s13 CB state"
fi

# 1-6. scenario13: CB 실패 호출 수
echo -n "  [1-6] scenario13 CB failed calls... "
RESULT=$(curl -s --connect-timeout 5 "${PROM}/api/v1/query_range" \
  --data-urlencode "query=sum by (name, kind)(increase(resilience4j_circuitbreaker_calls_total{application=\"scg-app\"}[30s]))" \
  --data-urlencode "start=${S13_START}" \
  --data-urlencode "end=${S13_END}" \
  --data-urlencode "step=15s" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -m json.tool > /dev/null 2>&1; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/prometheus/s13_cb_calls.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/prometheus/s13_cb_calls.json"
  fail "s13 CB calls"
fi

# 1-7. scenario13: concert-app 격리 확인 (정상 서비스 응답 시간)
echo -n "  [1-7] scenario13 concert-app 격리 P95... "
RESULT=$(curl -s --connect-timeout 5 "${PROM}/api/v1/query_range" \
  --data-urlencode "query=histogram_quantile(0.95, sum by (le)(rate(http_server_requests_seconds_bucket{application=\"scg-app\", uri=~\".*events.*\"}[30s])))" \
  --data-urlencode "start=${S13_START}" \
  --data-urlencode "end=${S13_END}" \
  --data-urlencode "step=15s" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -m json.tool > /dev/null 2>&1; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/prometheus/s13_concert_latency_p95.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/prometheus/s13_concert_latency_p95.json"
  fail "s13 concert latency"
fi

echo ""

# ═══════════════════════════════════════════════════════════════════
# 2. JAEGER
# ═══════════════════════════════════════════════════════════════════
echo "━━━ [2/4] Jaeger 트레이스 수집 ━━━"

# 2-1. scenario12: scg-app 전체 트레이스 (limit 10)
echo -n "  [2-1] scenario12 scg-app 트레이스... "
RESULT=$(curl -s --connect-timeout 5 \
  "${JAEGER}/api/traces?service=scg-app&start=${s12_start_us}&end=${s12_end_us}&limit=10&lookback=custom" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/jaeger/s12_traces.json"
  TRACE_COUNT=$(echo "$RESULT" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('data',[])))" 2>/dev/null || echo "?")
  ok "저장 완료 (${TRACE_COUNT}건)"
else
  echo "$RESULT" > "${OUTDIR}/jaeger/s12_traces.json"
  fail "s12 traces"
fi

# 2-2. scenario13: error 트레이스 (timeout/CB)
echo -n "  [2-2] scenario13 error 트레이스... "
RESULT=$(curl -s --connect-timeout 5 \
  "${JAEGER}/api/traces?service=scg-app&start=${s13_start_us}&end=${s13_end_us}&limit=10&tags=%7B%22error%22%3A%22true%22%7D&lookback=custom" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/jaeger/s13_error_traces.json"
  TRACE_COUNT=$(echo "$RESULT" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('data',[])))" 2>/dev/null || echo "?")
  ok "저장 완료 (${TRACE_COUNT}건)"
else
  echo "$RESULT" > "${OUTDIR}/jaeger/s13_error_traces.json"
  fail "s13 error traces"
fi

# 2-3. scenario13: 슬로우 트레이스 (duration > 1s)
echo -n "  [2-3] scenario13 슬로우 트레이스 (>1s)... "
RESULT=$(curl -s --connect-timeout 5 \
  "${JAEGER}/api/traces?service=scg-app&start=${s13_start_us}&end=${s13_end_us}&limit=10&minDuration=1000ms&lookback=custom" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/jaeger/s13_slow_traces.json"
  TRACE_COUNT=$(echo "$RESULT" | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('data',[])))" 2>/dev/null || echo "?")
  ok "저장 완료 (${TRACE_COUNT}건)"
else
  echo "$RESULT" > "${OUTDIR}/jaeger/s13_slow_traces.json"
  fail "s13 slow traces"
fi

# 2-4. Jaeger 서비스 의존성 맵
echo -n "  [2-4] 서비스 의존성 맵... "
RESULT=$(curl -s --connect-timeout 5 "${JAEGER}/api/dependencies?endTs=$(date +%s)000&lookback=3600000" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/jaeger/service_dependencies.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/jaeger/service_dependencies.json"
  fail "dependencies"
fi

echo ""

# ═══════════════════════════════════════════════════════════════════
# 3. ELASTICSEARCH
# ═══════════════════════════════════════════════════════════════════
echo "━━━ [3/4] Elasticsearch 로그 수집 ━━━"

# ES 접근 가능 여부 확인 (9200 또는 kibana proxy 시도)
ES_OK=false
for ES_TRY in "http://192.168.124.100:9200" "${BASE}/elasticsearch" "${BASE}/kibana/api/console/proxy"; do
  if curl -s --connect-timeout 3 "${ES_TRY}" 2>/dev/null | grep -q "cluster_name\|kibana" 2>/dev/null; then
    ES="${ES_TRY}"
    ES_OK=true
    echo "  ES endpoint: ${ES}"
    break
  fi
done
if [ "$ES_OK" = false ]; then
  echo "  ⚠ Elasticsearch 직접 접근 불가 — Kibana proxy 시도"
  ES="http://192.168.124.100:9200"
fi

# 3-1. scenario12 시간대: ERROR/WARN 로그 (인증 실패, 헤더 sanitize)
echo -n "  [3-1] scenario12 ERROR/WARN 로그... "
RESULT=$(curl -s --connect-timeout 5 -X POST "${ES}/filebeat-*/_search" \
  -H 'Content-Type: application/json' \
  -d '{
    "size": 20,
    "sort": [{"@timestamp": "desc"}],
    "query": {
      "bool": {
        "must": [
          {"range": {"@timestamp": {"gte": "'"${S12_START}"'", "lte": "'"${S12_END}"'"}}},
          {"terms": {"log.level": ["ERROR", "WARN"]}}
        ],
        "should": [
          {"match_phrase": {"message": "QUEUE_TOKEN"}},
          {"match_phrase": {"message": "JWT"}},
          {"match_phrase": {"message": "sanitize"}},
          {"match_phrase": {"message": "Unauthorized"}}
        ],
        "minimum_should_match": 0
      }
    },
    "_source": ["@timestamp", "message", "log.level", "service.name", "requestId"]
  }' 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/elasticsearch/s12_error_warn_logs.json"
  HIT_COUNT=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('hits',{}).get('total',{}).get('value', '?'))" 2>/dev/null || echo "?")
  ok "저장 완료 (${HIT_COUNT}건)"
else
  echo "$RESULT" > "${OUTDIR}/elasticsearch/s12_error_warn_logs.json"
  fail "s12 logs"
fi

# 3-2. scenario12: Queue-Token 차단 로그 (WARN: QUEUE_TOKEN Missing)
echo -n "  [3-2] scenario12 Queue-Token 차단 로그... "
RESULT=$(curl -s --connect-timeout 5 -X POST "${ES}/filebeat-*/_search" \
  -H 'Content-Type: application/json' \
  -d '{
    "size": 10,
    "sort": [{"@timestamp": "desc"}],
    "query": {
      "bool": {
        "must": [
          {"range": {"@timestamp": {"gte": "'"${S12_START}"'", "lte": "'"${S12_END}"'"}}},
          {"match_phrase": {"message": "QUEUE_TOKEN"}}
        ]
      }
    },
    "_source": ["@timestamp", "message", "log.level", "requestId"]
  }' 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/elasticsearch/s12_queue_token_logs.json"
  HIT_COUNT=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('hits',{}).get('total',{}).get('value', '?'))" 2>/dev/null || echo "?")
  ok "저장 완료 (${HIT_COUNT}건)"
else
  echo "$RESULT" > "${OUTDIR}/elasticsearch/s12_queue_token_logs.json"
  fail "s12 queue token logs"
fi

# 3-3. scenario13 시간대: timeout/CircuitBreaker/fallback 로그
echo -n "  [3-3] scenario13 timeout/CB/fallback 로그... "
RESULT=$(curl -s --connect-timeout 5 -X POST "${ES}/filebeat-*/_search" \
  -H 'Content-Type: application/json' \
  -d '{
    "size": 20,
    "sort": [{"@timestamp": "desc"}],
    "query": {
      "bool": {
        "must": [
          {"range": {"@timestamp": {"gte": "'"${S13_START}"'", "lte": "'"${S13_END}"'"}}}
        ],
        "should": [
          {"match_phrase": {"message": "timeout"}},
          {"match_phrase": {"message": "CircuitBreaker"}},
          {"match_phrase": {"message": "fallback"}},
          {"match_phrase": {"message": "waitingroom"}},
          {"match_phrase": {"message": "503"}},
          {"match_phrase": {"message": "502"}}
        ],
        "minimum_should_match": 1
      }
    },
    "_source": ["@timestamp", "message", "log.level", "service.name", "requestId"]
  }' 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/elasticsearch/s13_timeout_cb_logs.json"
  HIT_COUNT=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('hits',{}).get('total',{}).get('value', '?'))" 2>/dev/null || echo "?")
  ok "저장 완료 (${HIT_COUNT}건)"
else
  echo "$RESULT" > "${OUTDIR}/elasticsearch/s13_timeout_cb_logs.json"
  fail "s13 CB logs"
fi

# 3-4. scenario12: X-Request-Id propagation 로그 확인
echo -n "  [3-4] X-Request-Id propagation 로그... "
RESULT=$(curl -s --connect-timeout 5 -X POST "${ES}/filebeat-*/_search" \
  -H 'Content-Type: application/json' \
  -d '{
    "size": 5,
    "sort": [{"@timestamp": "desc"}],
    "query": {
      "bool": {
        "must": [
          {"range": {"@timestamp": {"gte": "'"${S12_START}"'", "lte": "'"${S12_END}"'"}}},
          {"exists": {"field": "requestId"}}
        ]
      }
    },
    "_source": ["@timestamp", "message", "log.level", "service.name", "requestId", "traceId"]
  }' 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/elasticsearch/s12_request_id_propagation.json"
  HIT_COUNT=$(echo "$RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('hits',{}).get('total',{}).get('value', '?'))" 2>/dev/null || echo "?")
  ok "저장 완료 (${HIT_COUNT}건)"
else
  echo "$RESULT" > "${OUTDIR}/elasticsearch/s12_request_id_propagation.json"
  fail "s12 request-id"
fi

# 3-5. 서비스별 로그 수 집계
echo -n "  [3-5] 전체 시간대 서비스별 로그 집계... "
RESULT=$(curl -s --connect-timeout 5 -X POST "${ES}/filebeat-*/_search" \
  -H 'Content-Type: application/json' \
  -d '{
    "size": 0,
    "query": {
      "range": {"@timestamp": {"gte": "2026-04-08T04:06:00Z", "lte": "2026-04-08T04:27:00Z"}}
    },
    "aggs": {
      "by_service": {
        "terms": {"field": "service.name", "size": 20},
        "aggs": {
          "by_level": {
            "terms": {"field": "log.level", "size": 10}
          }
        }
      }
    }
  }' 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/elasticsearch/log_aggregation.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/elasticsearch/log_aggregation.json"
  fail "log aggregation"
fi

echo ""

# ═══════════════════════════════════════════════════════════════════
# 4. GRAFANA
# ═══════════════════════════════════════════════════════════════════
echo "━━━ [4/4] Grafana 대시보드 데이터 수집 ━━━"

# 4-1. 대시보드 목록 조회
echo -n "  [4-1] 대시보드 목록... "
RESULT=$(curl -s --connect-timeout 5 "${GRAFANA}/api/search?type=dash-db" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/grafana/dashboard_list.json"
  DB_COUNT=$(echo "$RESULT" | python3 -c "import sys,json; print(len(json.load(sys.stdin)))" 2>/dev/null || echo "?")
  ok "저장 완료 (${DB_COUNT}개)"

  # SCG 관련 대시보드 UID 추출
  SCG_UID=$(echo "$RESULT" | python3 -c "
import sys, json
dbs = json.load(sys.stdin)
for db in dbs:
    title = db.get('title','').lower()
    if 'scg' in title or 'gateway' in title or 'spring cloud' in title:
        print(db.get('uid',''))
        break
" 2>/dev/null)
else
  echo "$RESULT" > "${OUTDIR}/grafana/dashboard_list.json"
  fail "dashboard list"
  SCG_UID=""
fi

# 4-2. SCG 대시보드 JSON 모델 (있으면)
if [ -n "$SCG_UID" ]; then
  echo -n "  [4-2] SCG 대시보드 모델 (uid=${SCG_UID})... "
  RESULT=$(curl -s --connect-timeout 5 "${GRAFANA}/api/dashboards/uid/${SCG_UID}" 2>/dev/null)
  if [ -n "$RESULT" ]; then
    echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/grafana/scg_dashboard_model.json" 2>/dev/null
    ok "저장 완료"
  else
    fail "SCG dashboard model"
  fi
else
  echo "  [4-2] SCG 대시보드 UID 미발견 — 건너뜀"
fi

# 4-3. Grafana 알림 이력 (최근 1시간)
echo -n "  [4-3] 알림 이력... "
RESULT=$(curl -s --connect-timeout 5 "${GRAFANA}/api/alertmanager/grafana/api/v2/alerts?active=true&silenced=false&inhibited=false" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/grafana/alerts.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/grafana/alerts.json"
  fail "alerts"
fi

# 4-4. AlertManager 알림 (별도 서비스)
echo -n "  [4-4] AlertManager 알림... "
RESULT=$(curl -s --connect-timeout 5 "${BASE}/alertmanager/api/v2/alerts" 2>/dev/null)
if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/grafana/alertmanager_alerts.json"
  ok "저장 완료"
else
  echo "$RESULT" > "${OUTDIR}/grafana/alertmanager_alerts.json"
  fail "alertmanager"
fi

echo ""

# ═══════════════════════════════════════════════════════════════════
# 수집 요약
# ═══════════════════════════════════════════════════════════════════
echo "━━━ 수집 완료 ━━━"
echo ""
echo "저장 위치: ${OUTDIR}/"
echo ""
find "${OUTDIR}" -type f -name "*.json" | sort | while read f; do
  SIZE=$(wc -c < "$f" | tr -d ' ')
  echo "  $(basename "$f") (${SIZE} bytes)"
done

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  수집 완료!                                                  ║"
echo "║                                                              ║"
echo "║  수동 캡처 추천 (Chrome 스크린샷):                             ║"
echo "║  1. Grafana SCG 대시보드 → HTTP Status Distribution 패널     ║"
echo "║  2. Grafana CB State 패널 → scenario13 시간대                ║"
echo "║  3. Jaeger scg-app → 503 에러 트레이스 상세                  ║"
echo "║  4. Kibana Discover → QUEUE_TOKEN 필터 적용 화면             ║"
echo "║  5. AlertManager → waitingroom DOWN 알림 목록                ║"
echo "╚══════════════════════════════════════════════════════════════╝"
