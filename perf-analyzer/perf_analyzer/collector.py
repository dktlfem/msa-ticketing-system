"""Prometheus API 메트릭 수집기.

capacity-planning.md에서 정의한 병목 순서의 핵심 메트릭을 자동 수집한다:
  1순위: Redis latency, connected_clients
  2순위: HikariCP active/pending connections
  3순위: concert-app optimistic lock (HTTP 409 비율)
  4순위: Tomcat threads busy

observability.md의 PromQL 쿼리를 코드로 옮긴 것이다.
"""

import requests
from datetime import datetime
from typing import List, Optional

from .models import InfraMetrics

# Prometheus instant query는 단일 시점,
# range query는 시간대별 시계열을 반환한다.
# 병목 분석에는 range query가 필요하다.
RANGE_QUERY_PATH = "/api/v1/query_range"

# 서비스별 수집할 PromQL 쿼리 정의
# key: 필드 이름, value: PromQL 템플릿 ({service}를 치환)
METRIC_QUERIES = {
    # HikariCP (capacity-planning.md: 2순위 병목)
    "hikari_active": 'hikaricp_connections_active{{application="{service}"}}',
    "hikari_pending": 'hikaricp_connections_pending{{application="{service}"}}',
    "hikari_max": 'hikaricp_connections_max{{application="{service}"}}',

    # Tomcat threads (capacity-planning.md: 4순위 병목)
    "tomcat_threads_busy": 'tomcat_threads_busy_threads{{application="{service}"}}',
    "tomcat_threads_max": 'tomcat_threads_config_max_threads{{application="{service}"}}',

    # JVM
    "jvm_heap_used": 'jvm_memory_used_bytes{{application="{service}", area="heap"}}',
    "jvm_gc_pause_sum": 'rate(jvm_gc_pause_seconds_sum{{application="{service}"}}[1m])',

    # HTTP 서버 메트릭
    "http_5xx_rate": 'rate(http_server_requests_seconds_count{{application="{service}", status=~"5.."}}[1m])',
    "http_request_duration_p95": 'histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{{application="{service}"}}[1m]))',

    # ── 추가 메트릭 (models.py 확장 반영) ──

    # P50 — 사용자 체감 응답 시간
    "http_request_duration_p50": 'histogram_quantile(0.50, rate(http_server_requests_seconds_bucket{{application="{service}"}}[1m]))',

    # HTTP 4xx (409 경합 / 429 차단 구분)
    "http_status_4xx_rate": 'rate(http_server_requests_seconds_count{{application="{service}", status=~"4.."}}[1m])',

    # Redis (1순위 병목 — 확장)
    "redis_latency": 'redis_command_duration_seconds{{application="{service}"}}',
    "redis_connected_clients": 'redis_connected_clients',
    "redis_memory_used": 'redis_memory_used_bytes',
    "redis_keys_count": 'redis_db_keys',

    # PG 외부 호출 (TossPayments)
    # payment-app에서 RestClient로 호출하는 PG API 응답 시간
    "pg_call_duration": 'http_client_requests_seconds_sum{{application="{service}", uri=~".*tosspayments.*"}}',
    "pg_call_error_rate": 'rate(http_client_requests_seconds_count{{application="{service}", uri=~".*tosspayments.*", status=~"[45].."}}[1m])',
}

# SCG 전용 메트릭 — scg 서비스에만 적용
SCG_METRIC_QUERIES = {
    # SCG 전체 P95 (필터 체인 오버헤드 포함)
    "scg_total_duration_p95": 'histogram_quantile(0.95, rate(spring_cloud_gateway_requests_seconds_bucket{{routeId=~".+"}}[1m]))',
}

# SCG routeId별 P95 — 개별 쿼리로 수집
SCG_ROUTE_IDS = [
    "payment-service",
    "concert-service",
    "user-service",
]

# 서비스간 내부 API 호출 레이턴시 쿼리 (http_client_requests)
INTERNAL_API_QUERIES = {
    # payment → booking 호출
    "payment→booking": 'histogram_quantile(0.95, rate(http_client_requests_seconds_bucket{{application="payment-service", uri=~".*booking.*"}}[1m]))',
    # payment → concert 호출
    "payment→concert": 'histogram_quantile(0.95, rate(http_client_requests_seconds_bucket{{application="payment-service", uri=~".*concert.*"}}[1m]))',
    # booking → concert 호출
    "booking→concert": 'histogram_quantile(0.95, rate(http_client_requests_seconds_bucket{{application="booking-service", uri=~".*concert.*"}}[1m]))',
    # booking → waitingroom 호출
    "booking→waitingroom": 'histogram_quantile(0.95, rate(http_client_requests_seconds_bucket{{application="booking-service", uri=~".*waiting.*"}}[1m]))',
}

# 서비스 이름 매핑 (application 태그 → 서비스 모듈)
SERVICE_NAMES = [
    "payment-service",
    "booking-service",
    "concert-service",
    "waitingroom-service",
    "user-service",
    "scg",
]


