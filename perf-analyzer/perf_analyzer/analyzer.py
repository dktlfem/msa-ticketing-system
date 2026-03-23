"""병목 자동 식별 엔진.

k6 결과와 Prometheus 메트릭을 받아 rules.py의 규칙을 적용하고,
식별된 병목을 severity 순으로 정렬하여 반환한다.
"""

from typing import List

from .models import K6ScenarioResult, InfraMetrics, Bottleneck, AnalysisReport
from .rules import INFRA_RULES, K6_RULES


SEVERITY_ORDER = {"critical": 0, "high": 1, "medium": 2, "low": 3}


def analyze(
    scenarios: List[K6ScenarioResult],
    infra_metrics: List[InfraMetrics],
) -> List[Bottleneck]:
    """k6 결과 + Prometheus 메트릭 → 병목 리스트.

    규칙 적용 순서:
    1. InfraMetrics 기반 규칙 (서비스별)
    2. K6ScenarioResult 기반 규칙 (시나리오별)
    3. 교차 분석: k6 p95 급증 시점과 인프라 이상치의 시간 상관관계

    반환값은 severity 순으로 정렬된다 (critical > high > medium > low).
    """
    bottlenecks = []

    # 1. 인프라 메트릭 기반 규칙
    for metrics in infra_metrics:
        for rule_fn in INFRA_RULES:
            result = rule_fn(metrics)
            if result is not None:
                bottlenecks.append(result)

    # 2. k6 진단 기반 규칙
    for scenario in scenarios:
        for rule_fn in K6_RULES:
            results = rule_fn(scenario)
            if isinstance(results, list):
                bottlenecks.extend(results)
            elif results is not None:
                bottlenecks.append(results)

    # 3. 교차 분석: k6 FAIL 시나리오와 인프라 병목의 시간 상관관계
    failed_scenarios = [s for s in scenarios if not s.passed]
    if failed_scenarios and infra_metrics:
        cross_bottlenecks = _cross_analyze(failed_scenarios, infra_metrics, bottlenecks)
        bottlenecks.extend(cross_bottlenecks)

    # severity 순 정렬
    bottlenecks.sort(key=lambda b: SEVERITY_ORDER.get(b.severity, 99))

    # 중복 제거 (같은 category + service 조합)
    seen = set()
    unique = []
    for b in bottlenecks:
        key = (b.category, b.metric_name, b.incident_id)
        if key not in seen:
            seen.add(key)
            unique.append(b)

    return unique


def _cross_analyze(
    failed_scenarios: List[K6ScenarioResult],
    infra_metrics: List[InfraMetrics],
    existing_bottlenecks: List[Bottleneck],
) -> List[Bottleneck]:
    """k6 실패 시나리오와 인프라 병목의 상관관계를 분석.

    예: k6 시나리오가 FAIL이고, 같은 시간대에 HikariCP pending이 급증했다면,
    "이 시나리오의 실패 원인은 DB 커넥션 풀 고갈일 가능성이 높다"는 교차 진단을 생성.
    """
    cross_results = []

    # 기존 인프라 병목이 있으면 실패 시나리오와 연결
    infra_categories = {b.category for b in existing_bottlenecks}

    for scenario in failed_scenarios:
        if not scenario.start_time or not scenario.end_time:
            continue

        correlations = []
        if "hikaricp" in infra_categories:
            correlations.append("HikariCP 커넥션 풀 고갈")
        if "http_5xx" in infra_categories:
            correlations.append("HTTP 5xx 에러 급증")
        if "latency" in infra_categories:
            correlations.append("응답 시간 SLO 초과")
        if "gc_pressure" in infra_categories:
            correlations.append("JVM GC 압박")

        if correlations:
            cross_results.append(Bottleneck(
                category="cross_analysis",
                severity="high",
                description=(
                    f"시나리오 '{scenario.scenario}' FAIL과 인프라 이상의 상관관계 발견: "
                    f"{', '.join(correlations)}"
                ),
                evidence=[
                    f"시나리오: {scenario.scenario} (FAIL)",
                    f"테스트 시간: {scenario.start_time} ~ {scenario.end_time}",
                    f"동시 발생 인프라 이상: {', '.join(correlations)}",
                ],
                runbook_summary=(
                    f"'{scenario.scenario}' 테스트 실패와 동시에 "
                    f"{', '.join(correlations)}이(가) 발생했습니다. "
                    "인프라 병목이 테스트 실패의 근본 원인일 가능성이 높습니다."
                ),
                recommended_actions=[
                    "위 인프라 병목의 개선 조치를 우선 적용",
                    "조치 후 동일 시나리오 재실행으로 검증",
                    "개선 전/후 수치를 perf-analyzer compare로 비교",
                ],
            ))

    return cross_results


def build_report(
    scenarios: List[K6ScenarioResult],
    infra_metrics: List[InfraMetrics],
    bottlenecks: List[Bottleneck],
    prometheus_url: str = "",
    test_date: str = "",
) -> AnalysisReport:
    """전체 분석 결과를 AnalysisReport로 조합."""
    from datetime import datetime

    return AnalysisReport(
        scenarios=scenarios,
        infra_metrics=infra_metrics,
        bottlenecks=bottlenecks,
        analysis_time=datetime.now().isoformat(),
        prometheus_url=prometheus_url,
        test_date=test_date,
    )
