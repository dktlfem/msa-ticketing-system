# 첫 B2B 제품 후보 비교 분석

> **작성일**: 2026-03-22
> **기준**: ci-cd-test 프로젝트 코드·문서·산출물 실물 분석
> **목적**: 두 제품 후보 중 "작게 시작 가능한 첫 제품"을 근거 기반으로 선정

---

## 1. 제품 한 줄 정의

**제품 A — Spring MSA 운영 스타터 + 대시보드/문서 자동화**
Spring Boot MSA 프로젝트에 공통 모듈(에러 처리, 페이징, 헤더 전파, 관측성 설정)을 starter로 제공하고, 코드를 스캔해 런북·API 명세·아키텍처 다이어그램을 자동 생성하는 개발자 도구.

**제품 B — 부하테스트/폭주대응 분석 도구**
k6 부하테스트 결과를 자동 수집·시각화하고, Prometheus/Grafana/Jaeger 메트릭과 결합해 병목 구간을 자동 식별하며, 폭주 시나리오별 대응 런북을 생성하는 성능 분석 플랫폼.

---

## 2. 타깃 사용자

| 기준 | 제품 A | 제품 B |
|------|--------|--------|
| **1차 타깃** | 초기 MSA 전환 중인 스타트업 백엔드 팀 (3~10명) | 부하테스트를 시작했지만 결과 해석에 막힌 백엔드/SRE 팀 |
| **구매 결정자** | 테크리드, CTO | SRE 리드, 백엔드 테크리드 |
| **페르소나** | "MSA 전환했는데 에러 처리, 헤더 전파, 런북을 매번 수동으로 만든다" | "k6 돌렸는데 CSV만 쌓이고 뭐가 병목인지 모르겠다" |
| **시장 규모** | Spring Boot MSA 한국 스타트업 ~500개+ | k6/JMeter 사용 팀, 전 세계 DevOps 엔지니어 |
| **경쟁** | Spring Initializr, JHipster, Backstage | Grafana k6 Cloud, Datadog APM, Artillery |

---

## 3. 사용자가 겪는 핵심 문제

### 제품 A — MSA 운영 스타터

1. **반복 보일러플레이트**: 새 서비스마다 GlobalExceptionHandler, ErrorCode Enum, ResponseWrapper, JPA Auditing, Redis Config를 복사해야 한다. 현재 프로젝트에서 `common-module/`이 이 역할을 하지만, 프로젝트 밖으로 재사용이 불가하다.
2. **운영 문서 수동 작성**: 현재 docs/ 폴더에 42개 마크다운이 있고, `_reports/`에 13개 변경 이력이 쌓여 있다. 코드 변경 시마다 문서를 수동으로 동기화해야 하며, `_reports/code-doc-consistency.md`가 이 문제를 직접 기록하고 있다.
3. **관측성 설정 파편화**: 6개 서비스 모두 `OTEL_SERVICE_NAME`, `OTEL_EXPORTER_OTLP_ENDPOINT`, `management.metrics.tags.application`을 개별 설정한다. docker-compose.yml에서 서비스마다 환경변수를 반복 선언한다.

### 제품 B — 부하테스트/폭주대응 분석 도구

1. **결과 해석 부재**: `load-test/scripts/k6/results/`에 CSV/JSON/HTML이 쌓이지만, "어디가 병목인가"를 자동으로 알려주지 않는다. `generate-summary.py`가 Excel 요약을 만들지만 scenario1~3만 처리하고, 성능 테스트 런북(`performance-test-runbook.md`)의 4가지 핵심 시나리오(좌석 경쟁, 결제 중복, E2E, SCG throughput)는 아직 k6 스크립트조차 없다.
2. **메트릭 연결 단절**: k6 결과(HTTP p95, 에러율)와 인프라 메트릭(HikariCP pending, Redis latency, Tomcat threads)이 별개 도구에 흩어져 있다. `capacity-planning.md`에서 "1순위 병목: Redis, 2순위: HikariCP"를 예측했지만, 실제 k6 결과와 Prometheus 메트릭을 연결해 검증한 기록이 없다.
3. **폭주 대응 런북 부재**: `incident-runbook.md`에 INC-001~009 + 추가 9개 시나리오가 있지만, 부하테스트 결과에서 자동으로 "이 패턴이면 INC-008(Optimistic Lock 폭증)"이라고 연결해주는 도구가 없다.

