# 06. 개발자 경험 (Developer Experience, DX)

## 1. DX 문서를 왜 따로 두는가

좋은 플랫폼은 “서비스가 돌아간다”에서 끝나지 않습니다.  
새 개발자가 합류했을 때 아래가 가능해야 합니다.

- 어디에서 API를 확인해야 하는지 바로 안다
- 로컬/staging를 어떻게 띄우는지 안다
- 장애가 나면 어디를 봐야 하는지 안다
- 공통 규약(`X-Auth-User-Id`, `Idempotency-Key`, correlationId)을 빠르게 이해한다
- 같은 설정을 중복 구현하지 않는다

이 문서는 그런 **협업 생산성**을 위한 기준 문서입니다.

---

## 2. 현재 DX 강점

### 2.1 Swagger / OpenAPI

각 서비스는 springdoc 기반 Swagger UI를 가질 수 있습니다.

- MVC 서비스: `springdoc-openapi-starter-webmvc-ui`
- WebFlux 서비스(waitingroom): `springdoc-openapi-starter-webflux-ui`

즉, 코드 수준에서 컨트롤러/DTO를 작성하면 API 문서가 자동으로 반영되는 구조를 이미 갖고 있습니다.

### 2.2 GitHub/Markdown 친화적 문서화

이 문서 패키지는 Markdown 기준으로 정리되어 있어

- GitHub 저장소에서 바로 리뷰 가능
- Notion으로 가져가서 재배치 가능
- PR 단위 diff 추적 가능

이라는 장점이 있습니다.

### 2.3 Excel 보조 자료

전체 스토리와 설계 의도는 Markdown에 두고,  
아래처럼 정형 표는 Excel을 병행하는 게 좋습니다.

- API 리스트 요약
- 데이터 매핑 테이블
- ERD 요약표
- 운영 체크리스트
- 테스트 결과 집계표

---

## 3. 문서 구조 권장안

```text
docs/
  00-overview.md
  01-api-specification.md
  02-architecture-infrastructure.md
  03-database-cache-design.md
  04-security-auth-rate-limiting.md
  05-observability.md
  06-developer-experience.md
  07-performance-test-runbook.md
  08-additional-recommended-docs.md
  excel/
    platform-api-and-mapping.xlsx
```

### 문서 역할 분리 원칙

- **README/Overview**: 처음 보는 사람이 전체 그림 파악
- **API 문서**: 호출 계약 확인
- **Architecture**: 시스템 전체 구조 이해
- **DB/Security/Observability**: 깊이 있는 설계 설명
- **Runbook/Test**: 운영/재현/검증 가이드
- **Excel**: 목록/매핑/집계 보조

---

## 4. Swagger 자동화와 문서 자동화

## 4.1 현재 가능한 것

- Controller / DTO 변경 시 Swagger UI 자동 반영
- Markdown 문서는 코드 변경과 함께 PR로 업데이트 가능

## 4.2 다음 단계로 추천하는 것

### CI 문서 체크

- PR에서 OpenAPI diff 확인
- breaking change 감지
- 문서 파일 미갱신 시 경고

### OpenAPI export

- 서비스별 `/v3/api-docs`를 artifact로 저장
- 배포 시점마다 스냅샷 보관

### 문서 버전 관리

- `docs/v1`, `docs/v2` 구조
- breaking change 시 release note 작성

---

## 5. 공통 규약을 starter로 추출하는 방향

현재 프로젝트에는 starter 분리 설계가 이미 잡혀 있습니다.

- `platform-starter-observability`
- `platform-starter-client`
- `platform-starter-lock`
- `platform-starter-idempotency`

### DX 관점에서 이게 왜 중요한가

서비스가 늘어날수록 개발자는 “무엇을 import해서 써야 하는지”가 단순해야 합니다.

좋은 DX는:

