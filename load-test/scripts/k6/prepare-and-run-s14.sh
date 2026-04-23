#!/usr/bin/env bash
set -uo pipefail

# ══════════════════════════════════════════════════════════════════════
# prepare-and-run-s14.sh
# Scenario 14 (좌석/예약 정합성) — 드라이런(VU=3) + 본실험(VU=20)
#
# 실행 흐름:
#   1. SSH 연결 확인
#   2. DB에서 AVAILABLE 좌석 자동 탐지
#   3. SQL_INIT 실행 (좌석 초기화 + 대기열 토큰 사전 주입)
#   4. k6 드라이런 (VU=3)
#   5. SQL_VERIFY (불변식 검증)
#   6. Observability 수집 (ES, Prometheus)
#   7. 드라이런 PASS 시 본실험 여부 확인 (VU=20)
#   8. 본실험 실행 및 결과 수집
#   9. SQL_CLEANUP
#
# 사용법:
#   ./prepare-and-run-s14.sh                  # 드라이런 → 본실험 (자동 좌석 탐지)
#   ./prepare-and-run-s14.sh --dry-run-only   # 드라이런만
#   TARGET_SEAT_ID=11 ./prepare-and-run-s14.sh  # 좌석 직접 지정
#
# 필요 환경:
#   - OpenVPN 연결 상태 (192.168.124.100 접근)
#   - k6 설치 (brew install grafana/k6/k6)
#   - SSH alias: dktlfem_home → dktlfem@192.168.124.100
# ══════════════════════════════════════════════════════════════════════

# ── 설정 ──────────────────────────────────────────────────────────────
STAGING_SSH="${STAGING_SSH:-dktlfem_home}"
SSH_CMD="${SSH_CMD:-ssh}"   # sshpass 우회 시: SSH_CMD="sshpass -e ssh"
COMPOSE_DIR="${COMPOSE_DIR:-devops_lab}"
SCG_BASE_URL="${SCG_BASE_URL:-http://192.168.124.100:8090}"
ES_URL="${ES_URL:-http://192.168.124.100:9200}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_DATE="$(date +%Y-%m-%d)"
RESULT_DIR="${RESULT_DIR:-${SCRIPT_DIR}/results/${RUN_DATE}}"
DRY_VUS=3
MAIN_VUS=20
TARGET_EVENT_ID="${TARGET_EVENT_ID:-1}"
TARGET_SEAT_ID="${TARGET_SEAT_ID:-}"   # 미지정 시 DB 자동 탐지
MODE="${1:---all}"

# ── 색상 ──────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'