def collect_metrics(
    prometheus_url: str,
    start_time: str,
    end_time: str,
    services: Optional[List[str]] = None,
    step: str = "15s",
) -> List[InfraMetrics]:
    """Prometheus에서 지정 시간대의 인프라 메트릭을 수집.

    Args:
        prometheus_url: Prometheus 서버 URL (예: http://192.168.124.100:8080/prometheus)
        start_time: ISO 형식 시작 시각
        end_time: ISO 형식 종료 시각
        services: 수집할 서비스 목록 (None이면 전체)
        step: 수집 간격 (기본 15초)

    Returns:
        서비스별 InfraMetrics 리스트
    """
    if services is None:
        services = SERVICE_NAMES

    results = []
    for service in services:
        metrics = _collect_service_metrics(
            prometheus_url, service, start_time, end_time, step
        )
        results.append(metrics)

    return results


def _collect_service_metrics(
    prometheus_url: str,
    service: str,
    start_time: str,
    end_time: str,
    step: str,
) -> InfraMetrics:
    """단일 서비스의 메트릭을 Prometheus에서 수집."""
    metrics = InfraMetrics(
        service=service,
        start_time=start_time,
        end_time=end_time,
    )

    # 공통 메트릭 수집
    for field_name, query_template in METRIC_QUERIES.items():
        query = query_template.format(service=service)
        try:
            data = _prometheus_range_query(
                prometheus_url, query, start_time, end_time, step
            )
            _set_metric_field(metrics, field_name, data)
        except Exception as e:
            print(f"  ⚠ {service}/{field_name} 수집 실패: {e}")

    # SCG 전용 메트릭 (scg 서비스에만 적용)
    if service == "scg":
        for field_name, query in SCG_METRIC_QUERIES.items():
            try:
                data = _prometheus_range_query(
                    prometheus_url, query, start_time, end_time, step
                )
                _set_metric_field(metrics, field_name, data)
            except Exception as e:
                print(f"  ⚠ {service}/{field_name} 수집 실패: {e}")

        # routeId별 P95 수집
        for route_id in SCG_ROUTE_IDS:
            query = (
                f'histogram_quantile(0.95, rate('
                f'spring_cloud_gateway_requests_seconds_bucket{{routeId="{route_id}"}}[1m]))'
            )
            try:
                data = _prometheus_range_query(
                    prometheus_url, query, start_time, end_time, step
                )
                if data:
                    metrics.scg_route_duration_p95[route_id] = data
            except Exception as e:
                print(f"  ⚠ scg/route_{route_id} 수집 실패: {e}")

    # 서비스간 내부 API 호출 레이턴시 (payment-service, booking-service에만 해당)
    if service in ("payment-service", "booking-service"):
        for call_path, query in INTERNAL_API_QUERIES.items():
            if not call_path.startswith(service.replace("-service", "")):
                continue
            try:
                data = _prometheus_range_query(
                    prometheus_url, query, start_time, end_time, step
                )
                if data:
                    metrics.internal_api_latency[call_path] = data
            except Exception as e:
                print(f"  ⚠ {service}/{call_path} 수집 실패: {e}")

    return metrics


def _prometheus_range_query(
    prometheus_url: str,
    query: str,
    start_time: str,
    end_time: str,
    step: str,
) -> list:
    """Prometheus range query를 실행하고 (timestamp, value) 리스트를 반환."""
    url = f"{prometheus_url.rstrip('/')}{RANGE_QUERY_PATH}"
    params = {
        "query": query,
        "start": start_time,
        "end": end_time,
        "step": step,
    }

    resp = requests.get(url, params=params, timeout=10)
    resp.raise_for_status()

    body = resp.json()
    if body.get("status") != "success":
        return []

    # range query 결과는 result[].values 에 [[timestamp, "value"], ...] 형태
    result_list = body.get("data", {}).get("result", [])
    if not result_list:
        return []

    # 첫 번째 시계열의 값만 사용 (단일 인스턴스 가정)
    values = result_list[0].get("values", [])
    return [(ts, float(val)) for ts, val in values]


def _set_metric_field(metrics: InfraMetrics, field_name: str, data: list):
    """InfraMetrics의 해당 필드에 데이터를 설정.

    max 값 필드(hikari_max, tomcat_threads_max)는 마지막 값을 스칼라로 저장.
    """
    if field_name in ("hikari_max", "tomcat_threads_max"):
        if data:
            setattr(metrics, field_name, data[-1][1])
    else:
        setattr(metrics, field_name, data)


def check_prometheus_connection(prometheus_url: str) -> bool:
    """Prometheus 연결 상태를 확인."""
    try:
        url = f"{prometheus_url.rstrip('/')}/api/v1/status/config"
        resp = requests.get(url, timeout=5)
        return resp.status_code == 200
    except Exception:
        return False
