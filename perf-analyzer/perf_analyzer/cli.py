"""perf-analyzer CLI 진입점.

사용법:
  perf-analyzer parse   <result_dir>          — k6 JSON 결과 파싱
  perf-analyzer collect --prometheus <url> ... — Prometheus 메트릭 수집
  perf-analyzer analyze <result_dir> [...]     — 병목 자동 식별
  perf-analyzer report  <result_dir> [...]     — 통합 리포트 생성
  perf-analyzer export  <result_dir> [...]     — ES에 분석 결과 적재
  perf-analyzer history --es-url <url>         — ES에서 과거 결과 조회
"""

import argparse
import json
import sys
from datetime import datetime
from pathlib import Path


def cmd_parse(args):
    """k6 JSON 결과를 파싱하여 핵심 지표를 출력."""
    from .parser import load_k6_results

    try:
        results = load_k6_results(args.result_dir)
    except FileNotFoundError as e:
        print(f"❌ {e}")
        sys.exit(1)

    if not results:
        print(f"⚠  JSON 결과 파일 없음: {args.result_dir}")
        sys.exit(1)

    print(f"📊 {len(results)}개 시나리오 파싱 완료\n")

    for s in results:
        verdict = "✅ PASS" if s.passed else "❌ FAIL"
        print(f"  {verdict}  {s.scenario}")
        print(f"       요청: {s.total_requests:,}건 | P95: {s.p95_ms:.1f}ms | "
              f"P99: {s.p99_ms:.1f}ms | TPS: {s.tps:.1f}")
        print(f"       에러율: {s.error_rate} | 핵심: {s.key_metric}")
        if s.start_time:
            print(f"       시간: {s.start_time} ~ {s.end_time}")
        if s.diagnostics:
            print(f"       진단: {len(s.diagnostics)}건")
            for d in s.diagnostics:
                print(f"         ⚠ {d.get('symptom', '')}")
        print()

    if args.json:
        output = [
            {
                "scenario": s.scenario,
                "passed": s.passed,
                "total_requests": s.total_requests,
                "p95_ms": s.p95_ms,
                "p99_ms": s.p99_ms,
                "error_rate": s.error_rate,
                "tps": s.tps,
                "start_time": s.start_time,
                "end_time": s.end_time,
            }
            for s in results
        ]
        print(json.dumps(output, indent=2, ensure_ascii=False))


def cmd_collect(args):
    """Prometheus에서 인프라 메트릭을 수집."""
    from .collector import collect_metrics, check_prometheus_connection

    prometheus_url = args.prometheus
    print(f"🔗 Prometheus 연결 확인: {prometheus_url}")

    if not check_prometheus_connection(prometheus_url):
        print(f"❌ Prometheus 연결 실패: {prometheus_url}")
        print(f"   VPN 연결 상태와 Prometheus URL을 확인하세요.")
        sys.exit(1)

    print(f"✅ Prometheus 연결 성공\n")
    print(f"📡 메트릭 수집 중...")
    print(f"   시간 범위: {args.start} ~ {args.end}")
    print(f"   수집 간격: {args.step}")

    services = args.services.split(",") if args.services else None
    metrics_list = collect_metrics(
        prometheus_url=prometheus_url,
        start_time=args.start,
        end_time=args.end,
        services=services,
        step=args.step,
    )

    print(f"\n📊 {len(metrics_list)}개 서비스 메트릭 수집 완료\n")

    for m in metrics_list:
        data_points = len(m.hikari_active) + len(m.http_5xx_rate)
        print(f"  {m.service}: {data_points}개 데이터 포인트")

    # JSON으로 저장
    if args.output:
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        # dataclass를 dict로 변환해서 저장
        import dataclasses
        data = [dataclasses.asdict(m) for m in metrics_list]
        output_path.write_text(json.dumps(data, indent=2, default=str), encoding="utf-8")
        print(f"\n💾 저장: {output_path}")


