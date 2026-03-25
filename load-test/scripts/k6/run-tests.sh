#!/usr/bin/env bash
set -euo pipefail

# ── 설정 ────────────────────────────────────────────────────
SCG_BASE_URL="${SCG_BASE_URL:-http://192.168.124.100:8090}"
DIRECT_BASE_URL="${DIRECT_BASE_URL:-http://192.168.124.100:8082}"
INFLUXDB_URL="${INFLUXDB_URL:-http://192.168.124.100:8086/k6}"
STAGING_SSH="${STAGING_SSH:-dktlfem@192.168.124.100}"
RUN_DATE="$(date +%Y-%m-%d)"
RESULT_DIR="${RESULT_DIR:-results/${RUN_DATE}}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCENARIO_DIR="${SCRIPT_DIR}/scenarios"
TOOLS_DIR="${SCRIPT_DIR}/tools"
SOAK_DURATION="${SOAK_DURATION:-10m}"

# ── 색상 ────────────────────────────────────────────────────
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

# ── 실행 모드 ────────────────────────────────────────────────
# auto:   수동 개입 불필요한 시나리오만 (1, 4, 5, 7)
# manual: 수동 개입 필요한 시나리오 포함 (2, 3)
# soak:   장시간 안정성 테스트 (6)
# all:    전체
# 개별:   scenario 번호 지정 (예: ./run-tests.sh 1 4 5)
MODE="${1:-auto}"

# ── 함수 ────────────────────────────────────────────────────
log_info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_err()   { echo -e "${RED}[ERROR]${NC} $*"; }

preflight_check() {
  log_info "사전 점검 시작..."

  if ! command -v k6 &>/dev/null; then
    log_err "k6가 설치되어 있지 않습니다."
    echo "  macOS: brew install grafana/k6/k6"
    exit 1
  fi
  log_ok "k6 $(k6 version 2>/dev/null | head -1)"

  local scg_status
  scg_status=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 "${SCG_BASE_URL}/actuator/health" 2>/dev/null || echo "000")
  if [[ "$scg_status" == "000" ]]; then
    log_err "SCG 연결 실패: ${SCG_BASE_URL}"
    echo "  VPN 연결 여부와 scg-app 실행 상태를 확인하세요."
    exit 1
  fi
  log_ok "SCG 연결 확인 (HTTP ${scg_status})"

  local influx_status
  influx_status=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 "http://192.168.124.100:8086/ping" 2>/dev/null || echo "000")
  if [[ "$influx_status" == "000" ]]; then
    log_warn "InfluxDB 연결 실패 — --out influxdb 비활성화"
    INFLUX_OUT=""
  else
    log_ok "InfluxDB 연결 확인"
    INFLUX_OUT="--out influxdb=${INFLUXDB_URL}"
  fi

  mkdir -p "${RESULT_DIR}/html" "${RESULT_DIR}/json" "${RESULT_DIR}/csv"
  log_ok "결과 폴더: ${RESULT_DIR}/{html,json,csv}/"
  echo ""
}