---

## 4. 현재 내 자산 중 바로 재사용 가능한 것

### 제품 A — MSA 운영 스타터

| 자산 | 위치 | 재사용 수준 | 비고 |
|------|------|-----------|------|
| GlobalExceptionHandler | `common-module/.../error/` | ⭐⭐⭐⭐⭐ | ErrorCode Enum + ErrorResponse 포맷 완성 |
| PageResponse 페이징 래퍼 | `common-module/.../api/pagination/` | ⭐⭐⭐⭐⭐ | PageMeta, SortMeta 포함 |
| JPA Auditing Config | `common-module/.../config/JpaAuditingConfig.java` | ⭐⭐⭐⭐⭐ | @CreatedDate, @LastModifiedDate |
| Redis Config | `payment-app/.../config/RedisConfig.java` | ⭐⭐⭐⭐ | Idempotency 패턴 포함 |
| RestClient Config | `payment-app/.../config/RestClientConfig.java` | ⭐⭐⭐⭐ | connect 3s / read 10s 타임아웃 |
| Gateway 헤더 전파 | `common-module/.../gateway/` | ⭐⭐⭐⭐ | GatewayHeaders, UserPassport, PassportCodec |
| 관측성 설정 패턴 | docker-compose.yml OTEL 환경변수 | ⭐⭐⭐ | 6개 서비스 OTLP 설정 템플릿 |
| API 명세 문서 구조 | `docs/api/api-spec.md` | ⭐⭐⭐ | 700행 API 카탈로그 구조 |
| 런북 구조 | `docs/operations/incident-runbook.md` | ⭐⭐⭐⭐ | INC-001~009 증상→로그→즉시조치→후속조치 템플릿 |
| ADR 템플릿 | `docs/architecture/adr/0000-template.md` | ⭐⭐⭐⭐ | 7개 ADR 실례 포함 |
| SLI/SLO 템플릿 | `docs/performance/sli-slo.md` | ⭐⭐⭐ | PromQL 쿼리 포함 |
| 배포 체크리스트 | `docs/operations/release-rollback-checklist.md` | ⭐⭐⭐⭐ | Blue/Green + 서비스별 체크 |

### 제품 B — 부하테스트/폭주대응 분석 도구

| 자산 | 위치 | 재사용 수준 | 비고 |
|------|------|-----------|------|
| k6 시나리오 7개 | `load-test/scripts/k6/scenario1~7.js` | ⭐⭐⭐⭐ | rate-limiter, circuit-breaker, bulkhead, filter-latency, jwt-attack, soak, internal-block |
| run-tests.sh | `load-test/scripts/k6/run-tests.sh` | ⭐⭐⭐ | 실행+결과 디렉토리 자동 생성, InfluxDB export |
| generate-summary.py | `load-test/scripts/k6/generate-summary.py` | ⭐⭐⭐⭐ | JSON→Excel 변환, 판정 색상, 시나리오별 시트 |
| Grafana k6 대시보드 | `load-test/scripts/k6/grafana/k6-dashboard.json` | ⭐⭐⭐⭐ | Grafana JSON 임포트 가능 |
| 성능 테스트 런북 | `docs/performance/performance-test-runbook.md` | ⭐⭐⭐⭐⭐ | 4개 시나리오 + fixture SQL + 검증 기준 |
| 용량 계획 분석 | `docs/performance/capacity-planning.md` | ⭐⭐⭐⭐ | 병목 순서 예측 + PromQL + scale-up 기준 |
| SLI/SLO 정의 | `docs/performance/sli-slo.md` | ⭐⭐⭐ | proposed 상태, PromQL 템플릿 |
| 장애 매트릭스 | `docs/operations/incident-runbook.md` | ⭐⭐⭐⭐ | 장애↔메트릭↔로그 매핑 |
| PromQL 쿼리 라이브러리 | `docs/observability/observability.md` | ⭐⭐⭐⭐ | 서비스별 Grafana 패널 쿼리 |

