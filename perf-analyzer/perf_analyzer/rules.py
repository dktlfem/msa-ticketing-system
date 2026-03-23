"""병목 판단 규칙 정의.

capacity-planning.md의 병목 순서 예측과 incident-runbook.md의 장애 매트릭스를
프로그래밍 가능한 규칙으로 변환한 것이다.

규칙 우선순위 (capacity-planning.md 기준):
  1순위: Redis — 대기열/멱등성 SPOF
  2순위: HikariCP — DB 커넥션 풀 고갈
  3순위: Optimistic Lock — 좌석 경합
  4순위: Tomcat/Netty — 스레드 풀 포화
  5순위: PG Timeout — 외부 의존성

각 규칙은 (조건 함수, Bottleneck 생성 함수) 쌍이다.
"""

from typing import Optional

from .models import Bottleneck, InfraMetrics, K6ScenarioResult

# ──────────────────────────────────────────────────────────
# 규칙 1: HikariCP 커넥션 풀 고갈
# incident-runbook.md INC-005: MySQL 장애/지연
# capacity-planning.md: "HikariCP 기본값 10개 제약"
# ──────────────────────────────────────────────────────────

def check_hikaricp_exhaustion(metrics: InfraMetrics) -> Optional[Bottleneck]:
    """HikariCP pending > 0이 지속되면 커넥션 풀 고갈."""
    if not metrics.hikari_pending:
        return None

    # pending이 0보다 큰 시점이 전체의 20% 이상이면 병목
    pending_nonzero = [(ts, val) for ts, val in metrics.hikari_pending if val > 0]
    ratio = len(pending_nonzero) / len(metrics.hikari_pending) if metrics.hikari_pending else 0

    if ratio < 0.2:
        return None

    peak_ts, peak_val = max(pending_nonzero, key=lambda x: x[1])
    active_peak = max((val for _, val in metrics.hikari_active), default=0)

    return Bottleneck(
        category="hikaricp",
        severity="high" if ratio > 0.5 else "medium",
        description=(
            f"{metrics.service}: HikariCP pending 커넥션이 전체 측정 구간의 "
            f"{ratio*100:.0f}%에서 발생. 최대 pending={peak_val:.0f}, "
            f"active 최대={active_peak:.0f}/{metrics.hikari_max:.0f}"
        ),
        evidence=[
            f"pending > 0 비율: {ratio*100:.1f}%",
            f"peak pending: {peak_val:.0f} at {peak_ts}",
            f"max pool size: {metrics.hikari_max:.0f}",
        ],
        incident_id="INC-005",
        runbook_summary=(
            "DB 커넥션 풀이 포화 상태입니다. HikariCP max-pool-size 증가를 검토하거나, "
            "slow query를 격리하세요. payment-app의 경우 PG 10s timeout 동안 "
            "커넥션을 점유하지 않도록 트랜잭션 경계를 분리했는지 확인하세요."
        ),
        recommended_actions=[
            "SHOW PROCESSLIST로 오래 실행 중인 쿼리 확인",
            "hikaricp.maximumPoolSize 증가 검토 (현재 기본 10)",
            "payment-app: confirmPayment()의 @Transactional 분리 확인",
            "slow query log 활성화 및 EXPLAIN 분석",
        ],
        metric_name="hikaricp_connections_pending",
        threshold=0,
        actual_value=peak_val,
        peak_timestamp=str(peak_ts),
    )


# ──────────────────────────────────────────────────────────
# 규칙 2: HTTP 5xx 비율 급증
# incident-runbook.md INC-001, INC-007
# ──────────────────────────────────────────────────────────

