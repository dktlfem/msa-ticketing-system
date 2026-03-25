#!/usr/bin/env bash
# =============================================================================
# SCRUM-47 Mini Integration E2E Demo Script
# 진입점: http://192.168.124.100:8080 (admin-gateway → SCG → 각 마이크로서비스)
# 실행 전제: docker compose up -d 완료 상태
# =============================================================================

set -euo pipefail

BASE_URL="http://192.168.124.100:8090"
# SCG JWT 시크릿 (application.yml의 개발용 시크릿)
JWT_SECRET="change-me-in-production-must-be-at-least-32-bytes!!"

# -----------------------------------------------------------------------------
# 공통 유틸
# -----------------------------------------------------------------------------

# HS256 JWT 생성 (python3 사용)
make_jwt() {
  local user_id="$1"
  python3 - <<PYEOF
import json, hmac, hashlib, base64, time

secret = b"${JWT_SECRET}"
now = int(time.time())

header  = base64.urlsafe_b64encode(json.dumps({"alg":"HS256","typ":"JWT"}).encode()).rstrip(b'=').decode()
payload = base64.urlsafe_b64encode(json.dumps({
    "sub": "${user_id}",
    "roles": ["USER"],
    "iat": now,
    "exp": now + 3600
}).encode()).rstrip(b'=').decode()

msg = f"{header}.{payload}".encode()
sig = base64.urlsafe_b64encode(hmac.new(secret, msg, hashlib.sha256).digest()).rstrip(b'=').decode()
print(f"{header}.{payload}.{sig}")
PYEOF
}

# Auth-Passport 헤더 생성 (SCG가 JWT 검증 후 주입하는 값 — 데모에서는 직접 생성)
make_passport() {
  local user_id="$1"
  local issued_at
  issued_at=$(date +%s)
  local json
  json=$(printf '{"userId":"%s","roles":["USER"],"jti":null,"issuedAt":%s,"clientIp":"127.0.0.1"}' \
    "$user_id" "$issued_at")
  echo -n "$json" | base64 | tr '+/' '-_' | tr -d '='
}

print_separator() {
  echo ""
  echo "════════════════════════════════════════════════════════"
  echo "  $1"
  echo "════════════════════════════════════════════════════════"
}

print_step() {
  echo ""
  echo "▶ $1"
}

# -----------------------------------------------------------------------------
# Scene 0: 서비스 헬스 체크 (JWT 불필요 — SCG excluded-paths: /actuator/**)
# -----------------------------------------------------------------------------
print_separator "Scene 0. 서비스 기동 확인"

print_step "SCG 직접 헬스 체크 (JWT 없이 통과 가능한 경로)"
SCG_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" \
  "http://192.168.124.100:8090/actuator/health")
echo "  scg health: ${SCG_HEALTH}"

print_step "admin-gateway → SCG → 서비스 헬스 체크"
for svc in "users" "seats" "waiting-room" "payments"; do
  STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
    "${BASE_URL}/api/v1/${svc}/actuator/health" 2>/dev/null || echo "000")
  echo "  /api/v1/${svc}: ${STATUS}"
done

# -----------------------------------------------------------------------------
# Scene 1: Happy Path — 정상 예약 플로우
# -----------------------------------------------------------------------------
print_separator "Scene 1. 정상 예약 플로우 (Happy Path)"

USER_ID=1
EVENT_ID=1
SCHEDULE_ID=11

print_step "JWT 생성 (userId=${USER_ID})"
JWT=$(make_jwt "$USER_ID")
PASSPORT=$(make_passport "$USER_ID")
echo "  JWT 생성 완료"

print_step "① 대기열 진입"
QUEUE_RESP=$(curl -s -X POST "${BASE_URL}/api/v1/waiting-room/join" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JWT}" \
  -d "{\"eventId\": ${EVENT_ID}, \"userId\": ${USER_ID}}")
echo "$QUEUE_RESP" | python3 -m json.tool 2>/dev/null || echo "$QUEUE_RESP"

IS_ALLOWED=$(echo "$QUEUE_RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('isAllowed',False))" 2>/dev/null || echo "False")
TOKEN_ID=$(echo "$QUEUE_RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('tokenId') or '')" 2>/dev/null || echo "")

print_step "② 대기열 상태 조회"
STATUS_RESP=$(curl -s "${BASE_URL}/api/v1/waiting-room/status?eventId=${EVENT_ID}&userId=${USER_ID}" \
  -H "Authorization: Bearer ${JWT}")
echo "$STATUS_RESP" | python3 -m json.tool 2>/dev/null || echo "$STATUS_RESP"
# 상태 조회 결과에서 isAllowed/tokenId 갱신 (진입 직후 즉시 통과 케이스 대응)
STATUS_IS_ALLOWED=$(echo "$STATUS_RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('isAllowed',False))" 2>/dev/null || echo "False")
STATUS_TOKEN_ID=$(echo "$STATUS_RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('tokenId') or '')" 2>/dev/null || echo "")
if [ "$STATUS_IS_ALLOWED" = "True" ] && [ -n "$STATUS_TOKEN_ID" ]; then
  IS_ALLOWED="True"
  TOKEN_ID="$STATUS_TOKEN_ID"
fi

print_step "③ 좌석 목록 조회 (scheduleId=${SCHEDULE_ID})"
SEAT_RESP=$(curl -s "${BASE_URL}/api/v1/seats/available/${SCHEDULE_ID}" \
  -H "Authorization: Bearer ${JWT}")