---

## 5. 현재 부족한 것

### 제품 A

| 부족한 것 | 심각도 | 해결 예상 시간 |
|----------|--------|-------------|
| common-module을 독립 Maven/Gradle 라이브러리로 분리한 경험 없음 | 높음 | 1주 |
| 코드 스캔 → 런북/API 명세 자동 생성 엔진 없음 | 매우 높음 | 3~4주 |
| UI 대시보드 (문서 탐색, 서비스맵 시각화) | 높음 | 2~3주 |
| 다양한 프로젝트에서의 검증 (현재 1개 프로젝트만) | 높음 | 지속적 |
| Spring Boot Auto-Configuration 메커니즘 구현 | 중간 | 1주 |
| 다양한 Spring Boot 버전 호환성 테스트 | 중간 | 1주 |

### 제품 B

| 부족한 것 | 심각도 | 해결 예상 시간 |
|----------|--------|-------------|
| k6 결과 + Prometheus 메트릭 자동 연결 엔진 | 높음 | 2주 |
| 병목 자동 식별 알고리즘 (p95 급증 시점 ↔ HikariCP pending 상관관계) | 높음 | 2주 |
| 웹 UI (테스트 결과 시각화, 비교 대시보드) | 중간 | 2~3주 |
| 다양한 부하테스트 도구 지원 (JMeter, Gatling, Artillery) | 중간 | 추후 |
| 런북 자동 생성 로직 (병목 패턴 → 대응 절차 매칭) | 중간 | 1~2주 |
| PG stub / deterministic 실패 시나리오 재현 | 낮음 (MVP 후) | 2주 |

---

## 6. 2주 MVP 가능성 평가

### 제품 A — 2주 MVP: ❌ 어려움

**이유**: 핵심 가치인 "코드 스캔 → 문서 자동 생성"이 가장 어려운 부분이다. AST 파싱 또는 annotation processing으로 Spring Controller, Entity, ErrorCode를 읽어 API 명세와 런북을 생성해야 한다. `common-module`을 starter로 분리하는 것은 2주 안에 가능하지만, 그것만으로는 Spring Initializr와 차별화되지 않는다.

가능한 2주 범위: common-module을 Maven Central에 배포 가능한 starter로 분리 + README + 사용 예제. 하지만 이것은 "제품"이 아니라 "오픈소스 라이브러리"에 가깝다.

### 제품 B — 2주 MVP: ✅ 가능

**이유**: 이미 존재하는 자산을 연결하는 것이 핵심이다.

2주 MVP 범위:
1. **k6 JSON 결과 파서** (기존 `generate-summary.py` 확장) — 3일
2. **Prometheus API 자동 수집** (`capacity-planning.md`의 PromQL 쿼리 재사용) — 3일
3. **병목 자동 식별 규칙 엔진** (p95 급증 시점 ↔ HikariCP/Redis/Tomcat 상관관계) — 4일
4. **CLI 기반 리포트 생성** (Excel + 마크다운 런북) — 4일

`generate-summary.py`가 이미 JSON→Excel 변환의 90%를 구현했고, `capacity-planning.md`가 병목 판단 규칙을 텍스트로 정리해두었다. 이 둘을 프로그래밍으로 연결하면 CLI 도구가 된다.

---

## 7. 4주 내 데모 가능성 평가

### 제품 A — 4주 데모: ⚠️ 제한적

4주 안에 만들 수 있는 것:
- Spring Boot Starter (common-module 기반) + auto-configuration
- CLI 도구: 프로젝트 스캔 → 서비스 목록, API 엔드포인트 목록, ErrorCode 목록 추출
- 마크다운 런북 템플릿 자동 생성 (현재 `incident-runbook.md` 구조 기반)