log_info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_err()   { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()  { echo ""; echo -e "${BOLD}${CYAN}━━━ $* ━━━${NC}"; echo ""; }

mysql_exec() {
    # 스테이징 서버의 MySQL에서 쿼리 실행
    # 인수: <db_name> <query>
    # ADR: SQL을 stdin으로 전달 → Windows SSH→WSL2 경유 시 따옴표 중첩 문제 방지
    #      docker compose exec -i (interactive) 로 stdin 수신
    echo "${2}" | ${SSH_CMD} "${STAGING_SSH}" \
        "wsl bash -c \"cd ~/${COMPOSE_DIR} && docker compose exec -i mysql mysql -uroot -p1234 ${1} -N -s\"" 2>/dev/null
}

mysql_exec_verbose() {
    # 결과를 표 형식으로 출력
    echo "${2}" | ${SSH_CMD} "${STAGING_SSH}" \
        "wsl bash -c \"cd ~/${COMPOSE_DIR} && docker compose exec -i mysql mysql -uroot -p1234 ${1}\"" 2>/dev/null
}

# ── 결과 디렉토리 생성 ─────────────────────────────────────────────────
mkdir -p "${RESULT_DIR}/json" \
         "${RESULT_DIR}/observability/elasticsearch" \
         "${RESULT_DIR}/observability/prometheus" \
         "${RESULT_DIR}/logs"

# ══════════════════════════════════════════════════════════════════════
# STEP 1: 사전 점검
# ══════════════════════════════════════════════════════════════════════
preflight() {
    log_step "STEP 1: 사전 점검"

    # k6 설치 확인
    if ! command -v k6 &>/dev/null; then
        log_err "k6가 설치되지 않았습니다."
        echo "  설치: brew install grafana/k6/k6"
        exit 1
    fi
    log_ok "k6 $(k6 version 2>/dev/null | head -1)"

    # SSH 연결 확인
    log_info "스테이징 서버 SSH 연결 확인 (${STAGING_SSH})..."
    if ! ${SSH_CMD} -o ConnectTimeout=5 "${STAGING_SSH}" "echo ok" &>/dev/null; then
        log_err "SSH 연결 실패. OpenVPN 연결 상태를 확인하세요."
        exit 1
    fi
    log_ok "SSH 연결 성공"

    # SCG 연결 확인
    local scg_status
    scg_status=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 \
                 "${SCG_BASE_URL}/actuator/health" 2>/dev/null || echo "000")
    if [[ "${scg_status}" == "000" ]]; then
        log_err "SCG 연결 실패: ${SCG_BASE_URL}"
        exit 1
    fi
    log_ok "SCG 연결 확인 (HTTP ${scg_status})"

    # ES 연결 확인
    local es_status
    es_status=$(curl -s -o /dev/null -w '%{http_code}' --connect-timeout 5 \
                "${ES_URL}/_cluster/health" 2>/dev/null || echo "000")
    if [[ "${es_status}" == "000" ]]; then
        log_warn "Elasticsearch 접근 불가 (${ES_URL}) — Observability 수집 제한됨"
    else
        log_ok "Elasticsearch 연결 확인 (HTTP ${es_status})"
    fi

    # booking-app / concert-app 서비스 상태 확인
    local ps_out
    ps_out=$(${SSH_CMD} "${STAGING_SSH}" "wsl bash -c \"cd ~/${COMPOSE_DIR} && docker compose ps\"" 2>/dev/null)
    for svc in "scg-app" "booking-app" "concert-app" "waitingroom-app"; do
        if echo "${ps_out}" | grep -q "${svc}"; then
            if echo "${ps_out}" | grep "${svc}" | grep -qi "up\|running"; then
                log_ok "${svc}: running"
            else
                log_warn "${svc}: 컨테이너 있으나 running 상태 아님"
            fi
        else
            log_warn "${svc}: 목록에서 확인 불가 (컨테이너명 확인 필요)"
        fi
    done
}

# ══════════════════════════════════════════════════════════════════════
# STEP 2: TARGET_SEAT_ID 자동 탐지
# ══════════════════════════════════════════════════════════════════════
detect_seat() {
    log_step "STEP 2: TARGET_SEAT_ID 탐지"

    if [[ -n "${TARGET_SEAT_ID}" ]]; then
        log_info "환경변수 지정 좌석 사용: seat_id=${TARGET_SEAT_ID}"
        # 지정된 좌석 존재 여부 확인
        local seat_info
        seat_info=$(mysql_exec ticketing_concert \
            "SELECT seat_id, status, version FROM seats WHERE seat_id=${TARGET_SEAT_ID};")
        if [[ -z "${seat_info}" ]]; then
            log_err "seat_id=${TARGET_SEAT_ID} 가 ticketing_concert.seats에 존재하지 않습니다."
            exit 1
        fi
        log_ok "좌석 확인: ${seat_info}"
        return
    fi

    # AVAILABLE 좌석 자동 탐지 (테스트 외 userId가 쓰지 않는 좌석)
    log_info "AVAILABLE 좌석 탐지 중 (ticketing_concert.seats)..."
    local auto_seat
    auto_seat=$(mysql_exec ticketing_concert \
        "SELECT seat_id FROM seats WHERE status='AVAILABLE' LIMIT 1;" | tr -d '[:space:]')

    if [[ -z "${auto_seat}" ]]; then
        log_err "AVAILABLE 좌석이 없습니다. 모든 좌석이 HOLD/SOLD 상태입니다."
        log_err "아래 SQL로 임의 좌석을 초기화하거나 TARGET_SEAT_ID를 직접 지정하세요:"
        echo "  UPDATE ticketing_concert.seats SET status='AVAILABLE', version=0"
        echo "  WHERE seat_id = (SELECT MIN(seat_id) FROM seats);"
        exit 1
    fi

    TARGET_SEAT_ID="${auto_seat}"
    log_ok "자동 탐지 완료: TARGET_SEAT_ID=${TARGET_SEAT_ID}"

    # 해당 좌석의 schedule_id 확인
    local seat_detail
    seat_detail=$(mysql_exec ticketing_concert \
        "SELECT seat_id, schedule_id, seat_no, status, version FROM seats WHERE seat_id=${TARGET_SEAT_ID};")
    log_info "좌석 상세: ${seat_detail}"
}