def check_http_5xx_spike(metrics: InfraMetrics) -> Optional[Bottleneck]:
    """HTTP 5xx 비율이 급증하면 서비스 장애."""
    if not metrics.http_5xx_rate:
        return None

    # 5xx rate가 0.05 (초당 0.05건 = 분당 3건) 이상인 시점 확인
    high_5xx = [(ts, val) for ts, val in metrics.http_5xx_rate if val > 0.05]
    if not high_5xx:
        return None

    peak_ts, peak_val = max(high_5xx, key=lambda x: x[1])

    return Bottleneck(
        category="http_5xx",
        severity="high",
        description=(
            f"{metrics.service}: HTTP 5xx 응답이 급증. "
            f"최대 5xx rate={peak_val:.3f}/s. "
            f"총 {len(high_5xx)}개 시점에서 임계치 초과."
        ),
        evidence=[
            f"5xx rate peak: {peak_val:.3f}/s at {peak_ts}",
            f"임계치 초과 시점 수: {len(high_5xx)}/{len(metrics.http_5xx_rate)}",
        ],
        incident_id="INC-001" if "payment" in metrics.service else "INC-006",
        runbook_summary=(
            "서비스에서 HTTP 500 에러가 급증했습니다. "
            "Kibana에서 해당 시간대 ERROR 로그를 확인하고, "
            "downstream 서비스 health를 점검하세요."
        ),
        recommended_actions=[
            f"Kibana: service:\"{metrics.service}\" AND level:\"ERROR\" 검색",
            "downstream 서비스 /actuator/health 확인",
            "DB connection pool 상태 확인",
            "Jaeger에서 error=true span 조회",
        ],
        metric_name="http_server_requests_seconds_count{status=5xx}",
        threshold=0.05,
        actual_value=peak_val,
        peak_timestamp=str(peak_ts),
    )


# ──────────────────────────────────────────────────────────
# 규칙 3: 응답 시간 P95 급증
# sli-slo.md: payment p95 < 1500ms, booking p95 < 1500ms
# ──────────────────────────────────────────────────────────

def check_latency_spike(metrics: InfraMetrics) -> Optional[Bottleneck]:
    """P95 응답 시간이 SLO 임계치를 초과하면 성능 저하."""
    if not metrics.http_request_duration_p95:
        return None

    # 서비스별 SLO 임계치 (sli-slo.md 기준)
    slo_thresholds = {
        "payment-service": 1.5,   # 1500ms
        "booking-service": 1.5,
        "concert-service": 0.3,   # 300ms
        "waitingroom-service": 0.5,
        "user-service": 0.5,
        "scg": 0.2,              # gateway overhead
    }
    threshold = slo_thresholds.get(metrics.service, 1.0)

    high_latency = [
        (ts, val) for ts, val in metrics.http_request_duration_p95
        if val > threshold
    ]
    if not high_latency:
        return None

    peak_ts, peak_val = max(high_latency, key=lambda x: x[1])

    return Bottleneck(
        category="latency",
        severity="high" if peak_val > threshold * 2 else "medium",
        description=(
            f"{metrics.service}: P95 응답 시간이 SLO({threshold*1000:.0f}ms) 초과. "
            f"최대 P95={peak_val*1000:.0f}ms. "
            f"{len(high_latency)}개 시점에서 위반."
        ),
        evidence=[
            f"P95 peak: {peak_val*1000:.0f}ms at {peak_ts}",
            f"SLO threshold: {threshold*1000:.0f}ms",
            f"위반 비율: {len(high_latency)}/{len(metrics.http_request_duration_p95)}",
        ],
        incident_id="INC-007" if "payment" in metrics.service else "",
        runbook_summary=(
            f"{metrics.service}의 응답 시간이 SLO를 초과했습니다. "
            "Jaeger에서 느린 span을 확인하고, DB slow query 또는 "
            "외부 API timeout 여부를 점검하세요."
        ),
        recommended_actions=[
            "Jaeger: Min Duration으로 느린 trace 필터링",
            "Grafana: hikaricp_connections_pending 확인",
            "slow query log 확인",
            "외부 API (TossPayments) 응답 시간 확인",
        ],
        metric_name="http_server_requests_seconds (p95)",
        threshold=threshold,
        actual_value=peak_val,
        peak_timestamp=str(peak_ts),
    )


# ──────────────────────────────────────────────────────────
# 규칙 4: JVM GC Pause 급증
# ──────────────────────────────────────────────────────────

