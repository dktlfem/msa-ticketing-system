#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
# Prometheus 직접 수집 (oauth2-proxy 우회 — 내부 포트 직접 접근)
# Grafana도 직접 포트(3000)로 접근
#
# 사용법:
#   cd ~/projects/ci-cd-test/load-test/scripts/k6
#   chmod +x collect-prometheus-direct.sh
#   ./collect-prometheus-direct.sh
# ═══════════════════════════════════════════════════════════════
set -euo pipefail

# Prometheus: 기본 9090, Grafana: 기본 3000
# Nginx proxy(8080) 뒤에 있어 oauth2 인증이 필요하므로, 직접 포트로 접근
# docker compose에서 포트 매핑 확인 필요 — 아래 후보를 순서대로 시도
OUTDIR="results/2026-04-08/observability"
mkdir -p "${OUTDIR}"/{prometheus,grafana}

S12_START="2026-04-08T04:06:30Z"
S12_END="2026-04-08T04:08:30Z"
S13_START="2026-04-08T04:21:00Z"
S13_END="2026-04-08T04:26:00Z"

ok() { echo "  ✓ $1"; }
fail() { echo "  ✗ $1"; }

# ── Prometheus 엔드포인트 탐색 ──
echo "━━━ Prometheus 엔드포인트 탐색 ━━━"
PROM=""
for PORT in 9090 19090 29090; do
  echo -n "  http://192.168.124.100:${PORT}... "
  if curl -s --connect-timeout 3 "http://192.168.124.100:${PORT}/api/v1/status/config" 2>/dev/null | grep -q '"status"' 2>/dev/null; then
    PROM="http://192.168.124.100:${PORT}"
    echo "✓ 접근 가능"
    break
  else
    echo "✗"
  fi
done

# SSH 경유 시도 (직접 접근 불가 시)
if [ -z "$PROM" ]; then
  echo ""
  echo "  직접 포트 접근 불가 — SSH 경유로 시도합니다..."
  echo -n "  ssh 192.168.124.100 curl localhost:9090... "
  TEST=$(ssh -o ConnectTimeout=5 FAMILY@192.168.124.100 "curl -s localhost:9090/api/v1/status/config 2>/dev/null" 2>/dev/null || echo "")
  if echo "$TEST" | grep -q '"status"' 2>/dev/null; then
    PROM="SSH_PROXY"
    echo "✓ SSH 경유 접근 가능"
  else
    echo "✗"
  fi
fi

if [ -z "$PROM" ]; then
  echo ""
  echo "  ⚠ Prometheus 접근 불가. 아래 명령으로 포트를 확인해주세요:"
  echo "    ssh 192.168.124.100 \"docker compose ps\" | grep prometheus"
  echo "    ssh 192.168.124.100 \"curl -s localhost:9090/api/v1/status/config | head -1\""
  echo ""
  echo "  또는 포트 포워딩:"
  echo "    ssh -L 9090:localhost:9090 192.168.124.100"
  echo "  후 다시 실행"
  exit 1
fi

# ── 쿼리 함수 ──
prom_query() {
  local query="$1" start="$2" end="$3" step="${4:-15s}" outfile="$5"
  local result=""

  if [ "$PROM" = "SSH_PROXY" ]; then
    result=$(ssh FAMILY@192.168.124.100 "curl -s 'localhost:9090/api/v1/query_range' \
      --data-urlencode 'query=${query}' \
      --data-urlencode 'start=${start}' \
      --data-urlencode 'end=${end}' \
      --data-urlencode 'step=${step}'" 2>/dev/null)
  else
    result=$(curl -s --connect-timeout 5 "${PROM}/api/v1/query_range" \
      --data-urlencode "query=${query}" \
      --data-urlencode "start=${start}" \
      --data-urlencode "end=${end}" \
      --data-urlencode "step=${step}" 2>/dev/null)
  fi

  if [ -n "$result" ] && echo "$result" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d.get('status')=='success'" 2>/dev/null; then
    echo "$result" | python3 -m json.tool > "$outfile"
    return 0
  else
    echo "$result" > "$outfile"
    return 1
  fi
}

echo ""
echo "━━━ [1/2] Prometheus 메트릭 수집 ━━━"

# 1-1. scenario12 HTTP status 분포
echo -n "  [1-1] scenario12 HTTP status 분포... "
if prom_query 'sum by (status)(increase(http_server_requests_seconds_count{application="scg-app"}[30s]))' \
  "$S12_START" "$S12_END" "15s" "${OUTDIR}/prometheus/s12_http_status_distribution.json"; then
  ok "저장 완료"
else fail "[1-1]"; fi

# 1-2. scenario12 401/403 카운트
echo -n "  [1-2] scenario12 401+403 카운트... "
if prom_query 'sum by (status, uri)(increase(http_server_requests_seconds_count{application="scg-app", status=~"401|403"}[30s]))' \
  "$S12_START" "$S12_END" "15s" "${OUTDIR}/prometheus/s12_auth_queue_errors.json"; then
  ok "저장 완료"
else fail "[1-2]"; fi

# 1-3. scenario12 P95
echo -n "  [1-3] scenario12 응답 시간 P95... "
if prom_query 'histogram_quantile(0.95, sum by (le)(rate(http_server_requests_seconds_bucket{application="scg-app"}[30s])))' \
  "$S12_START" "$S12_END" "15s" "${OUTDIR}/prometheus/s12_latency_p95.json"; then
  ok "저장 완료"
else fail "[1-3]"; fi