run_scenario() {
  local script="$1"
  local testid="$2"
  local index="$3"
  local total="$4"
  shift 4
  local extra_envs=()
  if [[ $# -gt 0 ]]; then
    extra_envs=("$@")
  fi

  if [[ ! -f "${SCENARIO_DIR}/${script}" ]]; then
    log_warn "스크립트 없음: ${script} (건너뜀)"
    return 0
  fi

  echo ""
  echo "╔══════════════════════════════════════════════════════╗"
  echo -e "║  ${CYAN}[${index}/${total}] ${testid}${NC}"
  echo "╠══════════════════════════════════════════════════════╣"
  echo "║  스크립트: ${script}"
  echo "║  SCG:      ${SCG_BASE_URL}"
  echo "╚══════════════════════════════════════════════════════╝"
  echo ""

  local env_args=(
    --env "SCG_BASE_URL=${SCG_BASE_URL}"
    --env "RESULT_DIR=${RESULT_DIR}"
  )
  if [[ ${#extra_envs[@]} -gt 0 ]]; then
    for e in "${extra_envs[@]}"; do
      env_args+=(--env "$e")
    done
  fi

  local start_ts
  start_ts=$(date +%s)

  k6 run \
    --tag testid="${testid}" \
    --tag run_date="${RUN_DATE}" \
    "${env_args[@]}" \
    ${INFLUX_OUT:-} \
    "${SCENARIO_DIR}/${script}" || {
      log_err "${testid} 실행 실패 (exit code: $?)"
      return 1
    }

  local end_ts elapsed
  end_ts=$(date +%s)
  elapsed=$((end_ts - start_ts))
  log_ok "${testid} 완료 (${elapsed}초)"
  echo ""
}

wait_for_manual_action() {
  local action="$1"
  echo ""
  echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo -e "${YELLOW}  수동 작업 필요:${NC}"
  echo -e "  ${action}"
  echo ""
  echo -e "  스테이징 서버 접속:"
  echo -e "    ssh ${STAGING_SSH}"
  echo -e "${YELLOW}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
  echo ""
  read -rp "  준비 완료 후 Enter를 누르세요... "
  echo ""
}

# ── 시나리오 실행 함수 ────────────────────────────────────────

run_s1() {
  run_scenario "scenario1-rate-limiter.js" "scenario1-rate-limiter" "$1" "$2"
}

run_s2() {
  echo -e "${YELLOW}[주의] scenario2는 테스트 중 수동 개입이 필요합니다.${NC}"
  echo ""
  echo "  타이밍:"
  echo "    테스트 시작 15초 후 → ssh에서: cd ~/devops_lab && docker compose stop payment-app"
  echo "    테스트 시작 75초 후 → ssh에서: cd ~/devops_lab && docker compose start payment-app"
  echo ""
  read -rp "  이해했으면 Enter를 눌러 테스트를 시작하세요... "
  run_scenario "scenario2-circuit-breaker.js" "scenario2-circuit-breaker" "$1" "$2"
}

run_s3() {
  echo -e "${YELLOW}[주의] scenario3는 테스트 중 수동 개입이 필요합니다.${NC}"
  echo ""
  echo "  타이밍:"
  echo "    테스트 시작 15초 후 → ssh에서: cd ~/devops_lab && docker compose stop payment-app"
  echo "    테스트 시작 60초 후 → ssh에서: cd ~/devops_lab && docker compose start payment-app"
  echo ""
  read -rp "  이해했으면 Enter를 눌러 테스트를 시작하세요... "
  run_scenario "scenario3-bulkhead.js" "scenario3-bulkhead" "$1" "$2"
}

run_s4() {
  log_info "scenario4: concert-app 직접 접근 확인 중..."
  local direct_status
  direct_status=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 "${DIRECT_BASE_URL}/api/v1/events" 2>/dev/null || echo "000")
  if [[ "$direct_status" == "000" ]]; then
    log_err "concert-app 직접 연결 실패: ${DIRECT_BASE_URL}"
    echo "  docker compose port concert-app 8080 으로 호스트 포트를 확인하세요."
    echo "  포트가 노출되지 않았다면 docker-compose.yml에서 ports 설정이 필요합니다."
    return 1
  fi
  log_ok "concert-app 직접 연결 확인 (HTTP ${direct_status})"
  run_scenario "scenario4-filter-latency.js" "scenario4-filter-latency" "$1" "$2" \
    "DIRECT_BASE_URL=${DIRECT_BASE_URL}"
}

run_s5() {
  run_scenario "scenario5-jwt-attack.js" "scenario5-jwt-attack" "$1" "$2"
}

run_s6() {
  log_info "scenario6: Soak 테스트 (${SOAK_DURATION})"
  run_scenario "scenario6-soak.js" "scenario6-soak" "$1" "$2" \
    "DURATION=${SOAK_DURATION}"
}

run_s7() {
  run_scenario "scenario7-internal-block.js" "scenario7-internal-block" "$1" "$2"
}

# ── 메인 ────────────────────────────────────────────────────

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║        SCG Hardening — k6 부하테스트 통합 실행        ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║  모드:      ${MODE}"
echo "║  SCG:       ${SCG_BASE_URL}"
echo "║  InfluxDB:  ${INFLUXDB_URL}"
echo "║  run_date:  ${RUN_DATE}"
echo "║  결과 폴더: ${RESULT_DIR}"
echo "╚══════════════════════════════════════════════════════╝"
echo ""

preflight_check

OVERALL_START=$(date +%s)
PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0

run_and_track() {
  local fn="$1"
  local idx="$2"
  local total="$3"
  if $fn "$idx" "$total"; then
    PASS_COUNT=$((PASS_COUNT + 1))
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
  fi
}

case "${MODE}" in
  auto)
    log_info "자동 실행 모드: 수동 개입 불필요 시나리오 (1, 4, 5, 7)"
    TOTAL=4
    run_and_track run_s1 1 $TOTAL
    run_and_track run_s4 2 $TOTAL
    run_and_track run_s5 3 $TOTAL
    run_and_track run_s7 4 $TOTAL
    ;;

  manual)
    log_info "수동 개입 포함 시나리오 (2, 3)"
    log_warn "스테이징 서버 SSH 세션을 미리 준비하세요: ssh ${STAGING_SSH}"
    echo ""
    TOTAL=2
    run_and_track run_s2 1 $TOTAL
    if [[ $FAIL_COUNT -eq 0 ]]; then
      log_info "payment-app이 다시 실행 중인지 확인합니다..."
      sleep 5
    fi
    run_and_track run_s3 2 $TOTAL
    ;;

  soak)
    log_info "Soak 테스트 모드 (${SOAK_DURATION})"
    TOTAL=1
    run_and_track run_s6 1 $TOTAL
    ;;

  all)
    log_info "전체 시나리오 실행 (1→5→7→4→2→3→6 순서)"
    log_warn "시나리오 2, 3은 수동 개입이 필요합니다."
    log_warn "시나리오 6(soak)은 ${SOAK_DURATION} 소요됩니다."
    echo ""
    TOTAL=7
    run_and_track run_s1 1 $TOTAL
    run_and_track run_s5 2 $TOTAL
    run_and_track run_s7 3 $TOTAL
    run_and_track run_s4 4 $TOTAL
    run_and_track run_s2 5 $TOTAL
    if [[ $FAIL_COUNT -lt 5 ]]; then
      log_info "payment-app 복구 대기 (10초)..."
      sleep 10
    fi
    run_and_track run_s3 6 $TOTAL
    run_and_track run_s6 7 $TOTAL
    ;;

  [1-7])
    log_info "단일 시나리오 실행: scenario${MODE}"
    TOTAL=1
    run_and_track "run_s${MODE}" 1 $TOTAL
    ;;

  *)
    echo "사용법: $0 [auto|manual|soak|all|1-7]"
    echo ""
    echo "  auto    수동 개입 불필요 시나리오 (1, 4, 5, 7)  [기본값]"
    echo "  manual  수동 개입 필요 시나리오 (2, 3)"
    echo "  soak    장시간 안정성 테스트 (6)"
    echo "  all     전체 시나리오 순차 실행"
    echo "  1-7     개별 시나리오 실행"
    echo ""
    echo "환경 변수:"
    echo "  SCG_BASE_URL      SCG 주소 (기본: http://192.168.124.100:8090)"
    echo "  DIRECT_BASE_URL   concert-app 직접 주소 (기본: http://192.168.124.100:8082)"
    echo "  INFLUXDB_URL      InfluxDB 주소 (기본: http://192.168.124.100:8086/k6)"
    echo "  SOAK_DURATION     soak 테스트 시간 (기본: 10m)"
    echo "  RESULT_DIR        결과 저장 경로 (기본: results/YYYY-MM-DD)"
    echo ""
    echo "예시:"
    echo "  ./run-tests.sh auto                    자동 시나리오만"
    echo "  ./run-tests.sh all                     전체 실행"
    echo "  ./run-tests.sh 1                       scenario1만"
    echo "  SOAK_DURATION=30m ./run-tests.sh soak  30분 soak"
    exit 0
    ;;