# ══════════════════════════════════════════════════════════════════════
# STEP 3: SQL_INIT (초기화)
# ══════════════════════════════════════════════════════════════════════
run_sql_init() {
    local vus=$1
    log_step "STEP 3: SQL_INIT (seat_id=${TARGET_SEAT_ID}, vus=${vus})"

    # 3-1. 좌석 상태 초기화
    log_info "[concert DB] seat_id=${TARGET_SEAT_ID} → AVAILABLE/version=0 초기화..."
    mysql_exec ticketing_concert \
        "UPDATE seats SET status='AVAILABLE', version=0, update_at=NOW() WHERE seat_id=${TARGET_SEAT_ID};"

    local after
    after=$(mysql_exec ticketing_concert \
        "SELECT seat_id, status, version FROM seats WHERE seat_id=${TARGET_SEAT_ID};")
    log_ok "좌석 초기화 후: ${after}"

    # 3-2. 이전 테스트 예약 정리
    log_info "[booking DB] seat_id=${TARGET_SEAT_ID} 이전 예약 삭제..."
    local deleted_cnt
    deleted_cnt=$(mysql_exec ticketing_booking \
        "SELECT COUNT(*) FROM reservations WHERE seat_id=${TARGET_SEAT_ID}" | tr -d '[:space:]')
    if [[ "${deleted_cnt}" -gt 0 ]]; then
        mysql_exec ticketing_booking \
            "DELETE FROM reservations WHERE seat_id=${TARGET_SEAT_ID};"
        log_ok "이전 예약 ${deleted_cnt}건 삭제 완료"
    else
        log_ok "삭제할 이전 예약 없음"
    fi

    # 3-3. 대기열 토큰 사전 주입 (userId 1 ~ vus)
    log_info "[waitingroom DB] 대기열 토큰 주입 (userId 1~${vus}, eventId=${TARGET_EVENT_ID})..."

    # 토큰 주입: WITH RECURSIVE 방식
    # ADR: INTERVAL 1 DAY 사용 이유
    #   MySQL(Docker)은 UTC, Spring Boot JVM은 KST(UTC+9) 기준 LocalDateTime.now() 반환.
    #   INTERVAL 3 HOUR(UTC) → expired_at=05:29 UTC → Java KST 11:29과 비교 시 EXPIRED 판정.
    #   24시간으로 설정 시 UTC 익일 02:29 → KST 11:29보다 충분히 커서 VALID 보장.
    mysql_exec ticketing_waitingroom \
        "INSERT INTO active_tokens (token_id, user_id, event_id, status, issued_at, expired_at)
         SELECT
           CONCAT(LPAD(n, 8, '0'), '-0000-4000-8000-', LPAD(n, 12, '0')),
           n, ${TARGET_EVENT_ID}, 'ACTIVE', NOW(), DATE_ADD(NOW(), INTERVAL 1 DAY)
         FROM (
           WITH RECURSIVE nums AS (SELECT 1 AS n UNION ALL SELECT n+1 FROM nums WHERE n < ${vus})
           SELECT n FROM nums
         ) t
         ON DUPLICATE KEY UPDATE status='ACTIVE', expired_at=DATE_ADD(NOW(), INTERVAL 1 DAY);"

    # Redis 분산락 잔여 키 정리 (이전 실행 잔여 락)
    log_info "[Redis] reservation:lock:seat:${TARGET_SEAT_ID} 잔여 락 정리..."
    ${SSH_CMD} "${STAGING_SSH}" \
        "wsl bash -c \"cd ~/${COMPOSE_DIR} && docker compose exec -T redis redis-cli DEL reservation:lock:seat:${TARGET_SEAT_ID}\"" 2>/dev/null | tr -d '[:space:]'

    # 3-4. 최종 확인
    log_info "[최종 확인] 초기화 결과..."
    echo ""
    echo "  ▶ 좌석 상태 (ticketing_concert.seats):"
    mysql_exec_verbose ticketing_concert \
        "SELECT seat_id, status, version FROM seats WHERE seat_id=${TARGET_SEAT_ID};"
    echo ""
    echo "  ▶ 대기열 토큰 수 (ticketing_waitingroom.active_tokens):"
    mysql_exec_verbose ticketing_waitingroom \
        "SELECT COUNT(*) AS token_count FROM active_tokens
         WHERE user_id BETWEEN 1 AND ${vus} AND event_id=${TARGET_EVENT_ID} AND status='ACTIVE';"
    echo ""
    log_ok "SQL_INIT 완료"
}

