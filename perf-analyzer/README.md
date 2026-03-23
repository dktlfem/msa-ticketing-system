# perf-analyzer

k6 부하테스트 결과를 Prometheus 인프라 메트릭과 자동 연결하여 병목을 식별하고, 대응 런북을 생성하는 CLI 도구.

## 설치

```bash
pip install -e .
```

## 사용법

```bash
# 1. k6 결과 파싱 — 핵심 지표 추출
perf-analyzer parse ./results/2026-03-19/

# 2. Prometheus 메트릭 수집 — 테스트 시간대 인프라 지표
perf-analyzer collect --prometheus http://192.168.124.100:8080/prometheus \
                      --start 2026-03-19T09:00:00Z \
                      --end   2026-03-19T09:10:00Z

# 3. 병목 자동 분석 — k6 + Prometheus 상관관계
perf-analyzer analyze ./results/2026-03-19/

# 4. 통합 리포트 — Excel + 마크다운 런북
perf-analyzer report ./results/2026-03-19/ -o ./reports/
```

## 아키텍처

```
perf-analyzer/
├── perf_analyzer/
│   ├── __init__.py
│   ├── cli.py              # CLI 진입점 (argparse)
│   ├── parser.py            # k6 JSON 결과 파서
│   ├── collector.py         # Prometheus API 수집기
│   ├── analyzer.py          # 병목 식별 규칙 엔진
│   ├── reporter.py          # Excel + 마크다운 리포트 생성
│   ├── rules.py             # 병목 판단 규칙 정의
│   └── models.py            # 데이터 모델 (dataclass)
├── rules/
│   └── default.yaml         # 기본 병목 판단 규칙
├── setup.py
├── requirements.txt
└── README.md
```