def cmd_analyze(args):
    """k6 결과 + Prometheus 메트릭 → 병목 자동 식별."""
    from .parser import load_k6_results
    from .collector import collect_metrics, check_prometheus_connection
    from .analyzer import analyze

    # 1. k6 결과 파싱
    try:
        scenarios = load_k6_results(args.result_dir)
    except FileNotFoundError as e:
        print(f"❌ {e}")
        sys.exit(1)

    if not scenarios:
        print(f"⚠  JSON 결과 없음: {args.result_dir}")
        sys.exit(1)

    print(f"📊 {len(scenarios)}개 시나리오 로드\n")

    # 2. Prometheus 메트릭 수집 (선택)
    infra_metrics = []
    if args.prometheus:
        if check_prometheus_connection(args.prometheus):
            # 시나리오들의 시간 범위를 합산
            start = min((s.start_time for s in scenarios if s.start_time), default="")
            end = max((s.end_time for s in scenarios if s.end_time), default="")

            if start and end:
                print(f"📡 Prometheus 메트릭 수집: {start} ~ {end}")
                infra_metrics = collect_metrics(args.prometheus, start, end)
                print(f"   {len(infra_metrics)}개 서비스 수집 완료\n")
        else:
            print(f"⚠  Prometheus 연결 실패 — k6 진단만으로 분석합니다\n")

    # 3. 병목 분석
    print(f"🔍 병목 분석 중...\n")
    bottlenecks = analyze(scenarios, infra_metrics)

    if bottlenecks:
        print(f"🚨 {len(bottlenecks)}개 병목 식별\n")
        for i, b in enumerate(bottlenecks, 1):
            severity_icon = {"critical": "🔴", "high": "🟠", "medium": "🟡", "low": "🟢"}.get(b.severity, "⚪")
            print(f"  {i}. {severity_icon} [{b.severity.upper()}] {b.category}")
            print(f"     {b.description}")
            if b.incident_id:
                print(f"     📋 런북: {b.incident_id}")
            if b.recommended_actions:
                print(f"     💡 조치: {b.recommended_actions[0]}")
            print()
    else:
        print(f"✅ 식별된 병목 없음 — 모든 지표가 정상 범위입니다\n")

    # JSON 출력
    if args.json:
        import dataclasses
        data = [dataclasses.asdict(b) for b in bottlenecks]
        print(json.dumps(data, indent=2, ensure_ascii=False))


def cmd_report(args):
    """통합 리포트 생성 (Excel + 마크다운)."""
    from .parser import load_k6_results
    from .collector import collect_metrics, check_prometheus_connection
    from .analyzer import analyze, build_report
    from .reporter import generate_markdown_report, generate_excel_report

    # 1. k6 결과 파싱
    try:
        scenarios = load_k6_results(args.result_dir)
    except FileNotFoundError as e:
        print(f"❌ {e}")
        sys.exit(1)

    if not scenarios:
        print(f"⚠  JSON 결과 없음: {args.result_dir}")
        sys.exit(1)

    print(f"📊 {len(scenarios)}개 시나리오 로드")

    # 2. Prometheus 메트릭 수집 (선택)
    infra_metrics = []
    if args.prometheus:
        if check_prometheus_connection(args.prometheus):
            start = min((s.start_time for s in scenarios if s.start_time), default="")
            end = max((s.end_time for s in scenarios if s.end_time), default="")
            if start and end:
                print(f"📡 Prometheus 수집: {start} ~ {end}")
                infra_metrics = collect_metrics(args.prometheus, start, end)

    # 3. 분석
    bottlenecks = analyze(scenarios, infra_metrics)
    print(f"🔍 {len(bottlenecks)}개 병목 식별")

    # 4. 리포트 생성
    report = build_report(
        scenarios=scenarios,
        infra_metrics=infra_metrics,
        bottlenecks=bottlenecks,
        prometheus_url=args.prometheus or "",
        test_date=datetime.now().strftime("%Y-%m-%d"),
    )

    output_dir = args.output or str(Path(args.result_dir) / "reports")

    md_path = generate_markdown_report(report, output_dir)
    print(f"📝 마크다운 리포트: {md_path}")

    xlsx_path = generate_excel_report(report, output_dir)
    print(f"📊 Excel 리포트: {xlsx_path}")

    # ES 적재 (--es-url 옵션이 있으면 자동 실행)
    if args.es_url:
        _do_es_export(args.es_url, report)

    print(f"\n✅ 리포트 생성 완료: {output_dir}/")


