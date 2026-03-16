> 🎟️ 대규모 트래픽 대응을 위한 티켓팅 시스템 (High-Availability MSA)
"초당 1,100건의 트래픽에도 0%의 실패율을 보장하는 고가용성 아키텍처 구축"

### 🚀 1. 핵심 성과 (Key Achievements)
- [High Concurrency] Redis Sorted Set 기반 대기열 시스템으로 1,100+ TPS 처리량 확보
- [Fault Tolerance] Resilience4j Circuit Breaker 도입으로 서비스 장애 전이(Cascading Failure) 원천 차단
- [Observability] Prometheus & Grafana 연동을 통해 앱-인프라 전 구간 실시간 모니터링 체계 구축
- [Reliability] HashSet 기반 중복 검증 로직으로 대규모 데이터(유저 1,000명, 공연 30개)의 무결성 보장
---
### 🛠️ 2. 기술 스택 (Tech Stack)
- Language & Framework: Java 17, Spring Boot 3.3.2, Spring Data JPA
- Infrastructure: MySQL 8.0, Redis, Docker, GitLab CI, Jenkins
- Testing & Monitoring: k6 (Performance Testing), Prometheus, Grafana
---
### 🏗️ 3. 아키텍처 설계 (Architectural Evolution)
- 단일 모듈의 한계를 극복하고 유지보수성과 독립적 배포를 위해 4계층 멀티 모듈 아키텍처로 고도화했습니다.
- Presentation Layer: 클라이언트 요청 처리 및 응답 반환
- Business Layer: 도메인 로직 및 서비스 흐름 제어 (AiModelService 등)
- Implementation Layer: 기술적인 구현 세부사항 담당
- Data Access Layer: 데이터베이스 물리적 접근

### 🔥 4. 핵심 엔지니어링 챌린지 (Core Challenges)
#### 🛡️ 외부 API 장애 전이 방지 (Circuit Breaker)
- 문제: AI 모델 서버 등 무거운 외부 연산 지연 시 WAS 스레드가 고갈되어 시스템 전체가 마비될 위험 식별
- 해결: Resilience4j를 활용하여 독립적인 회로 정책 수립
- 최근 10회 요청 중 실패율 50% 초과 시 회로 즉시 개방(OPEN)
- Fallback Method 구현을 통한 안정적인 응답(Fail-fast) 보장
---
#### ⚡ 고부하 상황에서의 성능 임계치 검증 (k6 Stress Test)
- 방법: 1VU(Smoke) → 200VU(Load) → 1,000VU(Stress) 단계별 시뮬레이션 수행
- 결과: 1,000명 동시 접속 상황에서도 평균 지연시간 11.41ms의 초고속 응답 유지
# 플랫폼 설계 / API / 운영 문서 패키지

이 문서 묶음은 현재 프로젝트의 `scg-app`, `payment-app`, `booking-app`, `waitingroom-app`, `concert-app`, `user-app`, self-hosted staging, Redis 분리 노드, CI/CD, 보안, 관측성까지를 한 번에 설명하기 위한 **GitHub/Notion 친화형 문서 패키지**입니다.

## 포함 파일

| 문서 | 역할 |
| --- | --- |
| `01-api-specification.md` | 외부/내부 API 명세, 엔드포인트, 대표 요청/응답 예시 |
| `02-architecture-infrastructure.md` | 시스템 구성도, 네트워크, CI/CD, IaC 범위 |
| `03-database-cache-design.md` | ERD, 인덱스, 캐싱, 정합성, 파티셔닝 전략 |
| `04-security-auth-rate-limiting.md` | 보안 경계, 인증/인가, 내부 API 은닉, rate limiting |
| `05-observability.md` | ELK, Prometheus/Grafana, Jaeger, DB/APM 관측 전략 |
| `06-developer-experience.md` | Swagger, 문서화, starter/Config roadmap, GitHub/Notion 운영 방식 |
| `07-performance-test-runbook.md` | CountDownLatch 동시성 테스트, 판정 기준, 트러블슈팅 |
| `08-additional-recommended-docs.md` | ADR, Runbook, SLO, DR 등 추가 추천 문서 |

## 함께 제공한 Excel 파일

- `platform-api-and-mapping.xlsx`

이 파일에는 아래 시트가 들어 있습니다.

- `Summary`: 엔드포인트 수와 서비스별 요약
- `API_List`: 전체 외부/내부 API 카탈로그
- `Data_Mapping`: 헤더/Body/DB/Redis/내부 API 간 데이터 흐름 매핑
- `ERD_Summary`: 스키마/테이블/인덱스 요약

## 문서 운영 권장안

### 권장 결론

- **주 문서**: Markdown(GitHub)
- **발표/공유 허브**: Notion
- **보조 자료**: Excel

### 왜 이렇게 권장하는가

#### Markdown(GitHub)

- 버전 관리가 가장 강함
- 코드와 문서를 같이 PR로 관리 가능
- diff 추적이 쉬움

#### Notion

- 읽기 경험이 좋음
- 링크 허브/발표 자료로 쓰기 편함
- 면접/리뷰 시 공유가 편함

#### Excel

- API 리스트 요약
- 데이터 매핑
- ERD 요약표
- 테스트 결과 표

같은 **정형 테이블**에 강함

## 추천 운영 방식

1. GitHub `docs/`를 source of truth로 유지
2. Notion에는 README/Overview를 중심으로 링크 허브 구성
3. Excel은 정형 표가 필요한 부분만 보조적으로 첨부
4. 포트폴리오 제출 시 README에서 문서/엑셀을 함께 링크

## 한 줄 정리

**전체 흐름과 기술력은 Markdown/Notion으로 보여주고, API 목록/매핑표/체크리스트는 Excel로 보조하는 방식이 가장 좋습니다.**