데모 임팩트: "Spring Boot 프로젝트에 starter 추가하면 에러 처리 + 관측성이 자동 설정되고, CLI로 문서가 생성됩니다" — 설득력 있으나, **"이미 JHipster나 Backstage가 하고 있다"는 반론에 취약**하다.

### 제품 B — 4주 데모: ✅ 높은 임팩트

4주 안에 만들 수 있는 것:
- 2주 MVP (CLI 기반) +
- 웹 UI: 테스트 실행 이력 목록, 병목 히트맵, 개선 전/후 비교 차트
- Jaeger trace 연동: 느린 span 자동 추출 (`observability.md`의 span 구조 참고)
- 런북 자동 생성: 병목 패턴 → `incident-runbook.md` INC-XXX 매칭

데모 시나리오: "k6 돌리고 CLI 한 줄 실행하면 → 병목이 HikariCP임을 자동 식별 → Grafana 메트릭 증거와 함께 → 대응 런북까지 자동 생성" — **이 시나리오는 내 프로젝트의 실제 데이터로 데모할 수 있다.**

---

## 8. 차별화 포인트

### 제품 A

| 포인트 | 강도 | 경쟁 대비 |
|--------|------|----------|
| Spring MSA에 특화된 한국어 운영 문서 자동화 | ⭐⭐⭐⭐ | Backstage는 범용, JHipster는 생성 시점만 |
| 실전 MSA에서 검증된 common-module 패턴 | ⭐⭐⭐ | Spring Initializr는 보일러플레이트, 운영 패턴 아님 |
| 런북 + ADR + SLI/SLO 템플릿 세트 | ⭐⭐⭐⭐ | 대부분의 도구가 이 영역을 방치 |

### 제품 B

| 포인트 | 강도 | 경쟁 대비 |
|--------|------|----------|
| k6 결과 + Prometheus + Jaeger 자동 상관관계 분석 | ⭐⭐⭐⭐⭐ | Grafana k6 Cloud는 k6 메트릭만 보여줌, 인프라 메트릭 연결 없음 |
| 병목 패턴 → 런북 자동 매칭 | ⭐⭐⭐⭐⭐ | 경쟁 제품에 없는 기능 |
| 개선 전/후 수치 비교 자동화 | ⭐⭐⭐⭐ | 현재 수동 스크린샷 비교가 일반적 |
| Spring MSA 특화 병목 규칙 (HikariCP, Optimistic Lock, Redis SPOF) | ⭐⭐⭐⭐ | 범용 APM은 Spring 내부 컨텍스트를 모름 |

---

## 9. 구현 리스크

### 제품 A

| 리스크 | 발생 확률 | 영향 | 완화 방안 |
|--------|----------|------|----------|
| 코드 스캔 엔진 복잡도 폭증 (다양한 프로젝트 구조 대응) | 높음 | 높음 | 최초 버전은 자체 프로젝트 구조만 지원 |
| Spring Boot 버전 호환성 (3.x, 2.x) | 중간 | 중간 | 3.x 전용으로 시작 |
| "starter 하나 더 추가하기 싫다" 저항 | 중간 | 높음 | 문서 자동화에 초점 → starter는 선택사항 |
| 이미 Backstage/JHipster 생태계가 크다 | 높음 | 높음 | 니치(Spring+한국 스타트업) 집중 |

### 제품 B

| 리스크 | 발생 확률 | 영향 | 완화 방안 |
|--------|----------|------|----------|
| 병목 자동 식별 정확도가 낮으면 신뢰 상실 | 중간 | 높음 | 규칙 기반으로 시작, false positive 시 수동 확인 유도 |
| k6 이외 도구 지원 요구 (JMeter, Gatling) | 중간 | 중간 | k6 전용으로 시작, 확장 로드맵 명시 |
| Prometheus/Grafana 연동 환경 다양성 | 중간 | 중간 | Docker Compose 번들 제공 |
| Grafana k6 Cloud가 유사 기능 추가할 수 있다 | 낮음 | 높음 | 병목→런북 연결은 Grafana 로드맵에 없음 |

---

## 10. 제품화 리스크

### 제품 A

