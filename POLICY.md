# 프로젝트 정책 (Policy)

## 1. 요구사항 및 운영 정책

### 1.1 시스템 목표

- 모든 사용자 요청에 대해 Gateway 레벨 P95 응답시간 100ms 이내를 목표로 함 (Prometheus + Grafana로 모니터링)
- AWS RDS(MySQL 8.0)와의 안정적인 연결 유지
- 장애 발생 시 Circuit Breaker Fallback으로 Fail-fast 응답 보장

### 1.2 배포 및 운영 정책

- 배포 전략: Docker + Nginx를 사용한 Blue/Green 무중단 배포
- 배포 주체: 오직 Jenkins CI/CD 파이프라인을 통해서만 배포가 허용됨
- 배포 대상: 프로덕션(AWS EC2), 스테이징(홈 서버 192.168.124.100)

---

## 2. Git 브랜치 및 통합 전략

### 2.1 브랜치 규칙

- **main**: 운영 환경 반영 대상 (안정 버전)
- **feature/\<작업명\>**: 모든 기능 개발은 이 브랜치에서 진행

### 2.2 통합 (Merge) 규칙

- feature → main 병합 시 반드시 Merge Request(MR)를 사용해야 함
- 병합 시 Squash and Merge를 선택하여 main의 커밋 히스토리를 깔끔하게 유지

---

## 3. 커밋 메시지 컨벤션

Angular 스타일의 Conventional Commits 규칙을 따름.

### 3.1 형식

```
<type>(<scope>): <subject>
```

scope는 선택 사항이며, 변경된 서비스 모듈명을 명시함 (예: `feat(booking-app): 분산락 적용`)

### 3.2 유형 (Type)

- **feat**: 새로운 기능 추가
- **fix**: 버그 수정
- **docs**: 문서 수정 (MD 파일 포함)
- **style**: 코드 스타일 변경 (포매팅, 세미콜론 누락 등)
- **test**: 테스트 코드 추가/수정
- **refactor**: 리팩토링 (기능 변경 없는 코드 개선)
- **build**: Jenkinsfile, Dockerfile, Gradle 빌드 파일 수정
- **ci**: CI 설정 파일 수정
- **perf**: 성능 개선
- **chore**: 자잘한 수정이나 빌드 업데이트
- **rename**: 파일 또는 폴더명만 변경한 경우
- **remove**: 파일 삭제만 한 경우

---

## 4. 코드 품질 및 리뷰 정책

### 4.1 정적 분석 및 테스트

- CI 파이프라인은 빌드 단계에서 컴파일 오류를 검증함
- 모든 코드는 JUnit 5 + Mockito 기반 단위 테스트를 포함해야 함
- 통합 테스트는 @SpringBootTest + Testcontainers(MySQL)로 실제 DB 환경에서 검증

### 4.2 코드 리뷰 체크리스트 (1인 프로젝트)

1인 개발 프로젝트이므로 MR 승인 대신 셀프 리뷰 체크리스트를 적용:

- [ ] 코드 스타일이 기존 코드와 일관성을 유지하는가
- [ ] 성능 저하 가능성이 있는 코드는 없는가 (N+1 쿼리, 불필요한 트랜잭션 등)
- [ ] 보안 취약점은 없는가 (하드코딩된 비밀번호, SQL 인젝션 등)
- [ ] 변경에 대한 ADR 주석이 포함되어 있는가 ("왜 이 방식인지")
- [ ] 예외 처리가 GlobalExceptionHandler + ErrorCode Enum 패턴을 따르는가

---

## 5. 복원력 및 Health Check 정책

### 5.1 Health Check 엔드포인트

- 모든 Spring Boot 서비스는 `/actuator/health` 엔드포인트를 통해 상태를 노출
- DB 연결, Redis 연결, 디스크 공간 등 핵심 의존성의 Liveness/Readiness를 확인 가능해야 함
- Blue/Green 배포 시 20초 Health Check 통과가 트래픽 전환의 전제 조건

### 5.2 장애 복구 (Rollback)

- 배포 실패 또는 심각한 장애 발생 시 Jenkins 파이프라인의 이전 성공 빌드 번호를 사용하여 구 버전 이미지로 즉시 Rollback 수행
- Rollback은 반드시 CI/CD 도구(Jenkins)를 통해서만 진행하며, 수동 컨테이너 조작은 금지
- Gateway(scg-app)의 Circuit Breaker가 자동으로 장애 서비스를 격리하여 Cascading Failure 방지

---

## 6. 환경 변수 및 보안 정책

### 6.1 민감 정보 보호

- DB 비밀번호, Docker Hub 토큰, AWS SSH 키, JWT Secret 등 모든 민감 정보는 절대 코드(application.yml 등)에 하드코딩하지 않음
- 민감 정보는 Jenkins Credential Manager 또는 환경 변수 파일(.env)을 통해서만 주입

### 6.2 네트워크 보안

- 홈 스테이징 서버 접근은 OpenVPN(1194 UDP) 강제
- 2중 NAT 구성으로 외부 직접 접근 차단
- 내부 서비스(Grafana, Jaeger, Jenkins 등)는 oauth2-proxy(4180)를 경유해야만 접근 가능
- GitLab 인증: OAuth2 + 2중 MFA (Google Authenticator + Bitwarden OTP)

### 6.3 Gateway 보안 헤더 정책

- 신뢰할 수 없는 헤더(`Auth-Passport`, `Auth-User-Id`, `Internal-Token`, `Auth-Queue-Token`)는 Gateway에서 세정 후 백엔드에 전달
- JWT Secret은 최소 32바이트 이상, 환경 변수(`JWT_SECRET_KEY`)로 주입

---

## 7. 로깅 및 관측성 정책

### 7.1 로깅 표준

- SLF4J + Logback 사용, 구조화된 JSON 로그 (Logstash Logback Encoder 8.0)
- MDC에 traceId, spanId 포함하여 분산 추적 연계
- ERROR/WARN 레벨 로그는 Elasticsearch에서 자동 집계 및 알림

### 7.2 메트릭 수집

- Micrometer Prometheus로 JVM, HTTP, 커스텀 비즈니스 메트릭 수집
- Grafana 대시보드에서 서비스별 CPU/메모리, TPS, 응답시간(P50/P95/P99) 실시간 모니터링

### 7.3 분산 추적

- Jaeger를 통한 서비스 간 요청 흐름 추적
- booking-app ↔ payment-app 트랜잭션 플로우 시각화
- 슬로우 트레이스 상위 10개 자동 추출 (부하테스트 시)