# 1-4. scenario13 HTTP status 분포
echo -n "  [1-4] scenario13 HTTP status 분포... "
if prom_query 'sum by (status)(increase(http_server_requests_seconds_count{application="scg-app"}[30s]))' \
  "$S13_START" "$S13_END" "15s" "${OUTDIR}/prometheus/s13_http_status_distribution.json"; then
  ok "저장 완료"
else fail "[1-4]"; fi

# 1-5. scenario13 CB state
echo -n "  [1-5] scenario13 CB state... "
if prom_query 'resilience4j_circuitbreaker_state{application="scg-app"}' \
  "$S13_START" "$S13_END" "15s" "${OUTDIR}/prometheus/s13_cb_state.json"; then
  ok "저장 완료"
else fail "[1-5]"; fi

# 1-6. scenario13 CB calls
echo -n "  [1-6] scenario13 CB failed calls... "
if prom_query 'sum by (name, kind)(increase(resilience4j_circuitbreaker_calls_total{application="scg-app"}[30s]))' \
  "$S13_START" "$S13_END" "15s" "${OUTDIR}/prometheus/s13_cb_calls.json"; then
  ok "저장 완료"
else fail "[1-6]"; fi

# 1-7. scenario13 concert-app 격리
echo -n "  [1-7] scenario13 concert-app 격리 P95... "
if prom_query 'histogram_quantile(0.95, sum by (le)(rate(http_server_requests_seconds_bucket{application="scg-app", uri=~".*events.*"}[30s])))' \
  "$S13_START" "$S13_END" "15s" "${OUTDIR}/prometheus/s13_concert_latency_p95.json"; then
  ok "저장 완료"
else fail "[1-7]"; fi

echo ""
echo "━━━ [2/2] Grafana 대시보드 데이터 수집 ━━━"

# Grafana 직접 포트 탐색
GRAFANA=""
for PORT in 3000 13000; do
  echo -n "  http://192.168.124.100:${PORT}... "
  if curl -s --connect-timeout 3 "http://192.168.124.100:${PORT}/api/health" 2>/dev/null | grep -q "ok" 2>/dev/null; then
    GRAFANA="http://192.168.124.100:${PORT}"
    echo "✓ 접근 가능"
    break
  else
    echo "✗"
  fi
done

if [ -z "$GRAFANA" ]; then
  echo "  SSH 경유로 Grafana 시도..."
  TEST=$(ssh -o ConnectTimeout=5 FAMILY@192.168.124.100 "curl -s localhost:3000/api/health" 2>/dev/null || echo "")
  if echo "$TEST" | grep -q "ok" 2>/dev/null; then
    GRAFANA="SSH_PROXY_GF"
    echo "  ✓ SSH 경유 Grafana 접근 가능"
  else
    echo "  ⚠ Grafana 접근 불가 — 건너뜀"
  fi
fi

if [ -n "$GRAFANA" ]; then
  gf_get() {
    local path="$1" outfile="$2"
    if [ "$GRAFANA" = "SSH_PROXY_GF" ]; then
      ssh FAMILY@192.168.124.100 "curl -s 'localhost:3000${path}'" 2>/dev/null
    else
      curl -s --connect-timeout 5 "${GRAFANA}${path}" 2>/dev/null
    fi
  }

  echo -n "  [2-1] 대시보드 목록... "
  RESULT=$(gf_get "/api/search?type=dash-db" "")
  if [ -n "$RESULT" ] && echo "$RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
    echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/grafana/dashboard_list.json"
    ok "저장 완료"
  else fail "[2-1]"; fi

  echo -n "  [2-2] 알림 이력... "
  RESULT=$(gf_get "/api/alertmanager/grafana/api/v2/alerts" "")
  if [ -n "$RESULT" ]; then
    echo "$RESULT" | python3 -m json.tool > "${OUTDIR}/grafana/alerts.json" 2>/dev/null
    ok "저장 완료"
  else fail "[2-2]"; fi
fi

# AlertManager 직접 포트
echo -n "  [2-3] AlertManager... "
AM_RESULT=""
for PORT in 9093 19093; do
  AM_RESULT=$(curl -s --connect-timeout 3 "http://192.168.124.100:${PORT}/api/v2/alerts" 2>/dev/null || echo "")
  if echo "$AM_RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
    echo "$AM_RESULT" | python3 -m json.tool > "${OUTDIR}/grafana/alertmanager_alerts.json"
    ok "저장 완료 (port ${PORT})"
    break
  fi
done
if [ -z "$AM_RESULT" ] || ! echo "$AM_RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
  AM_RESULT=$(ssh -o ConnectTimeout=5 FAMILY@192.168.124.100 "curl -s localhost:9093/api/v2/alerts" 2>/dev/null || echo "")
  if echo "$AM_RESULT" | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
    echo "$AM_RESULT" | python3 -m json.tool > "${OUTDIR}/grafana/alertmanager_alerts.json"
    ok "저장 완료 (SSH)"
  else
    fail "[2-3]"
  fi
fi

echo ""
echo "━━━ 수집 완료 ━━━"
find "${OUTDIR}" -type f -name "*.json" | sort | while read f; do
  SIZE=$(wc -c < "$f" | tr -d ' ')
  if [ "$SIZE" -gt 500 ]; then
    echo "  ✓ $(basename "$f") (${SIZE} bytes)"
  else
    echo "  △ $(basename "$f") (${SIZE} bytes — 데이터 부족 또는 인증 필요)"
  fi
done