| 리스크 | 설명 |
|--------|------|
| **수익 모델 불명확** | 오픈소스 starter는 무료가 당연하다. 문서 자동화 SaaS로 과금하려면 호스팅 인프라가 필요하다. |
| **바이럴 채널 부재** | Spring 생태계는 이미 포화 상태. 신규 starter의 발견(discovery)이 어렵다. |
| **유지보수 부담** | Spring Boot 메이저 버전 업데이트마다 호환성을 맞춰야 한다. |
| **한 프로젝트 편향** | 현재 자산이 티켓팅 시스템에 맞춰져 있어 범용화에 추가 작업 필요. |

### 제품 B

| 리스크 | 설명 |
|--------|------|
| **초기 사용자 확보** | k6 사용자 커뮤니티에 진입해야 한다. k6 확장(xk6) 또는 Grafana 마켓플레이스 등록이 진입점이 될 수 있다. |
| **데이터 프라이버시** | 메트릭 데이터에 민감 정보(서비스 구조, 트래픽 패턴)가 포함될 수 있다. |
| **과금 시점** | CLI 무료 → Pro(웹 UI + 팀 공유 + 이력 비교)로 전환하는 시점이 명확하다. |
| **인프라 종속성** | Prometheus + Grafana 환경을 전제하므로 Datadog/New Relic 사용자를 놓칠 수 있다. |

---

## 11. 내가 첫 제품으로 선택해야 할 1순위

### 🏆 제품 B — 부하테스트/폭주대응 분석 도구

---

## 12. 선택 이유 5개

**① 기존 자산의 재사용률이 압도적으로 높다.**

`generate-summary.py`(JSON→Excel, 90% 완성), k6 시나리오 7개, PromQL 쿼리 라이브러리(`observability.md`), 병목 판단 규칙(`capacity-planning.md`), 장애 매트릭스(`incident-runbook.md`)가 이미 존재한다. 제품 A의 핵심인 "코드 스캔 → 문서 자동 생성 엔진"은 0에서 시작해야 하지만, 제품 B의 핵심인 "k6 결과 + Prometheus 연결 → 병목 식별"은 기존 Python 스크립트와 PromQL 쿼리를 연결하는 작업이다.

**② 2주 MVP가 현실적으로 가능하다.**

제품 A의 2주 MVP는 "starter 분리"인데, 이것은 Spring Initializr와 다를 바 없다. 제품 B의 2주 MVP는 "k6 결과를 파싱해서 Prometheus 메트릭과 병목을 자동 연결하는 CLI"인데, 이것만으로도 기존에 없는 가치를 만든다. `generate-summary.py`를 확장하면 3일 만에 파서가 완성되고, `capacity-planning.md`의 규칙을 코드로 옮기면 4일 만에 병목 식별기가 된다.

**③ 차별화 지점이 경쟁 제품의 로드맵에 없다.**

Grafana k6 Cloud는 k6 메트릭 시각화에 집중하고, Datadog APM은 범용 인프라 모니터링에 집중한다. "k6 부하테스트 결과를 Prometheus 인프라 메트릭과 자동 상관관계 분석하고, 병목 패턴에 맞는 런북까지 생성"하는 도구는 현재 시장에 없다. 이것은 내가 `incident-runbook.md`에서 INC-001~009를 설계하면서 쌓은 "병목 패턴 → 대응 절차" 매핑 지식이 직접적인 차별화 자산이 되기 때문이다.

**④ 데모 임팩트가 자체 프로젝트로 바로 증명된다.**

4주 데모 시나리오: home-staging 환경에서 k6 돌리고 → CLI 실행 → "HikariCP pending이 0.3초 시점에 급증, 동시에 payment-app p95가 1.5초를 초과, 원인: TossPayments read-timeout 10초 동안 커넥션 점유" → 대응 런북 INC-001 자동 생성. 이 시나리오는 실제 데이터로 구동되므로 설득력이 높다. 제품 A의 데모는 "starter 추가하면 에러 처리가 됩니다"인데, 이것은 Spring 개발자에게 "그래서?"라는 반응을 받기 쉽다.

