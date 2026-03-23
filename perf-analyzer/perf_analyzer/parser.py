"""k6 JSON 결과 파서.

generate-summary.py의 load_json_results() + extract_summary_row()를
K6ScenarioResult dataclass로 변환하는 모듈이다.

기존 generate-summary.py와의 차이점:
- scenario1~3만 처리하던 것을 범용 구조로 확장
- 테스트 시작/종료 시간을 추출해 Prometheus 수집에 활용
- diagnostics를 구조화된 객체로 보존
"""

import json
from pathlib import Path
from datetime import datetime, timedelta
from typing import List

from .models import K6ScenarioResult


def load_k6_results(result_dir: str) -> List[K6ScenarioResult]:
    """디렉토리 내 *.json 파일을 로드하여 K6ScenarioResult 리스트를 반환.

    같은 시나리오의 JSON이 여러 개 있으면 timestamp가 가장 최신인 것만 사용한다.
    (generate-summary.py의 동일 로직 재사용)
    """
    result_path = Path(result_dir)
    if not result_path.is_dir():
        raise FileNotFoundError(f"결과 디렉토리 없음: {result_dir}")

    all_results = []
    for f in sorted(result_path.glob("*.json")):
        try:
            with open(f, "r", encoding="utf-8") as fh:
                data = json.load(fh)
            # k6 커스텀 JSON에는 "scenario" 키가 있어야 한다
            if "scenario" in data:
                data["_file"] = f.name
                all_results.append(data)
        except (json.JSONDecodeError, KeyError):
            continue

    # 같은 시나리오가 여러 개면 timestamp가 가장 최신인 것만 유지
    latest = {}
    for r in all_results:
        scenario = r.get("scenario", "unknown")
        ts = r.get("timestamp", "")
        if scenario not in latest or ts > latest[scenario].get("timestamp", ""):
            latest[scenario] = r

    return [_parse_single(data) for data in latest.values()]


def _parse_single(data: dict) -> K6ScenarioResult:
    """JSON dict 1건을 K6ScenarioResult로 변환."""
    scenario = data.get("scenario", "unknown")
    timestamp = data.get("timestamp", "")
    results = data.get("results", {})
    latency = data.get("latency", {})
    diagnostics = data.get("diagnostics", [])

    # 테스트 시간 범위 추정
    # k6 JSON의 timestamp가 테스트 종료 시각이라고 가정
    # phases의 duration을 합산해서 시작 시각을 역산
    start_time, end_time = _estimate_time_range(timestamp, data.get("phases", {}))

    # 시나리오별 핵심 지표 추출 (generate-summary.py 로직 확장)
    p50, p95, p99, max_ms, min_ms, error_rate, tps, key_metric = _extract_key_metrics(
        scenario, results, latency
    )

    # phases duration 합산 → duration_seconds
    duration_seconds = sum(
        _parse_duration(p.get("duration", "0s"))
        for p in data.get("phases", {}).values()
    )

    # HTTP 상태 코드 분포 (results에서 추출)
    http_status_dist = _extract_http_status_dist(results, scenario)

    # TPS timeline (phases별 추정 TPS)
    tps_timeline = _extract_tps_timeline(data.get("phases", {}), results, timestamp)

    # iteration duration (results에서 추출)
    iteration_duration_avg_ms = results.get("iterationDurationAvgMs", 0)

    # peak TPS (burst 구간의 avgRps 또는 전체 TPS의 1.5배 추정)
    peak_tps = results.get("avgRps", tps * 1.5 if tps else 0)

    return K6ScenarioResult(
        scenario=scenario,
        timestamp=timestamp,
        passed=data.get("pass", False),
        total_requests=results.get("totalRequests", 0),
        p50_ms=p50,
        p95_ms=p95,
        p99_ms=p99,
        max_ms=max_ms,
        min_ms=min_ms,
        error_rate=error_rate,
        tps=tps,
        peak_tps=peak_tps,
        key_metric=key_metric,
        http_status_dist=http_status_dist,
        tps_timeline=tps_timeline,
        iteration_duration_avg_ms=iteration_duration_avg_ms,
        raw=data,
        start_time=start_time,
        end_time=end_time,
        duration_seconds=duration_seconds,
        diagnostics=diagnostics,
    )


