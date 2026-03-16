# 08. 추가로 작성하면 좋은 문서

아래 문서까지 들어가면 포트폴리오 깊이가 한 단계 더 올라갑니다.

## 1. ADR (Architecture Decision Records)

예시 주제:

- 왜 Redis를 별도 노드로 분리했는가
- 왜 Blue/Green을 선택했는가
- 왜 seat 선점에 낙관적 락을 썼는가
- 왜 payment에 멱등성과 보상 로직을 넣었는가
- 왜 `/internal/**`를 gateway에서 숨겼는가

### 템플릿

- Context
- Decision
- Alternatives
- Consequences

---

## 2. 장애 대응 Runbook

반드시 있으면 좋은 주제:

- payment confirm 장애 시 운영자 조치
- reservation sync failure 재처리 절차
- Redis 장애 시 영향 범위와 복구 순서
- MySQL slow query 급증 시 대응 절차
- Jenkins 배포 실패 / Blue-Green 롤백 절차

---

## 3. SLI / SLO 문서

예시:

- `payment confirm` 성공률
- `booking create` p95 latency
- gateway 5xx 비율
- waitingroom token 발급 성공률

이 문서가 있으면 “모니터링 도구를 붙였다”를 넘어  
**운영 목표를 숫자로 관리한다**는 인상을 줍니다.

---

## 4. 백업 / 복구 / DR 문서

포함하면 좋은 내용:

- DB 백업 주기
- Redis 장애 시 어떤 데이터가 날아가도 괜찮은지
- recovery code/OTP 분실 시 복구 절차
- GitLab/Jenkins 장애 시 우회 배포 절차

---

## 5. Release / Rollback Checklist

예시 항목:

- DB migration 검토 완료
- feature flag 상태 확인
- blue 환경 health check 통과
- Grafana/Jaeger baseline 확인
- rollback 기준과 책임자 확인

---

## 6. Capacity / Cost 문서

특히 당신의 프로젝트처럼 self-hosted staging + AWS prod 흐름이 있으면 좋은 주제입니다.

- home staging 리소스 한계
- Redis 노드 메모리 headroom
- RDS 비용/성능 고려
- 로그/메트릭 보관 기간에 따른 저장 비용

---

## 7. Data Retention / Privacy 문서

가능하다면 정리할 내용:

- 결제 데이터 보관 범위
- 로그에 민감정보를 남기지 않는 원칙
- paymentKey / secret / recovery code 취급 정책
- Elasticsearch 보관 기간과 삭제 정책

---

## 8. 추천 최종 문서 세트

최소 세트:

1. Overview / README
2. Architecture
3. API Spec
4. DB / Cache Design
5. Security
6. Observability
7. Performance Test / Runbook

강력 추천 세트:

8. ADR
9. Incident Runbook
10. SLI/SLO
11. Release / Rollback Checklist
12. Backup / DR

---

## 9. 포트폴리오 제출용 팁

문서가 많다고 무조건 좋은 건 아닙니다.  
다음 기준으로 정리하면 좋습니다.

- README에서 1분 안에 전체 구조가 보일 것
- 본문은 “현재 구현”과 “차기 계획”이 분리될 것
- 표와 다이어그램이 많고, 긴 문단 설명은 최소화할 것
- API 목록과 데이터 매핑은 Excel로 보조할 것
- GitHub Markdown을 기준으로 관리하고, Notion은 읽기 경험 개선용으로 쓸 것