# ══════════════════════════════════════════════════════════════════════
# STEP 4: k6 실행
# ══════════════════════════════════════════════════════════════════════
run_k6() {
    local vus=$1
    local label=$2   # "dryrun" or "main"
    log_step "STEP 4: k6 실행 [${label}] VU=${vus}"

    local run_tag
    run_tag="$(date +%Y%m%d-%H%M%S)"
    local json_out="${RESULT_DIR}/json/scenario14-seat-consistency_${run_tag}_raw.json.gz"
    local log_file="${RESULT_DIR}/logs/s14_${label}_${run_tag}.log"

    echo "  SCG:       ${SCG_BASE_URL}"
    echo "  SEAT_ID:   ${TARGET_SEAT_ID}"
    echo "  VUS:       ${vus}"
    echo "  결과파일:  ${json_out}"
    echo ""

    k6 run \
        --tag testid="scenario14-seat-consistency" \
        --tag run_date="${RUN_DATE}" \
        --tag run_id="${run_tag}" \
        --tag phase="${label}" \
        --out "json=${json_out}" \
        --env "SCG_BASE_URL=${SCG_BASE_URL}" \
        --env "TARGET_SEAT_ID=${TARGET_SEAT_ID}" \
        --env "TARGET_EVENT_ID=${TARGET_EVENT_ID}" \
        --env "CONTENTION_VUS=${vus}" \
        --env "RESULT_DIR=${RESULT_DIR}" \
        "${SCRIPT_DIR}/scenarios/scenario14-seat-consistency.js" 2>&1 | tee "${log_file}"

    local k6_exit=${PIPESTATUS[0]}
    if [[ ${k6_exit} -eq 0 ]]; then
        log_ok "k6 [${label}] 완료 (run_id=${run_tag})"
    else
        log_warn "k6 [${label}] 종료코드=${k6_exit} (threshold 확인 필요)"
    fi

    # run_tag를 파일에 저장해서 이후 단계에서 참조 가능하게
    echo "${run_tag}" > "${RESULT_DIR}/logs/last_run_tag_${label}.txt"
    return ${k6_exit}
}

