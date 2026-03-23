"""Elasticsearch 데이터 적재 모듈.

perf-analyzer 분석 결과를 Elasticsearch에 적재하여
Kibana에서 부하테스트 결과를 시각화하고 이력을 추적할 수 있게 한다.

인덱스 구조:
  - perf-test-results: k6 시나리오별 요약 (시나리오명, 판정, P50/P95/P99, TPS, 에러율 등)
  - perf-bottlenecks:  식별된 병목 (카테고리, 심각도, 설명, 권장 조치 등)

ES 접근 방식:
  - 집 스테이징 서버의 ES는 Docker 내부 포트(9200)만 열려 있음
  - docker exec로 curl을 실행하거나, docker-compose에서 포트를 바인딩해야 함
  - 이 모듈은 표준 HTTP API로 ES에 접근하므로, URL만 바꾸면 어디서든 동작
"""

import json
import dataclasses
from datetime import datetime
from typing import List, Optional
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

from .models import K6ScenarioResult, Bottleneck, AnalysisReport

# ── 인덱스 설정 ────────────────────────────────────────────────

RESULTS_INDEX = "perf-test-results"
BOTTLENECKS_INDEX = "perf-bottlenecks"

# 인덱스 매핑 — keyword 타입은 정확한 값 집계에,
#               date 타입은 시간 범위 필터에 필수
RESULTS_INDEX_MAPPING = {
    "mappings": {
        "properties": {
            # 식별자
            "scenario": {"type": "keyword"},
            "test_date": {"type": "date", "format": "yyyy-MM-dd"},
            "timestamp": {"type": "date"},
            "run_id": {"type": "keyword"},

            # 판정
            "passed": {"type": "boolean"},

            # 요청 지표
            "total_requests": {"type": "integer"},
            "tps": {"type": "float"},
            "peak_tps": {"type": "float"},

            # 레이턴시 (ms)
            "p50_ms": {"type": "float"},
            "p95_ms": {"type": "float"},
            "p99_ms": {"type": "float"},
            "max_ms": {"type": "float"},
            "min_ms": {"type": "float"},

            # 에러
            "error_rate": {"type": "keyword"},
            "key_metric": {"type": "keyword"},

            # HTTP 상태 분포 — nested object
            "http_status_dist": {"type": "object"},

            # 시간 범위
            "start_time": {"type": "date"},
            "end_time": {"type": "date"},
            "duration_seconds": {"type": "float"},

            # TPS 타임라인 (배열)
            "tps_timeline": {"type": "nested", "properties": {
                "timestamp": {"type": "keyword"},
                "tps": {"type": "float"},
                "phase": {"type": "keyword"},
            }},

            # 진단 정보 (k6 스크립트 자체 진단)
            "diagnostics_count": {"type": "integer"},

            # 메타
            "indexed_at": {"type": "date"},
        }
    },
    "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 0,
    }
}

BOTTLENECKS_INDEX_MAPPING = {
    "mappings": {
        "properties": {
            # 식별자
            "test_date": {"type": "date", "format": "yyyy-MM-dd"},
            "run_id": {"type": "keyword"},

            # 병목 정보
            "category": {"type": "keyword"},
            "severity": {"type": "keyword"},
            "description": {"type": "text", "analyzer": "standard"},
            "incident_id": {"type": "keyword"},
            "runbook_summary": {"type": "text"},

            # 메트릭 근거
            "metric_name": {"type": "keyword"},
            "threshold": {"type": "float"},
            "actual_value": {"type": "float"},
            "peak_timestamp": {"type": "keyword"},

            # 증거 / 조치
            "evidence": {"type": "text"},
            "recommended_actions": {"type": "text"},

            # 메타
            "indexed_at": {"type": "date"},
        }
    },
    "settings": {
        "number_of_shards": 1,
        "number_of_replicas": 0,
    }
}


# ── ES HTTP 클라이언트 (urllib 기반 — 외부 의존성 없음) ────────