**⑤ 과금 전환 경로가 명확하다.**

CLI 무료(OSS) → Pro 플랜: 웹 대시보드, 팀 공유, 이력 비교, Slack/Notion 자동 업로드. 현재 프로젝트의 CLAUDE.md에서 "노션 자동 업로드: 항상 노션에도 함께 업로드"를 요구사항으로 명시한 것 자체가 Pro 기능의 수요 증거다. 제품 A는 "오픈소스 starter + 문서 SaaS"인데, starter를 유료화하면 커뮤니티 반발이 크고, SaaS만 유료화하면 starter 없이도 작동하는 문서 도구(Notion, Confluence)와 경쟁해야 한다.

---

## 13. "지금 당장 시작할 작업 7개" 우선순위 목록

| 순위 | 작업 | 예상 기간 | 산출물 | 기존 자산 연결 |
|------|------|----------|--------|--------------|
| 1 | `generate-summary.py`를 CLI 도구로 리팩토링: k6 JSON 결과 디렉토리를 입력받아 시나리오별 핵심 지표(p50/p95/p99, 에러율, TPS) 추출 | 2일 | `perf-analyzer parse` CLI 명령어 | `generate-summary.py` 90% 재사용 |
| 2 | Prometheus API 자동 수집 모듈 구현: k6 테스트 시간대를 입력받아 해당 구간의 HikariCP, Tomcat, Redis, JVM 메트릭을 자동 쿼리 | 3일 | `perf-analyzer collect-metrics` 명령어 | `capacity-planning.md` PromQL 쿼리, `observability.md` 메트릭 목록 |
| 3 | 병목 자동 식별 규칙 엔진: k6 p95 급증 시점과 인프라 메트릭 이상치의 시간 상관관계 분석 → "HikariCP 병목", "Redis 병목", "Optimistic Lock 경합" 등 자동 라벨링 | 4일 | `perf-analyzer analyze` 명령어 | `capacity-planning.md` 병목 순서 예측, `incident-runbook.md` 장애 매트릭스 |
| 4 | 런북 자동 생성: 식별된 병목 패턴에 매칭되는 대응 절차를 마크다운으로 자동 출력 | 2일 | `perf-analyzer runbook` 명령어 | `incident-runbook.md` INC-001~009 구조 직접 재사용 |
| 5 | home-staging 환경에서 end-to-end 검증: 실제 k6 테스트 실행 → `perf-analyzer` 전체 파이프라인 구동 → 결과 문서 생성 | 2일 | 실행 결과 Excel + 마크다운 런북 + 스크린샷 | home-staging 인프라 (Prometheus, Grafana, Jaeger) |
| 6 | 웹 UI 프로토타입: React로 테스트 이력 목록, 병목 히트맵, 개선 전/후 비교 차트 | 5일 | `http://localhost:3000` 데모 | `load-test/scripts/k6/grafana/k6-dashboard.json` 레이아웃 참고 |
| 7 | 랜딩 페이지 + GitHub 공개 + k6 커뮤니티 소개글 작성 | 2일 | GitHub repo + 블로그 포스트 | README.md 프로젝트 소개 구조 참고 |

---

## 문서 간 충돌/중복 사항

분석 과정에서 발견한 문서 간 비정합:

| 항목 | 문서 A | 문서 B | 충돌/중복 내용 |
|------|--------|--------|-------------|
| Java 버전 | `README.md`: Java 17 | `ARCHITECTURE.md`, `build.gradle`: Java 21 | README가 오래된 버전 기재 |
| Spring Boot 버전 | `README.md`: 3.3.2 | `build.gradle`: 3.4.0 | README 미업데이트 |
| payment API 엔드포인트명 | `api-spec.md` 5.3절: `/api/v1/payments/request` | `api-spec.md` 10.4절 예시: `/api/v1/payments/prepare` | 동일 문서 내 엔드포인트명 불일치. request vs prepare 혼용 |
| payment 응답 status 값 | `payment-architecture.md`: APPROVED/REFUNDED | `api-spec.md` 10.5절 예시: DONE | APPROVED vs DONE 혼용 |
| run-tests.sh 시나리오 | `run-tests.sh`: scenario1~2만 등록 | `performance-test-runbook.md`: 4개 핵심 시나리오 정의 | 런북에 정의된 시나리오의 k6 스크립트 미구현 |
| `generate-summary.py` 범위 | scenario1~3만 처리 | k6 스크립트: scenario1~7 존재 | 4~7 시나리오 Excel 요약 미지원 |
| 백업 DR | `backup-dr.md`: cron 자동화 planned | `release-rollback-checklist.md`: 배포 전 백업 필수 체크 | 자동 백업이 없는데 배포 전 백업을 체크리스트에 넣음 → 수동 의존 |