# ══════════════════════════════════════════════════════════════════════
# STEP 5: SQL_VERIFY (불변식 검증)
# ══════════════════════════════════════════════════════════════════════
run_sql_verify() {
    local phase=$1
    log_step "STEP 5: SQL_VERIFY [${phase}] (seat_id=${TARGET_SEAT_ID})"

    echo "━━━ [불변식 1] 예약 row 중복 여부 ━━━"
    mysql_exec_verbose ticketing_booking \
        "SELECT COUNT(*) AS reservation_count,
                GROUP_CONCAT(status ORDER BY reservation_id) AS statuses,
                CASE
                  WHEN COUNT(*) = 0 THEN 'WARN: 예약 없음 (성공 0건)'
                  WHEN COUNT(*) = 1 THEN 'PASS: 정확히 1건'
                  ELSE                   'FAIL: oversell 발생!'
                END AS invariant_1
         FROM ticketing_booking.reservations
         WHERE seat_id = ${TARGET_SEAT_ID};"

    echo ""
    echo "━━━ [불변식 2] 좌석 상태/버전 ━━━"
    mysql_exec_verbose ticketing_concert \
        "SELECT seat_id, status, version,
                CASE
                  WHEN status='HOLD' AND version=1 THEN 'PASS: HOLD/version=1'
                  WHEN status='HOLD' AND version>1 THEN CONCAT('FAIL: version=', version, ' (중복 전이)')
                  WHEN status='AVAILABLE'           THEN 'WARN: AVAILABLE (성공 0건)'
                  ELSE                                   CONCAT('FAIL: 예상 외 상태=', status)
                END AS invariant_2
         FROM ticketing_concert.seats
         WHERE seat_id = ${TARGET_SEAT_ID};"

    echo ""
    echo "━━━ [종합 판정] ━━━"
    local verdict
    verdict=$(mysql_exec ticketing_booking \
        "SELECT
           CASE
             WHEN (SELECT COUNT(*) FROM ticketing_booking.reservations WHERE seat_id=${TARGET_SEAT_ID}) > 1
               THEN 'FAIL: [불변식1] oversell'
             WHEN (SELECT status FROM ticketing_concert.seats WHERE seat_id=${TARGET_SEAT_ID}) != 'HOLD'
               THEN 'WARN: [불변식2] HOLD 아님'
             WHEN (SELECT version FROM ticketing_concert.seats WHERE seat_id=${TARGET_SEAT_ID}) != 1
               THEN 'FAIL: [불변식2] version != 1'
             ELSE 'PASS: 모든 불변식 충족'
           END AS final_verdict;" 2>/dev/null | tr -d '[:space:]')

    echo ""
    if echo "${verdict}" | grep -q "^PASS"; then
        log_ok "불변식 판정: ${verdict}"
    elif echo "${verdict}" | grep -q "^WARN"; then
        log_warn "불변식 판정: ${verdict}"
    else
        log_err "불변식 판정: ${verdict}"
    fi

    # 예약 생성자 확인 (누가 이겼는지)
    echo ""
    echo "━━━ [참고] 예약 생성 userId ━━━"
    mysql_exec_verbose ticketing_booking \
        "SELECT reservation_id, user_id, seat_id, status, reserved_at
         FROM reservations WHERE seat_id=${TARGET_SEAT_ID}
         ORDER BY reservation_id DESC LIMIT 3;"

    # 판정 결과를 변수로 반환
    echo "${verdict}"
}

# ══════════════════════════════════════════════════════════════════════
# STEP 6: Observability 수집
# ══════════════════════════════════════════════════════════════════════
collect_observability() {
    local phase=$1
    local start_ts=$2
    local end_ts=$3
    log_step "STEP 6: Observability 수집 [${phase}]"

    local obs_dir="${RESULT_DIR}/observability"
    local es_dir="${obs_dir}/elasticsearch"
    local prom_dir="${obs_dir}/prometheus"
    mkdir -p "${es_dir}" "${prom_dir}"

    # ── Elasticsearch ─────────────────────────────────────────────
    log_info "[ES] booking-app 로그 수집 (${start_ts} ~ ${end_ts})..."

    # [1] 좌석 점유 성공 로그
    curl -s -X POST "${ES_URL}/filebeat-*/_search" \
         -H 'Content-Type: application/json' \
         -d "{
           \"size\": 10,
           \"query\": {
             \"bool\": {
               \"must\": [
                 {\"range\": {\"@timestamp\": {\"gte\": \"${start_ts}\", \"lte\": \"${end_ts}\"}}},
                 {\"match_phrase\": {\"message\": \"좌석 점유 성공\"}}
               ]
             }
           },
           \"sort\": [{\"@timestamp\": {\"order\": \"asc\"}}]
         }" > "${es_dir}/s14_${phase}_hold_success.json" 2>/dev/null

    local success_cnt
    success_cnt=$(python3 -c "import json,sys; d=json.load(open('${es_dir}/s14_${phase}_hold_success.json')); print(d.get('hits',{}).get('total',{}).get('value',0))" 2>/dev/null || echo "0")
    log_ok "[ES] 좌석 점유 성공 로그: ${success_cnt}건 → s14_${phase}_hold_success.json"

    # [2] 동시성 충돌 로그 (낙관적락)
    curl -s -X POST "${ES_URL}/filebeat-*/_search" \
         -H 'Content-Type: application/json' \
         -d "{
           \"size\": 10,
           \"query\": {
             \"bool\": {
               \"must\": [
                 {\"range\": {\"@timestamp\": {\"gte\": \"${start_ts}\", \"lte\": \"${end_ts}\"}}},
                 {\"match_phrase\": {\"message\": \"동시성 충돌 발생\"}}
               ]
             }
           }
         }" > "${es_dir}/s14_${phase}_conflict.json" 2>/dev/null

    local conflict_cnt
    conflict_cnt=$(python3 -c "import json,sys; d=json.load(open('${es_dir}/s14_${phase}_conflict.json')); print(d.get('hits',{}).get('total',{}).get('value',0))" 2>/dev/null || echo "0")
    log_ok "[ES] 동시성 충돌 로그: ${conflict_cnt}건 → s14_${phase}_conflict.json"

    # [3] 분산락 타임아웃 로그 (R003)
    curl -s -X POST "${ES_URL}/filebeat-*/_search" \
         -H 'Content-Type: application/json' \
         -d "{
           \"size\": 10,
           \"query\": {
             \"bool\": {
               \"must\": [
                 {\"range\": {\"@timestamp\": {\"gte\": \"${start_ts}\", \"lte\": \"${end_ts}\"}}},
                 {\"match_phrase\": {\"message\": \"R003\"}}
               ]
             }
           }
         }" > "${es_dir}/s14_${phase}_r003.json" 2>/dev/null

    local r003_cnt
    r003_cnt=$(python3 -c "import json,sys; d=json.load(open('${es_dir}/s14_${phase}_r003.json')); print(d.get('hits',{}).get('total',{}).get('value',0))" 2>/dev/null || echo "0")
    log_ok "[ES] 분산락(R003) 로그: ${r003_cnt}건 → s14_${phase}_r003.json"

    # 수집 결과 일치 여부 출력
    if [[ "${success_cnt}" -eq 0 && "${conflict_cnt}" -eq 0 && "${r003_cnt}" -eq 0 ]]; then
        log_warn "[ES] 3개 패턴 모두 0건 — Filebeat 수집 경로 확인 필요"
        log_warn "  booking-app 로그가 Filebeat → ES로 수집되고 있는지 docker logs filebeat 확인"
    fi

    # ── Prometheus ────────────────────────────────────────────────
    log_info "[Prometheus] HTTP status 분포 수집..."

    # booking-service HTTP status 카운트 (테스트 시간대)
    ${SSH_CMD} "${STAGING_SSH}" \
        "wsl bash -c \"cd ~/${COMPOSE_DIR} && docker exec prometheus wget -qO- 'http://localhost:9090/prometheus/api/v1/query?query=increase(http_server_requests_seconds_count%7Bapplication%3D%22booking-service%22%7D%5B5m%5D)&time=${end_ts}'\"" \
        2>/dev/null > "${prom_dir}/s14_${phase}_booking_status.json"

    local prom_ok
    prom_ok=$(python3 -c "import json; d=json.load(open('${prom_dir}/s14_${phase}_booking_status.json')); print('ok' if d.get('status')=='success' else 'fail')" 2>/dev/null || echo "fail")

    if [[ "${prom_ok}" == "ok" ]]; then
        log_ok "[Prometheus] booking-service status 분포 수집 완료 → s14_${phase}_booking_status.json"
    else
        log_warn "[Prometheus] 수집 실패 — docker exec 경로 또는 레이블 확인 필요"
    fi

    # ── Jaeger ────────────────────────────────────────────────────
    log_info "[Jaeger] 대표 trace 확인 포인트..."
    echo ""
    echo "  ★ Jaeger UI에서 직접 확인:"
    echo "    URL: http://192.168.124.100:8080/jaeger/"
    echo "    Service: booking-app"
    echo "    시간대: ${start_ts} ~ ${end_ts}"
    echo ""
    echo "    수집 대상:"
    echo "    [성공 1건] booking-app → concert-app span 포함 trace"
    echo "    [실패 1건] booking-app에서 차단된 trace (R003 or S001)"

    echo ""
    log_ok "Observability 수집 완료 → ${obs_dir}/"
}

