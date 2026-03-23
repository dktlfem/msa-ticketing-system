"""데이터 모델 정의 — k6 결과, Prometheus 메트릭, 병목 분석 결과를 담는 구조체."""

from dataclasses import dataclass, field
from typing import Optional


@dataclass
class LatencyMetrics:
    """레이턴시 퍼센타일 지표."""
    p50: float = 0.0
    p95: float = 0.0
    p99: float = 0.0


@dataclass
class K6ScenarioResult:
    """k6 시나리오 1건의 파싱 결과.

    generate-summary.py의 extract_summary_row()가 반환하던 dict를
    타입이 명확한 dataclass로 전환한 것이다.
    """
    scenario: str = ""
    timestamp: str = ""
    passed: bool = False
    total_requests: int = 0

    # 레이턴시 퍼센타일 — P50은 사용자 체감, P95/P99는 꼬리 레이턴시
    p50_ms: float = 0.0
    p95_ms: float = 0.0
    p99_ms: float = 0.0
    max_ms: float = 0.0       # 최대 응답 시간 (이상치 탐지용)
    min_ms: float = 0.0       # 최소 응답 시간 (기준선)

    error_rate: str = ""
    tps: float = 0.0
    peak_tps: float = 0.0     # 피크 구간 TPS (평균과 비교해 버스트 강도 판단)
    key_metric: str = ""

    # HTTP 상태 코드 분포 — 정상(200) vs 경합(409) vs 차단(429) vs 서버오류(5xx) 구분
    http_status_dist: dict = field(default_factory=dict)   # {"200": 3500, "409": 150, "500": 3}

    # 시간대별 TPS 변화 — 평균 TPS로는 피크 구간을 볼 수 없다
    tps_timeline: list = field(default_factory=list)       # [(timestamp, tps), ...]

    # VU(가상 사용자)당 iteration 소요 시간
    iteration_duration_avg_ms: float = 0.0

    # 원본 JSON 전체 (상세 시트 생성 시 사용)
    raw: dict = field(default_factory=dict)

    # 테스트 시간 범위 (Prometheus 수집 시 사용)
    start_time: str = ""
    end_time: str = ""

    # 테스트 총 소요 시간 (초)
    duration_seconds: float = 0.0

    # 진단 정보 (k6 스크립트가 생성한 것)
    diagnostics: list = field(default_factory=list)


@dataclass
class InfraMetrics:
    """Prometheus에서 수집한 인프라 메트릭 스냅샷.

    capacity-planning.md의 병목 순서 예측에서 정의한 핵심 지표들이다:
    1순위 Redis, 2순위 HikariCP, 3순위 Optimistic Lock, 4순위 Tomcat
    """
    # HikariCP (2순위 병목)
    hikari_active: list = field(default_factory=list)       # (timestamp, value) 쌍
    hikari_pending: list = field(default_factory=list)
    hikari_max: float = 10.0

    # Tomcat / Netty (4순위 병목)
    tomcat_threads_busy: list = field(default_factory=list)
    tomcat_threads_max: float = 200.0

    # JVM
    jvm_heap_used: list = field(default_factory=list)
    jvm_gc_pause_sum: list = field(default_factory=list)

    # Redis (1순위 병목)
    redis_latency: list = field(default_factory=list)
    redis_connected_clients: list = field(default_factory=list)
    redis_memory_used: list = field(default_factory=list)          # Redis 메모리 사용량 (대기열 크기 추적)
    redis_keys_count: list = field(default_factory=list)           # Redis 키 수 (idempotency/대기열 키 폭증 감지)

    # HTTP 서버 (서비스별)
    http_5xx_rate: list = field(default_factory=list)
    http_request_duration_p95: list = field(default_factory=list)
    http_request_duration_p50: list = field(default_factory=list)  # P50 — 사용자 체감 응답 시간
    http_status_4xx_rate: list = field(default_factory=list)       # 409(경합)/429(차단) 구분 필요

    # SCG Gateway 필터 오버헤드 (Auth-Passport/JWT 처리 포함)
    # gateway 총 응답 시간 - downstream 서비스 응답 시간 = 필터 체인 오버헤드
    scg_total_duration_p95: list = field(default_factory=list)     # SCG 전체 P95
    scg_route_duration_p95: dict = field(default_factory=dict)     # routeId별 P95 {"payment-service": [...]}

    # 서비스 간 내부 API 호출 레이턴시
    # payment → booking, booking → concert 각각의 호출 시간
    internal_api_latency: dict = field(default_factory=dict)       # {"payment→booking": [...], "booking→concert": [...]}

    # PG 외부 호출 (TossPayments)
    pg_call_duration: list = field(default_factory=list)           # TossPayments confirm/cancel 응답 시간
    pg_call_error_rate: list = field(default_factory=list)         # PG 호출 실패율

    # 수집 메타 정보
    service: str = ""
    start_time: str = ""
    end_time: str = ""


@dataclass
class Bottleneck:
    """식별된 병목 1건.

    analyzer.py의 규칙 엔진이 생성한다.
    incident-runbook.md의 INC-XXX와 매칭된다.
    """
    # 병목 유형
    category: str = ""          # "hikaricp", "redis", "optimistic_lock", "tomcat", "pg_timeout"
    severity: str = "medium"    # "critical", "high", "medium", "low"

    # 증거
    description: str = ""       # 사람이 읽을 수 있는 설명
    evidence: list = field(default_factory=list)  # 근거 데이터 목록

    # 대응
    incident_id: str = ""       # "INC-001", "INC-005" 등
    runbook_summary: str = ""   # 런북 요약 (1~2문장)
    recommended_actions: list = field(default_factory=list)

    # 메트릭 근거
    metric_name: str = ""       # Prometheus 메트릭 이름
    threshold: float = 0.0      # 위반된 임계치
    actual_value: float = 0.0   # 실제 측정치
    peak_timestamp: str = ""    # 최고치 시각


@dataclass
class AnalysisReport:
    """전체 분석 결과 — report 명령어의 입력."""
    scenarios: list = field(default_factory=list)      # List[K6ScenarioResult]
    infra_metrics: list = field(default_factory=list)   # List[InfraMetrics]
    bottlenecks: list = field(default_factory=list)     # List[Bottleneck]
    analysis_time: str = ""
    prometheus_url: str = ""
    test_date: str = ""