def _extract_key_metrics(scenario: str, results: dict, latency: dict):
    """시나리오별 핵심 지표를 추출.

    generate-summary.py의 extract_summary_row() 로직을 그대로 가져오되,
    scenario1~3 외의 시나리오도 범용 처리한다.

    반환: (p50, p95, p99, max_ms, min_ms, error_rate, tps, key_metric)
    """
    # latency 전체에서 P50/max/min 집계 (모든 시나리오 공통)
    all_p50 = [v.get("p50", 0) for v in latency.values() if isinstance(v, dict)]
    all_p95 = [v.get("p95", 0) for v in latency.values() if isinstance(v, dict)]
    all_p99 = [v.get("p99", 0) for v in latency.values() if isinstance(v, dict)]
    all_max = [v.get("max", 0) for v in latency.values() if isinstance(v, dict)]
    all_min = [v.get("min", 0) for v in latency.values() if isinstance(v, dict) and v.get("min", 0) > 0]

    if scenario == "scenario1-rate-limiter":
        p50 = latency.get("allowed", {}).get("p50", 0)
        p95 = latency.get("allowed", {}).get("p95", 0)
        p99 = latency.get("allowed", {}).get("p99", 0)
        max_ms = latency.get("allowed", {}).get("max", max(all_max, default=0))
        min_ms = latency.get("allowed", {}).get("min", min(all_min, default=0))
        rl_pct = results.get("rateLimitedPercent", 0)
        error_rate = f"{rl_pct}% (429)"
        tps = results.get("avgAllowedTps", 0)
        key_metric = f"차단율 {rl_pct}%"

    elif scenario == "scenario2-circuit-breaker":
        p50 = latency.get("closed", {}).get("p50", 0)
        p95 = latency.get("closed", {}).get("p95", 0)
        p99 = latency.get("closed", {}).get("p99", 0)
        max_ms = latency.get("closed", {}).get("max", max(all_max, default=0))
        min_ms = latency.get("closed", {}).get("min", min(all_min, default=0))
        fb_pct = results.get("fallbackPercent", 0)
        error_rate = f"{fb_pct}% (fallback)"
        total = results.get("totalRequests", 1)
        tps = round(total / 105, 2) if total else 0
        key_metric = f"FB {results.get('fallbackTotal', 0)}건"

    elif scenario == "scenario3-bulkhead":
        p50 = latency.get("passed", {}).get("p50", 0)
        p95 = latency.get("passed", {}).get("p95", 0)
        p99 = latency.get("passed", {}).get("p99", 0)
        max_ms = latency.get("passed", {}).get("max", max(all_max, default=0))
        min_ms = latency.get("passed", {}).get("min", min(all_min, default=0))
        rej_pct = results.get("bulkheadRejectedPercent", 0)
        error_rate = f"{rej_pct}% (rejected)"
        total = results.get("totalRequests", 1)
        tps = round(total / 80, 2) if total else 0
        key_metric = f"BH거절 {results.get('bulkheadRejected', 0)}건"

    else:
        # 범용 처리: latency 최상위 키 중 첫 번째에서 추출
        first_latency = next(iter(latency.values()), {}) if latency else {}
        p50 = first_latency.get("p50", 0)
        p95 = first_latency.get("p95", 0)
        p99 = first_latency.get("p99", 0)
        max_ms = first_latency.get("max", max(all_max, default=0))
        min_ms = first_latency.get("min", min(all_min, default=0))
        total = results.get("totalRequests", 0)
        error_count = results.get("errorCount", results.get("unexpectedCount", 0))
        error_pct = round(error_count / total * 100, 2) if total > 0 else 0
        error_rate = f"{error_pct}%"
        tps = 0
        key_metric = f"총 {total}건"

    return p50, p95, p99, max_ms, min_ms, error_rate, tps, key_metric