# ══════════════════════════════════════════════════════════════════════
# STEP 7: SQL_CLEANUP
# ══════════════════════════════════════════════════════════════════════
run_sql_cleanup() {
    log_step "STEP 7: SQL_CLEANUP"

    log_info "[booking DB] 테스트 예약 삭제..."
    mysql_exec ticketing_booking \
        "DELETE FROM reservations WHERE seat_id=${TARGET_SEAT_ID} AND status IN ('PENDING','CANCELLED');"

    log_info "[concert DB] 좌석 상태 원복 (AVAILABLE/version=0)..."
    mysql_exec ticketing_concert \
        "UPDATE seats SET status='AVAILABLE', version=0, update_at=NOW() WHERE seat_id=${TARGET_SEAT_ID};"

    log_info "[waitingroom DB] 토큰 USED 처리..."
    mysql_exec ticketing_waitingroom \
        "UPDATE active_tokens SET status='USED', expired_at=NOW()
         WHERE user_id BETWEEN 1 AND ${MAIN_VUS} AND event_id=${TARGET_EVENT_ID} AND status='ACTIVE';"

    log_info "[Redis] 분산락 잔여 키 정리..."
    ${SSH_CMD} "${STAGING_SSH}" \
        "wsl bash -c \"cd ~/${COMPOSE_DIR} && docker compose exec -T redis redis-cli DEL reservation:lock:seat:${TARGET_SEAT_ID}\"" 2>/dev/null

    echo ""
    echo "  ▶ 정리 후 좌석 상태:"
    mysql_exec_verbose ticketing_concert \
        "SELECT seat_id, status, version FROM seats WHERE seat_id=${TARGET_SEAT_ID};"
    log_ok "SQL_CLEANUP 완료"
}