esac

# ── Excel 요약 생성 ─────────────────────────────────────────
echo ""
if [[ -d "${RESULT_DIR}/json" ]] && ls "${RESULT_DIR}/json/"*.json 1>/dev/null 2>&1; then
  echo "====== Excel 요약 생성 ======"
  if command -v python3 &>/dev/null; then
    python3 "${TOOLS_DIR}/generate-summary.py" "${RESULT_DIR}" || log_warn "Excel 요약 생성 실패"
  else
    log_warn "python3 미설치 — Excel 요약 생성 건너뜀"
  fi
fi

# ── 결과 요약 ───────────────────────────────────────────────
OVERALL_END=$(date +%s)
OVERALL_ELAPSED=$((OVERALL_END - OVERALL_START))
MINUTES=$((OVERALL_ELAPSED / 60))
SECONDS_REMAIN=$((OVERALL_ELAPSED % 60))

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║                   실행 결과 요약                      ║"
echo "╠══════════════════════════════════════════════════════╣"
echo -e "║  ${GREEN}성공: ${PASS_COUNT}${NC}  ${RED}실패: ${FAIL_COUNT}${NC}"
echo "║  총 소요: ${MINUTES}분 ${SECONDS_REMAIN}초"
echo "║  결과:    ${RESULT_DIR}/"
echo "╠══════════════════════════════════════════════════════╣"
echo "║  다음 단계:"
echo "║  1. Grafana:  http://192.168.124.100:8080/grafana/"
echo "║     → testid 드롭다운에서 시나리오 선택"
echo "║  2. Jaeger:   http://192.168.124.100:8080/jaeger/"
echo "║     → 느린 트레이스 확인"
echo "║  3. perf-analyzer 분석:"
echo "║     cd ../../perf-analyzer"
echo "║     PYTHONPATH=. python3 -m perf_analyzer.cli report \\"
echo "║       ../load-test/scripts/k6/results -o ./output"
echo "╚══════════════════════════════════════════════════════╝"