def check_gc_pressure(metrics: InfraMetrics) -> Optional[Bottleneck]:
    """GC pause가 급증하면 JVM 힙 메모리 부족."""
    if not metrics.jvm_gc_pause_sum:
        return None

    # GC pause rate가 0.1초/분 (= 분당 100ms GC) 이상이면 경고
    high_gc = [(ts, val) for ts, val in metrics.jvm_gc_pause_sum if val > 0.1]
    if not high_gc:
        return None

    peak_ts, peak_val = max(high_gc, key=lambda x: x[1])

    return Bottleneck(
        category="gc_pressure",
        severity="medium",
        description=(
            f"{metrics.service}: GC pause가 급증. "
            f"최대 GC rate={peak_val:.3f}s/min."
        ),
        evidence=[
            f"GC pause rate peak: {peak_val:.3f}s/min at {peak_ts}",
        ],
        incident_id="",
        runbook_summary=(
            "JVM GC가 빈번하게 발생하고 있습니다. "
            "힙 메모리 사용량을 확인하고, -Xmx 증가를 검토하세요."
        ),
        recommended_actions=[
            "Grafana: jvm_memory_used_bytes{area=heap} 확인",
            "-Xmx 값 증가 검토",
            "메모리 누수 여부 확인 (heap dump 분석)",
        ],
        metric_name="jvm_gc_pause_seconds_sum",
        threshold=0.1,
        actual_value=peak_val,
        peak_timestamp=str(peak_ts),
    )


# ──────────────────────────────────────────────────────────
# 규칙 5: k6 진단 정보 기반 병목 (코드 스캔 불필요, k6가 이미 진단)
# ──────────────────────────────────────────────────────────

def check_k6_diagnostics(scenario: K6ScenarioResult) -> list:
    """k6 스크립트가 생성한 diagnostics를 Bottleneck으로 변환."""
    bottlenecks = []

    for diag in scenario.diagnostics:
        symptom = diag.get("symptom", "")
        causes = diag.get("causes", [])

        actions = [c.get("check", "") for c in causes if c.get("check")]
        cause_texts = [c.get("cause", "") for c in causes if c.get("cause")]

        bottlenecks.append(Bottleneck(
            category="k6_diagnostic",
            severity="medium",
            description=f"{scenario.scenario}: {symptom}",
            evidence=cause_texts,
            incident_id="",
            runbook_summary=symptom,
            recommended_actions=actions,
        ))

    return bottlenecks


# ──────────────────────────────────────────────────────────
# 규칙 6: Redis 메모리 사용량 급증
# capacity-planning.md: 1순위 병목
# incident-runbook.md INC-004
# ──────────────────────────────────────────────────────────

def check_redis_memory_pressure(metrics: InfraMetrics) -> Optional[Bottleneck]:
    """Redis 메모리 사용량이 급증하면 대기열/멱등성 키 폭증 의심."""
    if not metrics.redis_memory_used:
        return None

    # 시작 시점 대비 종료 시점 메모리가 50% 이상 증가하면 경고
    start_mem = metrics.redis_memory_used[0][1] if metrics.redis_memory_used else 0
    peak_ts, peak_mem = max(metrics.redis_memory_used, key=lambda x: x[1])

    if start_mem == 0:
        return None

    growth_ratio = (peak_mem - start_mem) / start_mem if start_mem > 0 else 0

    if growth_ratio < 0.5:
        return None

    return Bottleneck(
        category="redis_memory",
        severity="high" if growth_ratio > 1.0 else "medium",
        description=(
            f"Redis 메모리 사용량이 테스트 중 {growth_ratio*100:.0f}% 급증. "
            f"시작: {start_mem/1024/1024:.1f}MB → 피크: {peak_mem/1024/1024:.1f}MB. "
            f"idempotency 키 또는 대기열 키 폭증 의심."
        ),
        evidence=[
            f"시작 메모리: {start_mem/1024/1024:.1f}MB",
            f"피크 메모리: {peak_mem/1024/1024:.1f}MB at {peak_ts}",
            f"증가율: {growth_ratio*100:.0f}%",
        ],
        incident_id="INC-004",
        runbook_summary=(
            "Redis 메모리가 급증했습니다. redis-cli info memory로 사용량을 확인하고, "
            "payment:idempotency:* 키 수와 waiting-room:* 키 수를 점검하세요."
        ),
        recommended_actions=[
            "redis-cli --scan --pattern 'payment:idempotency:*' | wc -l",
            "redis-cli --scan --pattern 'waiting-room:*' | wc -l",
            "Redis maxmemory 설정 확인",
            "TTL이 적용되지 않은 키가 있는지 점검",
        ],
        metric_name="redis_memory_used_bytes",
        threshold=start_mem * 1.5,
        actual_value=peak_mem,
        peak_timestamp=str(peak_ts),
    )


# ──────────────────────────────────────────────────────────
# 규칙 7: SCG 필터 체인 오버헤드
# observability.md: SCG gateway 필터 오버헤드 측정
# ──────────────────────────────────────────────────────────