# ══════════════════════════════════════════════════════════════════════
# 메인
# ══════════════════════════════════════════════════════════════════════
echo ""
echo "╔═══════════════════════════════════════════════════════════╗"
echo "║   Scenario 14 — 좌석/예약 정합성 검증                      ║"
echo "║   드라이런(VU=${DRY_VUS}) → 본실험(VU=${MAIN_VUS})              ║"
echo "╠═══════════════════════════════════════════════════════════╣"
echo "║  모드:     ${MODE}"
echo "║  SSH:      ${STAGING_SSH}"
echo "║  SCG:      ${SCG_BASE_URL}"
echo "║  결과:     ${RESULT_DIR}"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""

# ── 사전 점검 ──────────────────────────────────────────────────────
preflight
detect_seat

echo ""
log_info "확정된 파라미터:"
echo "  TARGET_SEAT_ID  = ${TARGET_SEAT_ID}"
echo "  TARGET_EVENT_ID = ${TARGET_EVENT_ID}"
echo "  DRY_RUN VUS     = ${DRY_VUS}"
echo "  MAIN VUS        = ${MAIN_VUS}"
echo ""
read -rp "  위 파라미터로 진행합니다. Enter를 누르세요 (Ctrl+C로 중단)... "

# ════════════════════════════════
# 드라이런
# ════════════════════════════════
echo ""
echo -e "${BOLD}${CYAN}══ [드라이런 시작] VU=${DRY_VUS} ══${NC}"
echo ""

run_sql_init "${DRY_VUS}"

DRY_START=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
run_k6 "${DRY_VUS}" "dryrun" || true   # threshold 실패해도 계속 진행 (SQL_VERIFY로 판정)
DRY_END=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

sleep 3  # 로그 Filebeat 수집 대기

DRYRUN_VERDICT=$(run_sql_verify "dryrun")
collect_observability "dryrun" "${DRY_START}" "${DRY_END}"

