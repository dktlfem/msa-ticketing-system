---
title: "MSA 티켓팅 플랫폼 문서 허브"
last_updated: 2026-03-19
author: "민석"
---

# MSA 티켓팅 플랫폼 — 문서 허브

> **6개 서비스 기반 대규모 티켓팅 시스템**: `scg-app` · `waitingroom-app` · `concert-app` · `booking-app` · `payment-app` · `user-app`
>
> 기술 스택: Java 21 · Spring Boot 3.x · Spring Cloud · Kafka · Redis · MySQL · Docker · k6

---

## 추천 읽기 순서

### 1단계 — 시스템 전체 이해 (15분)

| 문서 | 설명 |
|------|------|
| [architecture/system-overview.md](architecture/system-overview.md) | 6개 서비스 구성, Mermaid 다이어그램 6종 |
| [architecture/why-msa.md](architecture/why-msa.md) | 왜 MSA인가, 서비스 경계, Saga 흐름, IaC 로드맵 |
| [api/api-spec.md](api/api-spec.md) | 시스템 전체 API 카탈로그 (외부 5개 + 내부 3개 서비스) |

### 2단계 — 핵심 도메인 설계 (30분)

| 문서 | 설명 |
|------|------|
| [services/payment/payment-architecture.md](services/payment/payment-architecture.md) | 결제 상태 전이, 트랜잭션 경계, 보상 흐름, Saga 다이어그램 |
| [services/booking/seat-locking-design.md](services/booking/seat-locking-design.md) | 분산락(Redisson) + 낙관적 락 동시성 제어 |
| [services/concert/concert-design.md](services/concert/concert-design.md) | 낙관적 락(@Version), 좌석 상태 전이, booking-app 내부 API 계약 |
| [services/scg/gateway-design.md](services/scg/gateway-design.md) | 필터 실행 순서, JWT 인증, 헤더 보안, 라우팅 설계 |
| [services/waitingroom/queue-design.md](services/waitingroom/queue-design.md) | Redis Sorted Set 기반 대기열, rate limiting |
| [data/database-cache-design.md](data/database-cache-design.md) | 개념 ERD, 인덱스 설계, 파티셔닝 전략, Redis 키 패턴 |
| [security/security-design.md](security/security-design.md) | 보안 통제 현황, 리스크 분석, Rate Limiting 확장 전략 |

### 3단계 — 운영/관측성 (20분)

| 문서 | 설명 |
|------|------|
| [observability/observability.md](observability/observability.md) | 로그·메트릭·트레이스 전체 스택 + Payment Confirm 심화 가이드 |
| [operations/incident-runbook.md](operations/incident-runbook.md) | 장애 대응 런북 (INC-001~009 + 추가 9개 시나리오) |
| [operations/backup-dr.md](operations/backup-dr.md) | 백업/DR 설계 (시나리오 1~5 + 추가 8개) |
| [operations/release-rollback-checklist.md](operations/release-rollback-checklist.md) | 배포 전후 체크리스트 (Blue/Green) |

---

## 전체 문서 목록

### 아키텍처

| 파일 | 설명 |
|------|------|
| [architecture/system-overview.md](architecture/system-overview.md) | 시스템 전체 아키텍처 다이어그램 (Mermaid 6종) |
| [architecture/why-msa.md](architecture/why-msa.md) | 서비스 경계, 의존성, 통신 방식, IaC 로드맵 |
| [architecture/adr/0000-template.md](architecture/adr/0000-template.md) | ADR 경량 템플릿 |
| [architecture/adr/0001-single-counter-metric-naming.md](architecture/adr/0001-single-counter-metric-naming.md) | ADR-0001: 단일 카운터 메트릭 명명 규칙 |

### API

| 파일 | 설명 |
|------|------|
| [api/api-spec.md](api/api-spec.md) | 시스템 전체 API 카탈로그 + payment-app 심화 + user-app DTO/에러 상세 (6.1, 6.2) |

### 데이터

