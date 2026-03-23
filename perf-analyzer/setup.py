from setuptools import setup, find_packages

setup(
    name="perf-analyzer",
    version="0.1.0",
    description="k6 부하테스트 결과 + Prometheus 메트릭 → 병목 자동 식별 + 런북 생성",
    author="MinSeok",
    packages=find_packages(),
    python_requires=">=3.9",
    install_requires=[
        "openpyxl>=3.1.0",
        "requests>=2.31.0",
        "pyyaml>=6.0",
    ],
    entry_points={
        "console_scripts": [
            "perf-analyzer=perf_analyzer.cli:main",
        ],
    },
)
