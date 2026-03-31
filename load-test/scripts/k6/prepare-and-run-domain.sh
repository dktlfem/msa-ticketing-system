#!/usr/bin/env bash
set -uo pipefail
# 참고: set -e 제외 — SSH 명령어 개별 실패 시에도 전체 스크립트 계속 진행

# ══════════════════════════════════════════════════════════════
# 도메인 시나리오 (8→9→10→11) 사전 데이터 초기화 + 순차 실행
#
# 사용법:
#   ./prepare-and-run-domain.sh              # 초기화 + 전체 실행
#   ./prepare-and-run-domain.sh --init-only  # 초기화만
#   ./prepare-and-run-domain.sh --run-only   # 실행만 (이미 초기화 완료된 경우)
#   ./prepare-and-run-domain.sh --scenario 8 # 특정 시나리오만
#
# 환경:
#   스테이징 서버: 192.168.124.100 (OpenVPN 필요)
#   MySQL:         docker compose exec mysql (root/1234)
#   Redis:         docker compose exec redis
# ══════════════════════════════════════════════════════════════

STAGING_SSH="${STAGING_SSH:-dktlfem_home}"
COMPOSE_DIR="${COMPOSE_DIR:-devops_lab}"   # 스테이징 서버 docker compose 경로 (~/ 기준)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_DATE="$(date +%Y-%m-%d)"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_err()   { echo -e "${RED}[ERROR]${NC} $*"; }

MODE="${1:---all}"
TARGET_SCENARIO="${2:-}"

# ── 1. SSH 연결 확인 ──────────────────────────────────────────
check_ssh() {
    log_info "스테이징 서버 SSH 연결 확인..."
    if ssh -o ConnectTimeout=5 "${STAGING_SSH}" "echo ok" &>/dev/null; then
        log_ok "SSH 연결 성공: ${STAGING_SSH}"
    else
        log_err "SSH 연결 실패. OpenVPN이 연결되었는지 확인하세요."
        exit 1
    fi
}