---

## 비교 요약 표

| 비교 항목 | 제품 A (MSA 운영 스타터) | 제품 B (부하테스트 분석 도구) |
|----------|----------------------|--------------------------|
| **한 줄 정의** | Spring MSA 공통 모듈 + 운영 문서 자동화 | k6 결과 + 인프라 메트릭 → 병목 자동 식별 + 런북 생성 |
| **타깃** | Spring MSA 스타트업 백엔드 팀 | k6 사용 백엔드/SRE 팀 |
| **핵심 문제** | 보일러플레이트 반복, 문서 수동 동기화 | 부하테스트 결과 해석 불가, 메트릭 단절 |
| **재사용 자산** | common-module, 문서 구조 | k6 스크립트, generate-summary.py, PromQL, 런북 |
| **자산 재사용률** | 40% (핵심 엔진 0에서 시작) | **75%** (기존 스크립트+문서 연결) |
| **2주 MVP** | ❌ starter 분리만 가능 (차별화 부족) | **✅ CLI 도구 (파서+분석+리포트)** |
| **4주 데모** | ⚠️ 제한적 (CLI 문서 생성) | **✅ 웹 UI + 실데이터 데모** |
| **차별화** | 한국 Spring MSA 니치 | **k6+Prometheus 상관분석 + 런북 자동생성 (시장에 없음)** |
| **경쟁 강도** | 높음 (Backstage, JHipster) | **낮음** (Grafana k6 Cloud와 직교) |
| **구현 리스크** | 높음 (코드 스캔 엔진) | **중간** (규칙 기반 정확도) |
| **제품화 리스크** | 높음 (수익 모델 불명확) | **중간** (CLI 무료→Pro 명확) |
| **과금 전환** | 불명확 (OSS ↔ SaaS 경계) | **명확** (CLI free → Web Pro) |
| **면접 어필** | MSA 공통 모듈 설계 경험 | **부하테스트 기반 병목 분석 + 자동화 경험** |
| **추천 순위** | 2순위 (제품 B 안정화 후 모듈로 통합 가능) | **🏆 1순위** |

---

## 부록: 제품 B 아키텍처 초안

```
┌─────────────────────────────────────────────────────┐
│                    perf-analyzer CLI                  │
├─────────────────────────────────────────────────────┤
│                                                      │
│  perf-analyzer parse        k6 JSON → 핵심 지표 추출  │
│  perf-analyzer collect      Prometheus API → 구간 메트릭│
│  perf-analyzer analyze      시간 상관관계 → 병목 라벨링  │
│  perf-analyzer runbook      병목 패턴 → 대응 절차 생성  │
│  perf-analyzer report       Excel + 마크다운 통합 리포트 │
│  perf-analyzer compare      이전 결과 vs 현재 결과 비교 │
│                                                      │
├─────────────────────────────────────────────────────┤
│  입력                        출력                      │
│  ├── k6 results/ (JSON)     ├── report.xlsx           │
│  ├── Prometheus API          ├── runbook.md            │
│  ├── Jaeger API (선택)       ├── comparison.md         │
│  └── 규칙 파일 (YAML)        └── bottleneck.json       │
└─────────────────────────────────────────────────────┘
```

---

*이 문서는 ci-cd-test 프로젝트의 실제 코드·문서·산출물을 기준으로 작성되었습니다.*
*분석 기준일: 2026-03-22*