echo "$SEAT_RESP" | python3 -m json.tool 2>/dev/null || echo "$SEAT_RESP"
SEAT_ID=$(echo "$SEAT_RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d[0]['seatId'] if isinstance(d,list) and d else 1)" \
  2>/dev/null || echo "1")
echo "  → 예약 대상 좌석 ID: ${SEAT_ID}"

print_step "④ 예약 생성 (seatId=${SEAT_ID})"
if [ "$IS_ALLOWED" = "True" ] && [ -n "$TOKEN_ID" ]; then
  QUEUE_TOKEN="$TOKEN_ID"
else
  echo "  ⚠ 대기열 순번 대기 중 — Queue-Token을 demo-token으로 대체"
  QUEUE_TOKEN="demo-token-$(date +%s)"
fi

RESERVATION_RESP=$(curl -s -X POST "${BASE_URL}/api/v1/reservations" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JWT}" \
  -H "Auth-Passport: ${PASSPORT}" \
  -H "Queue-Token: ${QUEUE_TOKEN}" \
  -d "{\"seatId\": ${SEAT_ID}}")
echo "$RESERVATION_RESP" | python3 -m json.tool 2>/dev/null || echo "$RESERVATION_RESP"
RESERVATION_ID=$(echo "$RESERVATION_RESP" | python3 -c \
  "import sys,json; d=json.load(sys.stdin); print(d.get('reservationId',''))" 2>/dev/null || echo "")

if [ -n "$RESERVATION_ID" ]; then
  print_step "⑤ 결제 요청 (reservationId=${RESERVATION_ID})"
  IDEMPOTENCY_KEY=$(python3 -c "import uuid; print(uuid.uuid4())")
  curl -s -X POST "${BASE_URL}/api/v1/payments/request" \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ${JWT}" \
    -H "Auth-Passport: ${PASSPORT}" \
    -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
    -d "{\"reservationId\": ${RESERVATION_ID}}" \
    | python3 -m json.tool 2>/dev/null
else
  echo "  ⚠ 예약 ID 없음 — 결제 단계 스킵"
fi

# -----------------------------------------------------------------------------
# Scene 2: 토큰 무효 — Queue-Token 검증 실패
# -----------------------------------------------------------------------------
print_separator "Scene 2. 토큰 무효 (Queue-Token 검증 실패)"

print_step "만료된 Queue-Token으로 예약 시도"
curl -s -X POST "${BASE_URL}/api/v1/reservations" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JWT}" \
  -H "Auth-Passport: ${PASSPORT}" \
  -H "Queue-Token: invalid-expired-token-000" \
  -d '{"seatId": 99}' \
  | python3 -m json.tool 2>/dev/null
echo "  → 예상: 400 또는 401 (토큰 검증 실패)"

# -----------------------------------------------------------------------------
# Scene 3: 동시 예약 — 낙관적 락 충돌
# -----------------------------------------------------------------------------
print_separator "Scene 3. 동시 예약 충돌 (낙관적 락)"

SEAT_ID_CONCURRENT=11
JWT_1=$(make_jwt "1")
JWT_2=$(make_jwt "2")
PASSPORT_1=$(make_passport "1")
PASSPORT_2=$(make_passport "2")
QUEUE_TOKEN_1="concurrent-token-user1-$(date +%s)"
QUEUE_TOKEN_2="concurrent-token-user2-$(date +%s)"

print_step "동일 좌석(seatId=${SEAT_ID_CONCURRENT})에 사용자 1·2 동시 요청"
(curl -s -X POST "${BASE_URL}/api/v1/reservations" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JWT_1}" \
  -H "Auth-Passport: ${PASSPORT_1}" \
  -H "Queue-Token: ${QUEUE_TOKEN_1}" \
  -d "{\"seatId\": ${SEAT_ID_CONCURRENT}}" \
  | python3 -m json.tool 2>/dev/null; echo "  [사용자 1 완료]") &
PID1=$!

(curl -s -X POST "${BASE_URL}/api/v1/reservations" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JWT_2}" \
  -H "Auth-Passport: ${PASSPORT_2}" \
  -H "Queue-Token: ${QUEUE_TOKEN_2}" \
  -d "{\"seatId\": ${SEAT_ID_CONCURRENT}}" \
  | python3 -m json.tool 2>/dev/null; echo "  [사용자 2 완료]") &
PID2=$!

wait $PID1
wait $PID2
echo "  → 예상: 1개 201 Created, 1개 409 Conflict"

# -----------------------------------------------------------------------------
# Scene 4: Saga 보상 트랜잭션 — 결제 실패 → 예약 롤백
# -----------------------------------------------------------------------------
print_separator "Scene 4. Saga 보상 트랜잭션 (결제 실패 → 예약 CANCELLED)"

print_step "존재하지 않는 reservationId로 결제 요청 → 보상 흐름 트리거"
IDEMPOTENCY_KEY2=$(python3 -c "import uuid; print(uuid.uuid4())")
curl -s -X POST "${BASE_URL}/api/v1/payments/request" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${JWT}" \
  -H "Auth-Passport: ${PASSPORT}" \
  -H "Idempotency-Key: ${IDEMPOTENCY_KEY2}" \
  -d '{"reservationId": 999999}' \
  | python3 -m json.tool 2>/dev/null
echo "  → 예상: 404 or 결제 실패 + booking CANCELLED"

# -----------------------------------------------------------------------------
print_separator "E2E Demo 완료"
echo "  Jaeger 트레이스: http://192.168.124.100:8080/jaeger/"
echo "  Grafana 대시보드: http://192.168.124.100:8080/grafana/"
echo ""