# ── 2. 데이터 초기화 ─────────────────────────────────────────
init_data() {
    echo ""
    echo "╔══════════════════════════════════════════════════════╗"
    echo "║          테스트 데이터 초기화 시작                     ║"
    echo "╚══════════════════════════════════════════════════════╝"
    echo ""

    # ─── 2-1. concert DB: 좌석 상태 초기화 ──────────────────
    log_info "[concert DB] 좌석 상태 초기화 (HOLD → AVAILABLE)..."
    local seat_reset
    seat_reset=$(ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker compose exec -T mysql mysql -uroot -p1234 ticketing_concert -N -e \"
        SELECT COUNT(*) FROM seats WHERE status='HOLD';
    \"" 2>/dev/null | tr -d '[:space:]')

    if [[ "${seat_reset}" -gt 0 ]]; then
        ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker compose exec -T mysql mysql -uroot -p1234 ticketing_concert -e \"
            UPDATE seats SET status='AVAILABLE', version=version+1 WHERE status='HOLD';
        \"" 2>/dev/null
        log_ok "HOLD 좌석 ${seat_reset}건 → AVAILABLE 복구"
    else
        log_ok "HOLD 좌석 없음 — 초기화 불필요"
    fi

    # 좌석 데이터 확인
    local total_seats
    total_seats=$(ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker compose exec -T mysql mysql -uroot -p1234 ticketing_concert -N -e \"
        SELECT COUNT(*) FROM seats WHERE status='AVAILABLE';
    \"" 2>/dev/null | tr -d '[:space:]')
    log_info "사용 가능 좌석 수: ${total_seats}건"

    # ─── 2-2. booking DB: 테스트 예약 정리 ──────────────────
    log_info "[booking DB] 이전 테스트 예약 데이터 정리..."
    ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker compose exec -T mysql mysql -uroot -p1234 ticketing_booking -e \"
        -- k6 테스트 userId 범위의 예약 삭제 (실 사용자 데이터 보호)
        DELETE FROM reservations WHERE user_id BETWEEN 1 AND 999;
        DELETE FROM reservations WHERE user_id BETWEEN 800 AND 905;
        DELETE FROM reservations WHERE user_id BETWEEN 1010 AND 1400;
    \"" 2>/dev/null
    log_ok "테스트 예약 데이터 정리 완료"

    # scenario10용 기본 예약 데이터 생성 (reservation_id=1)
    log_info "[booking DB] scenario10용 예약 데이터 확인 (reservation_id=1)..."
    local res_exists
    res_exists=$(ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker compose exec -T mysql mysql -uroot -p1234 ticketing_booking -N -e \"
        SELECT COUNT(*) FROM reservations WHERE reservation_id=1;
    \"" 2>/dev/null | tr -d '[:space:]')

    if [[ "${res_exists}" == "0" ]]; then
        ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker compose exec -T mysql mysql -uroot -p1234 ticketing_booking -e \"
            INSERT INTO reservations (reservation_id, user_id, seat_id, status, reserved_at, expired_at)
            VALUES (1, 1, 11, 'PENDING', NOW(), DATE_ADD(NOW(), INTERVAL 1 HOUR));
        \"" 2>/dev/null
        log_ok "scenario10용 예약 생성 (reservation_id=1)"
    else
        log_ok "scenario10용 예약 이미 존재"
    fi

    # ─── 2-3. payment DB: 테스트 결제 정리 ──────────────────
    log_info "[payment DB] 이전 테스트 결제 데이터 정리..."
    ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker compose exec -T mysql mysql -uroot -p1234 ticketing_payment -e \"
        DELETE FROM payments WHERE user_id BETWEEN 1 AND 999;
        DELETE FROM payments WHERE user_id BETWEEN 1010 AND 1400;
    \"" 2>/dev/null
    log_ok "테스트 결제 데이터 정리 완료"

    # ─── 2-4. waitingroom DB: 대기열 토큰 정리 ──────────────
    log_info "[waitingroom DB] 이전 테스트 토큰 정리..."
    ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker compose exec -T mysql mysql -uroot -p1234 ticketing_waitingroom -e \"
        DELETE FROM active_tokens WHERE user_id BETWEEN 1 AND 999;
        DELETE FROM active_tokens WHERE user_id BETWEEN 800 AND 905;
    \"" 2>/dev/null
    log_ok "대기열 토큰 정리 완료"

    # ─── 2-5. Redis: 대기열 큐 + 멱등성 키 + Rate Limiter 정리 ─
    log_info "[Redis] 대기열 큐 및 캐시 정리..."
    ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker compose exec -T redis redis-cli EVAL \"
        local keys = redis.call('KEYS', 'waiting-room:*')
        for i, key in ipairs(keys) do redis.call('DEL', key) end
        local rkeys = redis.call('KEYS', 'rate_limit:*')
        for i, key in ipairs(rkeys) do redis.call('DEL', key) end
        local ikeys = redis.call('KEYS', 'idempotency:*')
        for i, key in ipairs(ikeys) do redis.call('DEL', key) end
        return #keys + #rkeys + #ikeys
    \" 0" 2>/dev/null
    log_ok "Redis 키 정리 완료 (waiting-room, rate_limit, idempotency)"

    # Redis 전용 서버 (192.168.124.101) — waiting-room-app이 사용할 수 있음
    log_info "[Redis 101] 외부 Redis 서버 대기열 정리 시도..."
    ssh "${STAGING_SSH}" "ssh -o ConnectTimeout=3 dktlfem@192.168.124.101 'redis-cli EVAL \"
        local keys = redis.call(\\\"KEYS\\\", \\\"waiting-room:*\\\")
        for i, key in ipairs(keys) do redis.call(\\\"DEL\\\", key) end
        local rkeys = redis.call(\\\"KEYS\\\", \\\"rate_limit:*\\\")
        for i, key in ipairs(rkeys) do redis.call(\\\"DEL\\\", key) end
        return #keys + #rkeys
    \\\" 0' 2>/dev/null" 2>/dev/null && log_ok "외부 Redis 정리 완료" || log_warn "외부 Redis 접근 불가 (무시 가능)"

    echo ""
    log_ok "═══ 데이터 초기화 완료 ═══"
    echo ""
}

# ── 3. 서비스 상태 확인 ──────────────────────────────────────
check_services() {
    log_info "서비스 상태 확인..."

    # docker compose ps 전체 출력으로 확인 (--format 미지원 환경 호환)
    local ps_output
    ps_output=$(ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker compose ps 2>/dev/null" 2>/dev/null) || {
        log_warn "docker compose ps 실패 — 경로 확인: ~/${COMPOSE_DIR}"
        log_warn "COMPOSE_DIR 환경변수로 변경 가능 (예: COMPOSE_DIR=devops_lab ./prepare-and-run-domain.sh)"
        # docker-compose (v1 하이픈 형식) 시도
        ps_output=$(ssh "${STAGING_SSH}" "cd ~/${COMPOSE_DIR} && docker-compose ps 2>/dev/null" 2>/dev/null) || {
            log_err "docker compose / docker-compose 모두 실패"
            log_info "스테이징 서버에서 docker compose 파일이 있는 경로를 확인해주세요."
            exit 1
        }
    }

    echo "${ps_output}" | head -20
    echo ""

    local services=("scg-app" "concert-app" "booking-app" "payment-app" "waitingroom-app")
    local all_up=true

    for svc in "${services[@]}"; do
        if echo "${ps_output}" | grep -q "${svc}"; then
            if echo "${ps_output}" | grep "${svc}" | grep -qi "up\|running"; then
                log_ok "${svc}: running"
            else
                log_warn "${svc}: 존재하지만 running 아님"
                all_up=false
            fi
        else
            log_warn "${svc}: 목록에 없음 (컨테이너명이 다를 수 있음)"
        fi
    done

    if [[ "${all_up}" == "false" ]]; then
        log_warn "일부 서비스 상태 확인 필요. 계속 진행합니다..."
    fi
    echo ""
}

# ── 4. 시나리오 실행 ─────────────────────────────────────────
run_scenarios() {
    echo ""
    echo "╔══════════════════════════════════════════════════════╗"
    echo "║        도메인 시나리오 (8→9→10→11) 실행 시작          ║"
    echo "╚══════════════════════════════════════════════════════╝"
    echo ""

    cd "${SCRIPT_DIR}"

    if [[ -n "${TARGET_SCENARIO}" ]]; then
        log_info "단일 시나리오 실행: scenario${TARGET_SCENARIO}"
        ./run-tests.sh "${TARGET_SCENARIO}"
    else
        log_info "도메인 시나리오 순차 실행 (8→9→10→11)"
        ./run-tests.sh domain
    fi
}

# ── 메인 ─────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════════╗"
echo "║  도메인 부하테스트 — 사전 초기화 + 시나리오 8/9/10/11 실행  ║"
echo "╠══════════════════════════════════════════════════════════╣"
echo "║  모드:        ${MODE}"
echo "║  스테이징:    ${STAGING_SSH}"
echo "║  실행일:      ${RUN_DATE}"
echo "╚══════════════════════════════════════════════════════════╝"
echo ""

case "${MODE}" in
    --all)
        check_ssh
        check_services
        init_data
        run_scenarios
        ;;
    --init-only)
        check_ssh
        check_services
        init_data
        log_ok "초기화 완료. 실행은 별도로: ./run-tests.sh domain"
        ;;
    --run-only)
        run_scenarios
        ;;
    --scenario)
        if [[ -z "${TARGET_SCENARIO}" ]]; then
            log_err "시나리오 번호를 지정하세요: ./prepare-and-run-domain.sh --scenario 8"
            exit 1
        fi
        check_ssh
        check_services
        init_data
        run_scenarios
        ;;
    *)
        echo "사용법:"
        echo "  $0              # 초기화 + 전체 실행 (8→9→10→11)"
        echo "  $0 --init-only  # 초기화만"
        echo "  $0 --run-only   # 실행만"
        echo "  $0 --scenario 8 # 특정 시나리오만"
        exit 0
        ;;
esac
