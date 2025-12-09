# 1. 요구사항 및 운영 정책

## 1.1 시스템 목표
* 모든 사용자 요청에 대 100ms 이내 응답을 목표로 함. (Prometheus & Grafana로 모니터링 함)
* AWS RDS DB와의 안정적인 연결 유지.

## 1.2 배포 및 운영 정책
* 배포 전략 : Docker/Nginx를 사용한 Blue/Green 무중단 배포를 사용함.
* 배포 주체 : 오직 Jenkins CI/CD 파이프라인을 통해서만 배포가 허용됨.

# 2. Git 브랜치 및 통합 전략

## 2.1 브랜치 규칙
* main : 운영 환경 반영 대상 (안정 버전)
* feature/<작업명> : 모든 기능 개발은 이 브랜치에서 진행.

## 2.2 통합 (Merge) 규칙
* feature -> main 병합 시 반드시 Merge Request(MR)를 사용해야함.
* 병합 시 옵션은 Squash and Merge를 선택하여, main의 커밋 히스토리를 깔끔하게 유지함.

# 3. 커밋 메시지 컨벤션
[유형] : <내용> 형식 사용
* feat : 새로운 기능 추가
* fix : 버그 수정
* docs : 문서 (MD 파일 포함) 수정
* style : 코드 스타일 변 (코드 포매팅, 세미콜론 누락 등)
* design : 사용자 UI 디자인 변경 (CSS 등)
* test : 테스트 코드, 리팩토링 (Test Code)
* refactor : 리팩토링 (Production Code)
* build : Jenkinsfile, Dockerfile, Gradle 같은 빌드 파일 수정
* ci : CI 설정 파일 수정
* perf : 성능 개선
* chore : 자잘한 수정이나 빌드 업데이트
* rename : 파일 혹은 폴더명을 수정만 한 경우
* remove : 파일을 삭제만 한 경우

# 4. 코드 품질 및 리뷰 정책 (Code Quality & Review Policy)

## 4.1 정적 분석 및 테스트
* 모든 코드는 반드시 빌드 전에 로컬에서 정적 분석 도구 (예: SonarQube, Detekt)를 통과해야함.
* CI 파이프라인은 모든 유닛 테스트 및 통합 테스트를 100% 통과해야만 다음 단계(Docker Build)로 진행됨.

## 4.2 Code Review
* Merge Request(MR)는 최소 1명 이상의 승인(Approval)을 받아야 main 브랜치 병합될 수 있음.
* 리뷰어는 코드 스타일, 성능 저하 가능성, 보안 취약점을 중점적으로 검토해야함.

# 5. 복원력 및 Health Check 정책 (Resilience & Health Check)

## 5.1 Health Check 엔드포인트
* 모든 Spring Boot 애플리케이션은 /actuator/health 엔드포인트를 통해 상태를 노출시켜야함.
* DB 연결, 디스크 공간 등 핵심 의존성 상태(Liveness, Readiness)를 해당 엔드포인트에서 확인할 수 있어야함.

## 5.2 장애 복구 (Rollback)
* 배포 실패 또는 심각한 장애 발생 시, Jenkins 파이프라인의 이전 성공 빌드 번호를 사용하여 구 버전 이미지로 즉시 Rollback을 수행해야함.
* Rollback은 반드시 CI/CD 도구(Jenkins)를 통해서만 진행하며, 수동 컨테이너 조작은 금지함.

# 6. CI/CD 환경 변수 관리 (Environment Variables)

## 6.1 민감 정보 보호
* DB 비밀번호, Docker Hub 토큰, AWS SSH 키 등 모든 민감 정보는 절대로 코드(application.properties 등)에 하드코딩 하지 않음.
* 민감 정보는 Jenkins Credential Manager 또는 EC2 환경 변수 파일(.docker_user, .docker_pass)을 통해서만 주입되어야 함.