def _do_es_export(es_url: str, report):
    """ES 적재 공통 로직 — report 명령과 export 명령에서 공유."""
    from .exporter import check_es_connection, export_to_elasticsearch, ElasticsearchError

    print(f"\n🔗 Elasticsearch 연결: {es_url}")

    if not check_es_connection(es_url):
        print(f"  ⚠ ES 연결 실패 — 적재를 건너뜁니다.")
        print(f"  → ES URL이 올바른지 확인하세요.")
        print(f"  → docker-compose.yml에서 ports: [\"9200:9200\"] 바인딩이 필요할 수 있습니다.")
        return

    print(f"  ✅ ES 연결 성공")

    try:
        result = export_to_elasticsearch(es_url, report)
        print(f"  📤 시나리오 결과: {result['results_indexed']}건 적재")
        print(f"  📤 병목 분석:     {result['bottlenecks_indexed']}건 적재")
        print(f"  📋 run_id:       {result['run_id']}")
        print(f"  📋 인덱스:       {result['results_index']}, {result['bottlenecks_index']}")
    except ElasticsearchError as e:
        print(f"  ⚠ ES 적재 실패: {e}")


def cmd_export(args):
    """분석 결과를 Elasticsearch에 적재."""
    from .parser import load_k6_results
    from .collector import collect_metrics, check_prometheus_connection
    from .analyzer import analyze, build_report

    # 1. k6 결과 파싱
    try:
        scenarios = load_k6_results(args.result_dir)
    except FileNotFoundError as e:
        print(f"❌ {e}")
        sys.exit(1)

    if not scenarios:
        print(f"⚠  JSON 결과 없음: {args.result_dir}")
        sys.exit(1)

    print(f"📊 {len(scenarios)}개 시나리오 로드")

    # 2. Prometheus 메트릭 수집 (선택)
    infra_metrics = []
    if args.prometheus:
        if check_prometheus_connection(args.prometheus):
            start = min((s.start_time for s in scenarios if s.start_time), default="")
            end = max((s.end_time for s in scenarios if s.end_time), default="")
            if start and end:
                print(f"📡 Prometheus 수집: {start} ~ {end}")
                infra_metrics = collect_metrics(args.prometheus, start, end)

    # 3. 분석
    bottlenecks = analyze(scenarios, infra_metrics)
    print(f"🔍 {len(bottlenecks)}개 병목 식별")

    # 4. 리포트 빌드
    report = build_report(
        scenarios=scenarios,
        infra_metrics=infra_metrics,
        bottlenecks=bottlenecks,
        prometheus_url=args.prometheus or "",
        test_date=datetime.now().strftime("%Y-%m-%d"),
    )

    # 5. ES 적재
    _do_es_export(args.es_url, report)


def cmd_history(args):
    """Elasticsearch에서 과거 테스트 결과를 조회."""
    from .exporter import (
        check_es_connection, search_recent_results, search_recent_bottlenecks,
    )

    es_url = args.es_url
    print(f"🔗 Elasticsearch: {es_url}")

    if not check_es_connection(es_url):
        print(f"❌ ES 연결 실패: {es_url}")
        sys.exit(1)

    if args.type in ("results", "all"):
        print(f"\n📊 최근 테스트 결과 (최대 {args.size}건):\n")
        results = search_recent_results(es_url, size=args.size, scenario=args.scenario)

        if not results:
            print("  (결과 없음)")
        else:
            for r in results:
                verdict = "✅" if r.get("passed") else "❌"
                print(f"  {verdict} {r.get('test_date', '?')} | "
                      f"{r.get('scenario', '?')} | "
                      f"요청: {r.get('total_requests', 0):,} | "
                      f"P95: {r.get('p95_ms', 0):.1f}ms | "
                      f"TPS: {r.get('tps', 0):.1f} | "
                      f"에러: {r.get('error_rate', '?')}")

    if args.type in ("bottlenecks", "all"):
        print(f"\n🚨 최근 병목 이력 (최대 {args.size}건):\n")
        bottlenecks = search_recent_bottlenecks(es_url, size=args.size, severity=args.severity)

        if not bottlenecks:
            print("  (병목 없음)")
        else:
            for b in bottlenecks:
                severity_icon = {
                    "critical": "🔴", "high": "🟠", "medium": "🟡", "low": "🟢"
                }.get(b.get("severity", ""), "⚪")
                print(f"  {severity_icon} {b.get('test_date', '?')} | "
                      f"[{b.get('severity', '?').upper()}] {b.get('category', '?')} | "
                      f"{b.get('description', '')[:80]}")
                if b.get("incident_id"):
                    print(f"     📋 {b['incident_id']}")

    # JSON 출력
    if args.json:
        all_data = {}
        if args.type in ("results", "all"):
            all_data["results"] = search_recent_results(es_url, size=args.size, scenario=args.scenario)
        if args.type in ("bottlenecks", "all"):
            all_data["bottlenecks"] = search_recent_bottlenecks(es_url, size=args.size, severity=args.severity)
        print(f"\n{json.dumps(all_data, indent=2, ensure_ascii=False)}")