echo ""
echo "═══════════════════════════════════════════════"
echo -e "  드라이런 최종 판정: ${BOLD}${DRYRUN_VERDICT}${NC}"
echo "═══════════════════════════════════════════════"
echo ""

# 드라이런 결과에 따른 분기
if echo "${DRYRUN_VERDICT}" | grep -q "^FAIL"; then
    log_err "드라이런 FAIL — 본실험 중단"
    echo ""
    echo "  원인 분석 체크리스트:"
    echo "  1. SQL_VERIFY 결과 재확인"
    echo "  2. ES 로그 확인: ${RESULT_DIR}/observability/elasticsearch/"
    echo "  3. k6 로그 확인: ${RESULT_DIR}/logs/"
    run_sql_cleanup
    exit 1
fi

if [[ "${MODE}" == "--dry-run-only" ]]; then
    log_ok "드라이런 완료 (--dry-run-only 모드). 본실험은 별도 실행하세요."
    run_sql_cleanup
    exit 0
fi

# ════════════════════════════════
# 본실험
# ════════════════════════════════
echo ""
echo -e "${BOLD}${GREEN}══ [본실험 진행 여부] ══${NC}"
echo ""
echo "  드라이런 결과: ${DRYRUN_VERDICT}"
echo "  본실험 VU 수:  ${MAIN_VUS}"
echo ""
read -rp "  본실험(VU=${MAIN_VUS})을 진행하시겠습니까? [y/N]: " confirm_main
echo ""

if [[ "${confirm_main}" != "y" && "${confirm_main}" != "Y" ]]; then
    log_info "본실험 건너뜀. SQL_CLEANUP 실행..."
    run_sql_cleanup
    log_ok "완료. 결과 위치: ${RESULT_DIR}/"
    exit 0
fi

run_sql_init "${MAIN_VUS}"

MAIN_START=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
run_k6 "${MAIN_VUS}" "main" || true
MAIN_END=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

sleep 3

MAIN_VERDICT=$(run_sql_verify "main")
collect_observability "main" "${MAIN_START}" "${MAIN_END}"

# ── 최종 요약 ───────────────────────────────────────────────────
echo ""
echo "╔═══════════════════════════════════════════════════════════╗"
echo "║              Scenario 14 — 최종 결과                       ║"
echo "╠═══════════════════════════════════════════════════════════╣"
echo -e "║  드라이런 (VU=${DRY_VUS}):  ${DRYRUN_VERDICT}"
echo -e "║  본실험   (VU=${MAIN_VUS}): ${MAIN_VERDICT}"
echo "╠═══════════════════════════════════════════════════════════╣"
echo "║  결과 디렉토리: ${RESULT_DIR}"
echo "║  - k6 JSON:  ${RESULT_DIR}/json/"
echo "║  - ES 로그:  ${RESULT_DIR}/observability/elasticsearch/"
echo "║  - Prom:     ${RESULT_DIR}/observability/prometheus/"
echo "║  - k6 로그:  ${RESULT_DIR}/logs/"
echo "╠═══════════════════════════════════════════════════════════╣"
echo "║  Jaeger UI (수동 캡처 필요):"
echo "║    http://192.168.124.100:8080/jaeger/"
echo "║    Service: booking-app, 성공 1건 + 실패 1건 trace 캡처"
echo "╚═══════════════════════════════════════════════════════════╝"
echo ""

# ── 정리 ────────────────────────────────────────────────────────
if echo "${MAIN_VERDICT}" | grep -q "^PASS\|^WARN"; then
    log_info "정리 SQL 실행..."
    run_sql_cleanup
    log_ok "모든 단계 완료"
else
    log_warn "FAIL 판정 — 정리 전 수동 확인 권장"
    read -rp "  SQL_CLEANUP을 지금 실행하시겠습니까? [y/N]: " do_cleanup
    if [[ "${do_cleanup}" == "y" || "${do_cleanup}" == "Y" ]]; then
        run_sql_cleanup
    else
        log_warn "정리 보류. 필요 시 SQL_CLEANUP_s14.sql을 수동 실행하세요."
    fi
fi