def _extract_http_status_dist(results: dict, scenario: str) -> dict:
    """k6 results에서 HTTP 상태 코드 분포를 추출.

    시나리오별 결과 필드명이 다르므로 매핑한다.
    """
    dist = {}

    if scenario == "scenario1-rate-limiter":
        allowed = results.get("allowedCount", 0)
        rate_limited = results.get("rateLimitedCount", 0)
        unexpected = results.get("unexpectedCount", 0)
        if allowed:
            dist["200"] = allowed
        if rate_limited:
            dist["429"] = rate_limited
        if unexpected:
            dist["5xx"] = unexpected

    elif scenario == "scenario2-circuit-breaker":
        closed_pass = results.get("closedPassCount", 0)
        fallback = results.get("fallbackTotal", 0)
        upstream_err = results.get("upstreamErrorCount", 0)
        unexpected = results.get("unexpectedCount", 0)
        if closed_pass:
            dist["200"] = closed_pass
        if fallback:
            dist["503_fallback"] = fallback
        if upstream_err:
            dist["5xx_upstream"] = upstream_err
        if unexpected:
            dist["unexpected"] = unexpected

    elif scenario == "scenario3-bulkhead":
        passed = results.get("passedCount", 0)
        rejected = results.get("bulkheadRejected", 0)
        if passed:
            dist["200"] = passed
        if rejected:
            dist["503_rejected"] = rejected

    else:
        # 범용: 전체 요청에서 에러 분리
        total = results.get("totalRequests", 0)
        errors = results.get("errorCount", results.get("unexpectedCount", 0))
        if total - errors > 0:
            dist["200"] = total - errors
        if errors:
            dist["error"] = errors

    return dist


def _extract_tps_timeline(phases: dict, results: dict, timestamp: str) -> list:
    """phases별 추정 TPS timeline을 생성.

    실제 시계열 TPS는 k6 CSV/메트릭에서만 가능하지만,
    phases 구간별 평균 TPS를 추정하여 근사치를 제공한다.
    """
    timeline = []
    if not phases or not timestamp:
        return timeline

    try:
        end_dt = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return timeline

    total_requests = results.get("totalRequests", 0)
    total_duration = sum(_parse_duration(p.get("duration", "0s")) for p in phases.values())
    if total_duration == 0:
        return timeline

    # phases를 시간 역순으로 계산
    current_end = end_dt
    for phase_name, phase_data in reversed(list(phases.items())):
        dur_secs = _parse_duration(phase_data.get("duration", "0s"))
        if dur_secs == 0:
            continue
        phase_start = current_end - timedelta(seconds=dur_secs)

        # VU 수와 sleep으로 대략적 TPS 추정
        vus = phase_data.get("vus", 1)
        sleep_ms = phase_data.get("sleepMs", phase_data.get("sleep", 0))
        if isinstance(sleep_ms, str):
            sleep_ms = int(sleep_ms.replace("ms", "")) if "ms" in sleep_ms else 0
        cycle_time_ms = max(sleep_ms + 10, 100)  # 최소 요청 시간 10ms 가정
        estimated_tps = vus * 1000 / cycle_time_ms

        timeline.append((phase_start.isoformat(), round(estimated_tps, 1), phase_name))
        current_end = phase_start

    timeline.reverse()
    return timeline


def _estimate_time_range(timestamp: str, phases: dict) -> tuple:
    """테스트 시작/종료 시각을 추정.

    k6 JSON의 timestamp를 종료 시각으로 보고,
    phases의 duration을 합산해서 시작 시각을 역산한다.
    Prometheus 쿼리의 시간 범위로 사용한다.
    """
    if not timestamp:
        return ("", "")

    try:
        # ISO 형식 파싱
        end_dt = datetime.fromisoformat(timestamp.replace("Z", "+00:00"))
    except (ValueError, AttributeError):
        return (timestamp, timestamp)

    total_seconds = 0
    for phase_data in phases.values():
        dur_str = phase_data.get("duration", "0s")
        total_seconds += _parse_duration(dur_str)

    if total_seconds == 0:
        total_seconds = 300  # 기본 5분 추정

    start_dt = end_dt - timedelta(seconds=total_seconds)

    return (start_dt.isoformat(), end_dt.isoformat())


def _parse_duration(dur_str: str) -> int:
    """k6 duration 문자열("30s", "2m", "1h")을 초 단위로 변환."""
    dur_str = dur_str.strip()
    if dur_str.endswith("s"):
        return int(dur_str[:-1])
    elif dur_str.endswith("m"):
        return int(dur_str[:-1]) * 60
    elif dur_str.endswith("h"):
        return int(dur_str[:-1]) * 3600
    try:
        return int(dur_str)
    except ValueError:
        return 0