def main():
    parser = argparse.ArgumentParser(
        prog="perf-analyzer",
        description="k6 부하테스트 분석 + 병목 식별 + 런북 생성",
    )
    subparsers = parser.add_subparsers(dest="command", help="사용할 명령어")

    # ── parse ──
    p_parse = subparsers.add_parser("parse", help="k6 JSON 결과 파싱")
    p_parse.add_argument("result_dir", help="k6 결과 디렉토리 경로")
    p_parse.add_argument("--json", action="store_true", help="JSON 형식으로 출력")
    p_parse.set_defaults(func=cmd_parse)

    # ── collect ──
    p_collect = subparsers.add_parser("collect", help="Prometheus 메트릭 수집")
    p_collect.add_argument("--prometheus", required=True, help="Prometheus URL")
    p_collect.add_argument("--start", required=True, help="시작 시각 (ISO)")
    p_collect.add_argument("--end", required=True, help="종료 시각 (ISO)")
    p_collect.add_argument("--step", default="15s", help="수집 간격 (기본: 15s)")
    p_collect.add_argument("--services", help="서비스 목록 (콤마 구분)")
    p_collect.add_argument("--output", "-o", help="JSON 저장 경로")
    p_collect.set_defaults(func=cmd_collect)

    # ── analyze ──
    p_analyze = subparsers.add_parser("analyze", help="병목 자동 식별")
    p_analyze.add_argument("result_dir", help="k6 결과 디렉토리 경로")
    p_analyze.add_argument("--prometheus", help="Prometheus URL (없으면 k6 진단만 사용)")
    p_analyze.add_argument("--json", action="store_true", help="JSON 형식으로 출력")
    p_analyze.set_defaults(func=cmd_analyze)

    # ── report ──
    p_report = subparsers.add_parser("report", help="통합 리포트 생성")
    p_report.add_argument("result_dir", help="k6 결과 디렉토리 경로")
    p_report.add_argument("--prometheus", help="Prometheus URL")
    p_report.add_argument("--output", "-o", help="출력 디렉토리")
    p_report.add_argument("--es-url", help="Elasticsearch URL (지정 시 ES에도 자동 적재)")
    p_report.set_defaults(func=cmd_report)

    # ── export ──
    p_export = subparsers.add_parser("export", help="분석 결과를 Elasticsearch에 적재")
    p_export.add_argument("result_dir", help="k6 결과 디렉토리 경로")
    p_export.add_argument("--es-url", required=True, help="Elasticsearch URL")
    p_export.add_argument("--prometheus", help="Prometheus URL")
    p_export.set_defaults(func=cmd_export)

    # ── history ──
    p_history = subparsers.add_parser("history", help="ES에서 과거 테스트 이력 조회")
    p_history.add_argument("--es-url", required=True, help="Elasticsearch URL")
    p_history.add_argument("--type", choices=["results", "bottlenecks", "all"], default="all",
                           help="조회 대상 (기본: all)")
    p_history.add_argument("--size", type=int, default=10, help="조회 건수 (기본: 10)")
    p_history.add_argument("--scenario", help="특정 시나리오만 필터")
    p_history.add_argument("--severity", help="특정 심각도만 필터 (critical/high/medium/low)")
    p_history.add_argument("--json", action="store_true", help="JSON 형식으로 출력")
    p_history.set_defaults(func=cmd_history)

    args = parser.parse_args()
    if not args.command:
        parser.print_help()
        sys.exit(0)

    args.func(args)


if __name__ == "__main__":
    main()