def _es_request(
    es_url: str,
    method: str,
    path: str,
    body: Optional[dict] = None,
    timeout: int = 10,
) -> dict:
    """ES REST API 호출. urllib만 사용하여 외부 의존성 없이 동작."""
    url = f"{es_url.rstrip('/')}/{path.lstrip('/')}"
    data = json.dumps(body).encode("utf-8") if body else None

    req = Request(url, data=data, method=method)
    req.add_header("Content-Type", "application/json")

    try:
        with urlopen(req, timeout=timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except HTTPError as e:
        error_body = e.read().decode("utf-8", errors="replace")
        raise ElasticsearchError(
            f"ES {method} {path} → {e.code}: {error_body}"
        ) from e
    except URLError as e:
        raise ElasticsearchError(
            f"ES 연결 실패 ({es_url}): {e.reason}\n"
            "  → ES 포트가 호스트에 바인딩되어 있는지 확인하세요.\n"
            "  → docker-compose.yml에서 elasticsearch 서비스에\n"
            "    ports: [\"9200:9200\"] 추가가 필요할 수 있습니다."
        ) from e


class ElasticsearchError(Exception):
    """ES 통신 오류."""
    pass


# ── 연결 확인 ─────────────────────────────────────────────────

def check_es_connection(es_url: str) -> bool:
    """Elasticsearch 연결 상태를 확인."""
    try:
        result = _es_request(es_url, "GET", "/")
        return "cluster_name" in result
    except ElasticsearchError:
        return False


def get_es_health(es_url: str) -> dict:
    """ES 클러스터 건강 상태를 조회."""
    try:
        return _es_request(es_url, "GET", "/_cluster/health")
    except ElasticsearchError:
        return {}


# ── 인덱스 생성 ───────────────────────────────────────────────

def ensure_index(es_url: str, index_name: str, mapping: dict) -> bool:
    """인덱스가 없으면 생성. 이미 있으면 무시.

    Returns:
        True: 인덱스가 생성되었거나 이미 존재
        False: 생성 실패
    """
    try:
        _es_request(es_url, "HEAD", f"/{index_name}")
        return True  # 이미 존재
    except ElasticsearchError:
        pass  # 인덱스 없음 → 생성

    try:
        _es_request(es_url, "PUT", f"/{index_name}", body=mapping)
        return True
    except ElasticsearchError as e:
        # "resource_already_exists_exception"은 무시
        if "resource_already_exists" in str(e):
            return True
        print(f"  ⚠ 인덱스 생성 실패 ({index_name}): {e}")
        return False


def ensure_all_indices(es_url: str) -> bool:
    """perf-analyzer 전용 인덱스 2개를 모두 생성."""
    ok1 = ensure_index(es_url, RESULTS_INDEX, RESULTS_INDEX_MAPPING)
    ok2 = ensure_index(es_url, BOTTLENECKS_INDEX, BOTTLENECKS_INDEX_MAPPING)
    return ok1 and ok2


# ── 문서 인덱싱 ───────────────────────────────────────────────

def _generate_run_id(test_date: str) -> str:
    """테스트 날짜 + 현재 시각 기반 run_id 생성.

    같은 날짜에 여러 번 테스트해도 구분 가능.
    """
    now = datetime.now().strftime("%H%M%S")
    return f"{test_date}_{now}"


def index_scenario_result(
    es_url: str,
    scenario: K6ScenarioResult,
    test_date: str,
    run_id: str,
) -> bool:
    """K6ScenarioResult 1건을 perf-test-results에 인덱싱."""
    # tps_timeline을 ES nested 형식으로 변환
    tps_timeline_docs = []
    for item in scenario.tps_timeline:
        if isinstance(item, (list, tuple)) and len(item) >= 3:
            tps_timeline_docs.append({
                "timestamp": str(item[0]),
                "tps": float(item[1]),
                "phase": str(item[2]),
            })

    doc = {
        "scenario": scenario.scenario,
        "test_date": test_date,
        "timestamp": scenario.timestamp or datetime.now().isoformat(),
        "run_id": run_id,
        "passed": scenario.passed,
        "total_requests": scenario.total_requests,
        "tps": scenario.tps,
        "peak_tps": scenario.peak_tps,
        "p50_ms": scenario.p50_ms,
        "p95_ms": scenario.p95_ms,
        "p99_ms": scenario.p99_ms,
        "max_ms": scenario.max_ms,
        "min_ms": scenario.min_ms,
        "error_rate": scenario.error_rate,
        "key_metric": scenario.key_metric,
        "http_status_dist": scenario.http_status_dist,
        "start_time": scenario.start_time or None,
        "end_time": scenario.end_time or None,
        "duration_seconds": scenario.duration_seconds,
        "tps_timeline": tps_timeline_docs,
        "diagnostics_count": len(scenario.diagnostics),
        "indexed_at": datetime.now().isoformat(),
    }

    # doc_id: 같은 시나리오+run_id면 upsert
    doc_id = f"{run_id}_{scenario.scenario}"

    try:
        _es_request(
            es_url, "PUT",
            f"/{RESULTS_INDEX}/_doc/{doc_id}",
            body=doc,
        )
        return True
    except ElasticsearchError as e:
        print(f"  ⚠ 시나리오 인덱싱 실패 ({scenario.scenario}): {e}")
        return False


def index_bottleneck(
    es_url: str,
    bottleneck: Bottleneck,
    test_date: str,
    run_id: str,
    seq: int = 0,
) -> bool:
    """Bottleneck 1건을 perf-bottlenecks에 인덱싱."""
    doc = {
        "test_date": test_date,
        "run_id": run_id,
        "category": bottleneck.category,
        "severity": bottleneck.severity,
        "description": bottleneck.description,
        "incident_id": bottleneck.incident_id,
        "runbook_summary": bottleneck.runbook_summary,
        "metric_name": bottleneck.metric_name,
        "threshold": bottleneck.threshold,
        "actual_value": bottleneck.actual_value,
        "peak_timestamp": bottleneck.peak_timestamp,
        "evidence": "\n".join(bottleneck.evidence) if bottleneck.evidence else "",
        "recommended_actions": "\n".join(bottleneck.recommended_actions) if bottleneck.recommended_actions else "",
        "indexed_at": datetime.now().isoformat(),
    }

    doc_id = f"{run_id}_{bottleneck.category}_{seq}"

    try:
        _es_request(
            es_url, "PUT",
            f"/{BOTTLENECKS_INDEX}/_doc/{doc_id}",
            body=doc,
        )
        return True
    except ElasticsearchError as e:
        print(f"  ⚠ 병목 인덱싱 실패 ({bottleneck.category}): {e}")
        return False


# ── 통합 Export 함수 ──────────────────────────────────────────

def export_to_elasticsearch(
    es_url: str,
    report: AnalysisReport,
) -> dict:
    """AnalysisReport 전체를 Elasticsearch에 적재.

    Args:
        es_url: Elasticsearch URL (예: http://192.168.124.100:9200)
        report: perf-analyzer가 생성한 분석 리포트

    Returns:
        {"results_indexed": int, "bottlenecks_indexed": int, "run_id": str}
    """
    test_date = report.test_date or datetime.now().strftime("%Y-%m-%d")
    run_id = _generate_run_id(test_date)

    # 1. 인덱스 존재 확인 및 생성
    print(f"  📋 인덱스 확인/생성 중...")
    if not ensure_all_indices(es_url):
        raise ElasticsearchError("인덱스 생성 실패")

    # 2. 시나리오 결과 인덱싱
    results_ok = 0
    for scenario in report.scenarios:
        if index_scenario_result(es_url, scenario, test_date, run_id):
            results_ok += 1

    # 3. 병목 인덱싱
    bottlenecks_ok = 0
    for i, bottleneck in enumerate(report.bottlenecks):
        if index_bottleneck(es_url, bottleneck, test_date, run_id, seq=i):
            bottlenecks_ok += 1

    return {
        "results_indexed": results_ok,
        "bottlenecks_indexed": bottlenecks_ok,
        "run_id": run_id,
        "results_index": RESULTS_INDEX,
        "bottlenecks_index": BOTTLENECKS_INDEX,
    }


# ── 조회 유틸 (Kibana 없이 CLI에서 이력 확인) ────────────────

def search_recent_results(
    es_url: str,
    size: int = 10,
    scenario: Optional[str] = None,
) -> list:
    """최근 테스트 결과를 조회.

    Kibana 없이도 CLI에서 이력을 확인할 수 있다.
    """
    query: dict = {"match_all": {}}
    if scenario:
        query = {"term": {"scenario": scenario}}

    body = {
        "query": query,
        "sort": [{"indexed_at": {"order": "desc"}}],
        "size": size,
    }

    try:
        result = _es_request(
            es_url, "POST",
            f"/{RESULTS_INDEX}/_search",
            body=body,
        )
        hits = result.get("hits", {}).get("hits", [])
        return [h["_source"] for h in hits]
    except ElasticsearchError:
        return []


def search_recent_bottlenecks(
    es_url: str,
    size: int = 20,
    severity: Optional[str] = None,
) -> list:
    """최근 병목 이력을 조회."""
    query: dict = {"match_all": {}}
    if severity:
        query = {"term": {"severity": severity}}

    body = {
        "query": query,
        "sort": [{"indexed_at": {"order": "desc"}}],
        "size": size,
    }

    try:
        result = _es_request(
            es_url, "POST",
            f"/{BOTTLENECKS_INDEX}/_search",
            body=body,
        )
        hits = result.get("hits", {}).get("hits", [])
        return [h["_source"] for h in hits]
    except ElasticsearchError:
        return []