def check_scg_filter_overhead(metrics: InfraMetrics) -> Optional[Bottleneck]:
    """SCG 전체 P95에서 downstream 서비스 P95을 빼면 필터 오버헤드.

    Auth-Passport/JWT 처리 포함 SCG 필터 체인이 200ms를 넘으면 병목.
    """
    if not metrics.scg_total_duration_p95:
        return None

    # SCG 전체 P95 최대값
    peak_ts, peak_val = max(metrics.scg_total_duration_p95, key=lambda x: x[1])

    # 200ms (0.2s) 이상이면 SCG 자체 오버헤드 의심
    if peak_val < 0.2:
        return None

    return Bottleneck(
        category="scg_filter_overhead",
        severity="medium" if peak_val < 0.5 else "high",
        description=(
            f"SCG 게이트웨이 P95 응답 시간이 {peak_val*1000:.0f}ms. "
            f"JWT 검증, RequestSanitize, AccessLog 등 필터 체인 오버헤드 의심."
        ),
        evidence=[
            f"SCG 전체 P95: {peak_val*1000:.0f}ms at {peak_ts}",
            f"routeId별 P95: {', '.join(f'{k}={v[-1][1]*1000:.0f}ms' for k, v in metrics.scg_route_duration_p95.items() if v)}",
        ],
        incident_id="INC-006",
        runbook_summary=(
            "SCG 게이트웨이 응답 시간이 높습니다. "
            "Jaeger에서 SCG span의 필터별 처리 시간을 확인하고, "
            "JWT 파싱이 CPU-bound인지 점검하세요."
        ),
        recommended_actions=[
            "Jaeger: scg span에서 필터별 duration 확인",
            "Grafana: reactor.netty.* 메트릭 확인 (Netty event loop 포화)",
            "SCG CPU 사용률 점검 (JWT 파싱은 CPU-bound)",
            "routeId별 P95 비교로 특정 서비스에 병목이 집중되는지 확인",
        ],
        metric_name="spring_cloud_gateway_requests_seconds (p95)",
        threshold=0.2,
        actual_value=peak_val,
        peak_timestamp=str(peak_ts),
    )


# ──────────────────────────────────────────────────────────
# 규칙 8: PG(TossPayments) 외부 호출 타임아웃
# incident-runbook.md INC-007
# ──────────────────────────────────────────────────────────

def check_pg_call_timeout(metrics: InfraMetrics) -> Optional[Bottleneck]:
    """TossPayments API 응답 시간이 임계치를 초과하면 PG 장애 의심."""
    if not metrics.pg_call_duration or "payment" not in metrics.service:
        return None

    # PG 호출이 3초를 넘으면 경고, 8초 넘으면 타임아웃 임박
    high_latency = [(ts, val) for ts, val in metrics.pg_call_duration if val > 3.0]
    if not high_latency:
        return None

    peak_ts, peak_val = max(high_latency, key=lambda x: x[1])

    return Bottleneck(
        category="pg_timeout",
        severity="critical" if peak_val > 8.0 else "high",
        description=(
            f"TossPayments API 응답 시간 최대 {peak_val:.1f}s. "
            f"read-timeout(10s) 임박 — PG 장애 의심."
        ),
        evidence=[
            f"PG 응답 시간 peak: {peak_val:.1f}s at {peak_ts}",
            f"3s 초과 시점: {len(high_latency)}건",
            f"read-timeout 설정: 10s",
        ],
        incident_id="INC-007",
        runbook_summary=(
            "TossPayments API 응답이 지연되고 있습니다. "
            "PG 상태 페이지(tosspayments.com/status)를 확인하고, "
            "타임아웃 시 READY 상태 결제 잔류 건수를 점검하세요."
        ),
        recommended_actions=[
            "TossPayments 상태 페이지 확인: https://www.tosspayments.com/status",
            "DB: SELECT count(*) FROM payments WHERE status='READY' AND created_at < NOW()-INTERVAL 10 MINUTE",
            "circuit breaker 적용 검토 (Resilience4j)",
            "PG 오류 패턴 분석: 4xx(카드 문제) vs 5xx(PG 서버 문제) 구분",
        ],
        metric_name="http_client_requests_seconds (tosspayments)",
        threshold=3.0,
        actual_value=peak_val,
        peak_timestamp=str(peak_ts),
    )


