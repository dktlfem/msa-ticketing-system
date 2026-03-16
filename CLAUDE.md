# Project
토스뱅크 Platform 개발자 지원용 MSA 기반 대규모 티켓팅 시스템

# 이 저장소의 역할
이 저장소는 애플리케이션/도메인 구현 중심이다.
주요 서비스:
- user-app
- waitingroom-app
- concert-app
- booking-app
- payment-app
- scg-app
# Service Boundaries

각 서비스의 책임은 아래와 같다.

user-app
- 사용자 인증
- 사용자 정보 조회
- 사용자 예약 목록 조회

waitingroom-app
- 대기열 토큰 발급
- 대기열 순서 관리
- 입장 허용

concert-app
- 공연 조회
- 좌석 조회

booking-app
- 좌석 선점
- 예약 생성
- 예약 상태 관리

payment-app
- 결제 요청 생성
- 결제 승인
- 결제 취소
- idempotency 처리

scg-app
- API Gateway
- 인증 전달
- 라우팅

# 현재 단계
- booking + waitingroom + concert 미니 연동 완료
- 지금 최우선은 payment-app 개발
- 목표는 기능 수보다 "면접에서 설명 가능한 설계"이다

# 이번 주 최우선 과제
1. payment-app 최소 흐름 완성
2. booking/payment 정합성 시나리오 정리
3. 중복 결제/중복 요청/idempotency 처리
4. 좌석 선점 -> 예약 확정 상태 전이 명확화
5. 실패 복구 시나리오 정리
6. end-to-end trace 1개 확보
7. k6 수치 3~5개 확보

# 핵심 사용자 흐름
로그인
-> 대기열 진입
-> 입장 허용
-> 공연/좌석 조회
-> 좌석 선점
-> 결제 요청
-> 결제 성공/실패/중복 처리
-> 예약 확정 또는 보상 처리

# Claude 작업 원칙
코드 변경 전 반드시:
1. 목표를 5줄 이하로 요약
2. 영향받는 서비스/모듈을 나열
3. 동시성/정합성 리스크를 적기
4. 가장 작은 변경안을 제안
5. 검증 명령어와 실패 시 롤백 방법을 적기

코드 변경 후 반드시:
1. 변경 파일 목록
2. 왜 이렇게 바꿨는지
3. 대안과 트레이드오프
4. 실행/테스트 방법
5. 남은 리스크
6. 면접 답변 90초 버전
7. 면접 답변 3분 버전
8. 예상 꼬리질문 5개

# 설계 설명 기준
항상 아래 순서로 설명:
배경 -> 문제 -> 대안 -> 선택 이유 -> 트레이드오프 -> 검증 -> 한계

# 반드시 깊게 설명해야 할 주제
- 왜 MSA인가
- 왜 waitingroom-app을 분리했는가
- 좌석 중복 예약을 어떻게 막는가
- 좌석 선점 TTL과 만료 처리는 어떻게 하는가
- payment idempotency를 어떻게 보장하는가
- 결제 성공 후 예약 실패 시 어떻게 복구하는가
- scg-app에서 Gateway/Config를 왜 썼는가
- Redis와 DB 중 어느 것이 source of truth인가
- 어디가 병목이라고 보는가
- 어떤 메트릭과 trace로 검증했는가

# 금지/주의
- .env, .env.*, secrets, credentials는 읽지 말 것
- 코드에 없는 기능을 있다고 가정하지 말 것
- 큰 리팩터링보다 작은 변경을 우선할 것
- 새로운 기술을 추가할 때는 기존 대안과 비교할 것
- 구현보다 설명 가능성과 검증 가능성을 우선할 것

# 답변 스타일
- 피상적인 표현을 피할 것
- "확장성", "안정성", "운영" 같은 단어는 근거와 함께 쓸 것
- 실제 코드, 로그, 테스트, 메트릭 근거가 없으면 단정하지 말 것

# 우선순위가 충돌하면
1. 정합성
2. 동시성 안전성
3. 복구 가능성
4. 관측 가능성
5. 개발 속도

# Documentation

프로젝트 문서는 docs/ 디렉토리에 정리되어 있다.

구조:
- docs/architecture : 플랫폼 아키텍처
- docs/services : 각 서비스 설계
- docs/api : API 명세
- docs/observability : 모니터링
- docs/operations : 운영 문서
- docs/performance : 성능 테스트

항상 먼저 참고할 문서:
- @docs/architecture/overview.md
- @docs/api/api-spec.md
- @docs/data/database-cache-design.md
- @docs/services/payment/payment-architecture.md
- @docs/observability/observability.md
- @docs/operations/incident-runbook.md

작업 전에 반드시 다음 문서를 참고한다.

docs/
- 01-api-specification.md
- 02-architecture-infrastructure.md
- 03-database-cache-design.md
- 04-security-auth-rate-limiting.md
- 05-observability.md
- 06-developer-experience.md
- 07-performance-test-runbook.md
- 08-additional-recommended-docs.md

Documentation generation rule:

- Do not generate all documentation at once.
- Generate documents step by step.
- Always verify existing code before writing documentation.
- Avoid speculative architecture not present in the code.
- 반드시 현재 코드와 위 문서를 기준으로 작성
- 코드에 없는 기능은 추측해서 쓰지 말 것
- 미구현 항목은 planned 또는 proposed 로 명시
- 기존 문서와 중복을 최소화할 것