- 공통 builder, filter, interceptor, properties가 자동 구성됨
- 서비스별 구현자는 비즈니스 로직에만 집중
- 설정 변경은 code change가 아니라 property change로 처리

즉, **starter는 코드 재사용을 넘어서 개발 경험의 표준화 도구**입니다.

---

## 6. Spring Cloud Config 도입 이유

서비스 수가 늘어나면 `application.yml/properties`가 분산되면서 운영성이 급격히 떨어집니다.

중앙 설정 관리 대상으로 적합한 것:

- gateway URI / timeout
- tracing sampling
- retry / backoff
- idempotency TTL
- lock lease / wait time
- Redis namespace
- feature toggle

### 좋은 문장 예시

> 서비스 수 증가에 대비해 공통 starter와 Spring Cloud Config를 결합해, 코드 수정 없이 공통 운영 정책을 중앙에서 변경할 수 있는 구조를 준비하고 있습니다.

---

## 7. SDK / CLI / 개발자 도구 관점에서 추가하면 좋은 것

현재는 아직 구현 단계가 아니어도, DX 항목에 아래를 **로드맵**으로 넣으면 인상이 좋습니다.

### 7.1 SDK

- 테스트용 Java/Kotlin client
- 공통 인증 헤더/Idempotency-Key 생성 helper
- retry/timeout 기본 내장

### 7.2 CLI

- staging health check
- test data seed
- 특정 reservation/payment 상태 조회
- 운영자용 재처리 trigger

### 7.3 Template generator

- 새 서비스 생성 시 starter와 기본 observability 설정 자동 주입
- `Controller / Service / Reader / Writer / Validator` 구조 뼈대 생성

---

## 8. 개발자 온보딩 문서에 꼭 들어가야 할 것

### 빠른 시작

- 로컬 또는 WSL2에서 어떤 순서로 띄우는지
- 필수 환경변수는 무엇인지
- Redis/MySQL/Jaeger/Grafana 접속 정보는 무엇인지

### 호출 규약

- 어떤 API가 `X-Auth-User-Id`를 요구하는지
- 언제 `Idempotency-Key`를 넣어야 하는지
- `X-Waiting-Token`은 어디서 받는지

### 디버깅 루틴

- correlationId로 Kibana 검색
- traceId로 Jaeger 확인
- p95/p99로 Grafana 확인
- slow query/P6Spy 확인

### 테스트 데이터

- event/schedule/seat seed 정책
- user 계정 seed
- payment confirm 테스트용 mock/stub 전략

---

## 9. GitHub과 Notion을 같이 쓰는 방법

### 권장 운영 방식

- **GitHub Markdown = source of truth**
- **Notion = 발표/가독성/링크 허브**
- **Excel = 정형 표 보조**

이 조합이 가장 실무적입니다.

#### 이유

- GitHub은 버전 관리와 diff가 강함
- Notion은 읽기 경험과 페이지 연결이 강함
- Excel은 정렬/필터/매핑 표에 강함

### 추천 방식

1. GitHub `docs/`에 Markdown 원본 유지
2. Notion에는 각 문서를 임포트하거나 링크로 연결
3. Excel은 `docs/excel/` 또는 `artifacts/`에 보관
4. README에서 문서와 엑셀 파일을 함께 링크

---

## 10. 이번 프로젝트에서 DX로 특히 어필하기 좋은 포인트

- 단순 API 구현이 아니라 gateway, 공통 헤더, 예외 표준, 멱등성 규약을 함께 정리했다
- Swagger, Markdown 문서, Excel 보조 자료를 조합해 개발자/면접관이 빠르게 전체 구조를 이해할 수 있도록 했다
- starter와 Config Server 방향을 미리 설계해서 서비스 증가 시 운영 복잡도가 폭증하지 않도록 준비했다
- CountDownLatch 동시성 테스트, 트러블슈팅 문서, 관측성 플레이북까지 포함해 “다른 사람도 사용할 수 있는 플랫폼” 관점으로 문서화했다