# ──────────────────────────────────────────────────────────
# 규칙 9: 서비스간 내부 API 호출 병목
# architecture/overview.md: payment→booking→concert 단방향
# ──────────────────────────────────────────────────────────

def check_internal_api_latency(metrics: InfraMetrics) -> Optional[Bottleneck]:
    """서비스간 내부 API 호출 P95이 임계치를 초과하면 downstream 병목."""
    if not metrics.internal_api_latency:
        return None

    worst_path = None
    worst_peak = 0
    worst_ts = ""

    for call_path, data_points in metrics.internal_api_latency.items():
        if not data_points:
            continue
        # 500ms(0.5s) 이상이면 내부 호출 병목
        high = [(ts, val) for ts, val in data_points if val > 0.5]
        if high:
            ts, val = max(high, key=lambda x: x[1])
            if val > worst_peak:
                worst_peak = val
                worst_path = call_path
                worst_ts = ts

    if worst_path is None:
        return None

    return Bottleneck(
        category="internal_api_latency",
        severity="high" if worst_peak > 1.0 else "medium",
        description=(
            f"서비스간 내부 호출 '{worst_path}' P95이 {worst_peak*1000:.0f}ms. "
            f"downstream 서비스 병목 또는 네트워크 지연 의심."
        ),
        evidence=[
            f"호출 경로: {worst_path}",
            f"P95 peak: {worst_peak*1000:.0f}ms at {worst_ts}",
            f"임계치: 500ms",
        ],
        incident_id="",
        runbook_summary=(
            f"'{worst_path}' 내부 API 호출이 지연되고 있습니다. "
            "Jaeger에서 해당 span을 확인하고, downstream 서비스의 "
            "DB/Redis 상태를 점검하세요."
        ),
        recommended_actions=[
            f"Jaeger: {worst_path.split('→')[1] if '→' in worst_path else worst_path} 서비스 span 확인",
            "downstream 서비스 /actuator/health 확인",
            "downstream 서비스 HikariCP pending 확인",
            "네트워크 RTT 확인 (Docker 내부 네트워크 vs 외부)",
        ],
        metric_name="http_client_requests_seconds (internal)",
        threshold=0.5,
        actual_value=worst_peak,
        peak_timestamp=str(worst_ts),
    )


# ──────────────────────────────────────────────────────────
# 규칙 10: k6 HTTP 상태 코드 분포 이상 (409 경합 / 5xx 서버오류)
# ──────────────────────────────────────────────────────────

def check_k6_http_status_anomaly(scenario: K6ScenarioResult) -> list:
    """k6 결과의 HTTP 상태 코드 분포에서 이상 패턴을 탐지."""
    bottlenecks = []

    if not scenario.http_status_dist:
        return bottlenecks

    total = scenario.total_requests or 1
    dist = scenario.http_status_dist

    # 5xx 비율이 5% 이상이면 서버 오류 병목
    error_5xx = dist.get("5xx", 0) + dist.get("5xx_upstream", 0)
    if error_5xx > 0 and (error_5xx / total) > 0.05:
        bottlenecks.append(Bottleneck(
            category="k6_5xx_anomaly",
            severity="high",
            description=(
                f"{scenario.scenario}: 5xx 응답이 {error_5xx}건 "
                f"({error_5xx/total*100:.1f}%) — 서버 오류 급증."
            ),
            evidence=[
                f"5xx 건수: {error_5xx}/{total}",
                f"HTTP 상태 분포: {dist}",
            ],
            runbook_summary=f"k6 테스트에서 5xx 비율이 {error_5xx/total*100:.1f}%입니다.",
            recommended_actions=[
                "Kibana에서 해당 시간대 ERROR 로그 확인",
                "downstream 서비스 health 점검",
            ],
        ))

    return bottlenecks


# ──────────────────────────────────────────────────────────
# 규칙 레지스트리
# ──────────────────────────────────────────────────────────

# InfraMetrics 기반 규칙 (서비스별로 실행)
INFRA_RULES = [
    check_hikaricp_exhaustion,
    check_http_5xx_spike,
    check_latency_spike,
    check_gc_pressure,
    check_redis_memory_pressure,
    check_scg_filter_overhead,
    check_pg_call_timeout,
    check_internal_api_latency,
]

# K6ScenarioResult 기반 규칙 (시나리오별로 실행)
K6_RULES = [
    check_k6_diagnostics,
    check_k6_http_status_anomaly,
]