| 파일 | 설명 |
|------|------|
| [data/database-cache-design.md](data/database-cache-design.md) | 개념 ERD, 테이블 요약, 인덱스, 파티셔닝, Redis 키 패턴, 정합성 전략 |

### 보안

| 파일 | 설명 |
|------|------|
| [security/security-design.md](security/security-design.md) | 보안 통제, 리스크 분석 (R6: 비밀번호 평문 저장, R7: JWT 발급 미구현 포함), 개선 계획, Rate Limiting 확장 전략 |
| [security/data-retention-privacy.md](security/data-retention-privacy.md) | 데이터 보존 및 개인정보 설계 |

### 관측성

| 파일 | 설명 |
|------|------|
| [observability/observability.md](observability/observability.md) | 로그·메트릭·트레이스 스택 + Payment Confirm 심화 가이드 + 전 서비스 알림 룰 |

### 운영

| 파일 | 설명 |
|------|------|
| [operations/incident-runbook.md](operations/incident-runbook.md) | 장애 대응 런북 (INC-001~009: PG오류·보상취소실패·Redis·MySQL·SCG·user-app 등) |
| [operations/backup-dr.md](operations/backup-dr.md) | 백업/재해복구 설계 |
| [operations/release-rollback-checklist.md](operations/release-rollback-checklist.md) | 릴리즈/롤백 체크리스트 |
| [operations/gitlab-ci-template.yml](operations/gitlab-ci-template.yml) | GitLab CI/CD 5-stage 파이프라인 템플릿 |

### 성능

| 파일 | 설명 |
|------|------|
| [performance/performance-test-runbook.md](performance/performance-test-runbook.md) | k6 기반 부하 테스트 런북 |
| [performance/sli-slo.md](performance/sli-slo.md) | SLI/SLO 정의 (전 서비스: concert-app·user-app SLO 포함) |
| [performance/capacity-planning.md](performance/capacity-planning.md) | 용량 계획 (병목 순서, 시나리오 A~D, user-app BCrypt 영향 포함) |

### 서비스별 설계

| 파일 | 설명 |
|------|------|
| [services/payment/payment-architecture.md](services/payment/payment-architecture.md) | payment-app 심화 설계 + Saga 흐름 다이어그램 |
| [services/booking/seat-locking-design.md](services/booking/seat-locking-design.md) | 좌석 선점 동시성 제어 (Redisson + 낙관적 락) |
| [services/concert/concert-design.md](services/concert/concert-design.md) | 낙관적 락, 좌석 상태 전이, Caffeine 캐시, 내부 API |
| [services/scg/gateway-design.md](services/scg/gateway-design.md) | 필터 체인, JWT 인증, 헤더 보안, 라우팅, CB/Retry |
| [services/waitingroom/queue-design.md](services/waitingroom/queue-design.md) | 대기열 Redis 설계 + rate limiting |

### 레거시 (보존)

| 파일 | 설명 |
|------|------|
| [performance/concurrency-test-runbook.md](performance/concurrency-test-runbook.md) | Java CountDownLatch 기반 동시성 테스트 (k6 런북은 `performance/` 참조) |

---

## 설계 철학 요약

**크로스커팅 관심사는 시스템 전체 단일 문서**: API 명세, DB/Cache 설계, 보안, 관측성, 운영 런북은 서비스별로 쪼개지 않고 시스템 전체를 하나의 문서에서 다룹니다.

**서비스별 심화 설계는 `services/` 하위**: 동시성 제어, 결제 상태 전이, 대기열 알고리즘처럼 특정 서비스에 특화된 설계 결정은 `services/{서비스명}/` 아래에 위치합니다.

**Why 중심 서술**: 각 설계 결정에 "왜 이 방식인지"를 항상 함께 기록합니다. ADR 패턴을 따릅니다.

---

*최종 업데이트: 2026-03-22 (ADR-0007 Phase 3 완료 반영 — 헤더명 일괄 교체: X-Auth-User-Id→Auth-User-Id, X-Waiting-Token→Queue-Token, X-Correlation-Id→Correlation-Id, X-Internal-Token→Internal-Token, Auth-Passport 신규 추가)*
