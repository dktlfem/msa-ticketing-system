# 04. 보안 및 인증 (Security & Auth)

## 1. 현재 보안 전략 요약

이 프로젝트의 보안 설계는 **외부 노출면 축소 + 운영 도구 접근 통제 + 내부 API 은닉 + 결제 정합성 보호**를 핵심으로 합니다.

핵심 통제는 다음과 같습니다.

- VPN-only ingress
- GitLab 2차 인증(MFA)
- oauth2-proxy 기반 내부 도구 접근 통제
- gateway에서 `/internal/**` 차단
- `X-Auth-User-Id` 표준화
- Redis 기반 멱등성
- 결제 secret key 환경변수 관리
- Bitwarden + OTP 기반 recovery/secret 관리

---

## 2. 네트워크 경계 보안

### 현재 구현

- 집 공유기 이중 NAT 환경에서 외부에 개방한 포트는 **1194/UDP(OpenVPN)** 하나
- staging 및 Redis 노드는 내부 IP로만 유지
- 운영자/개발자는 VPN 접속 이후에만 내부 서비스 접근 가능

### 장점

- GitLab/Jenkins/Grafana/Prometheus/Jaeger/Kibana 같은 운영 도구가 직접 인터넷에 노출되지 않음
- 애플리케이션 서비스도 public exposure 없이 내부망에서만 확인 가능
- 공격면이 로그인 페이지/관리자 UI 단위가 아니라 **VPN entry point 하나**로 수렴

---

## 3. 운영 도구 인증/인가

### 현재 구현

- GitLab에 2중 MFA 적용
- oauth2-proxy를 통해 GitLab에 로그인한 사용자만 Jenkins/Grafana/Prometheus 접근
- Bitwarden에 GitLab recovery code 저장
- Google Authenticator / Bitwarden OTP로 OTP 관리

### 설계 의도

운영 접근을 서비스별 로컬 계정으로 흩어놓지 않고,  
**GitLab identity를 중심으로 중앙 통제**하는 구조입니다.

### 문서에 적으면 좋은 표현

> 운영용 내부 도구 접근을 GitLab 기반 OAuth2/OIDC 플로우로 통합하고, GitLab MFA와 비밀정보 관리 도구(Bitwarden/OTP)를 함께 적용하여 운영 계정 보안을 강화했습니다.

---

## 4. 애플리케이션 인증/인가 모델

## 4.1 현재 상태

현재 서비스 레벨에서는 완전한 JWT/OIDC Resource Server까지는 가지 않았고,  
우선 **헤더 기반 사용자 식별 표준화**를 먼저 반영했습니다.

- 표준 헤더: `X-Auth-User-Id`
- 하위 호환 헤더: `X-User-Id`
- MVC 컨트롤러: `@AuthUserId` ArgumentResolver 사용
- SCG: legacy header를 `X-Auth-User-Id`로 bridge 가능

즉, 지금은 **인증 자체를 완성**했다기보다,  
향후 JWT/OIDC 전환을 위한 **API 시그니처와 진입 게이트웨이 표준화**를 먼저 끝낸 상태입니다.

## 4.2 왜 이런 순서를 택했는가

바로 JWT/OIDC로 가면 서비스별 SecurityConfig, 테스트, API 계약이 동시에 흔들릴 수 있습니다.  
그래서 먼저:

1. 컨트롤러 시그니처 표준화
2. gateway에서 공통 헤더 정리
3. 이후 JWT/OIDC 검증 계층을 추가

이 순서가 안전합니다.

## 4.3 차기 목표

- `scg-app` 또는 auth/BFF 레이어에서 JWT 검증
- 검증된 사용자 정보만 `X-Auth-User-Id`로 downstream 전달
- `X-User-Id` bridge 비활성화
- 관리자 API와 일반 사용자 API 권한 분리

---

## 5. 내부 API 은닉

`scg-app`에는 `InternalPathBlockGlobalFilter`가 적용되어 있습니다.

- `/internal/**` 요청은 gateway에서 선차단
- 기본 상태 코드는 404
- 외부 사용자는 내부 API 존재 여부를 알기 어려움

### 왜 중요한가

이 프로젝트는 내부 API를 많이 사용합니다.

- `booking-app` -> `waitingroom-app internal`
- `booking-app` -> `concert-app internal`
- `payment-app` -> `booking-app internal`
- `payment-app` -> `concert-app internal`

내부 API를 외부에 그대로 노출하면 서비스 간 계약과 외부 API 계약이 섞여 버립니다.  
그래서 **외부 계약과 내부 계약을 분리**하는 게 중요합니다.

---

## 6. 결제 보안

## 6.1 PG 연동

- 외부 PG는 Toss Payments 사용
- `paymentKey`, `orderId`, `amount`를 서버에서 교차 검증
- payment secret key는 환경변수로 주입
- 코드에 live secret를 하드코딩하지 않음

## 6.2 멱등성

`prepare`, `confirm`, `cancel`은 모두 `Idempotency-Key`를 필수로 요구합니다.

Redis 저장 구조:

- `...:processing`
- `...:response`

그리고 request payload hash를 함께 저장해서

- 같은 키 + 같은 본문 => 같은 결과 재사용 가능
- 같은 키 + 다른 본문 => 즉시 차단

### 왜 중요한가

결제는 네트워크 재시도, 더블 클릭, 모바일 재전송, 브라우저 재호출이 항상 발생합니다.  
멱등성 없이 payment confirm을 열어두면 **중복 승인** 또는 **중복 취소**가 발생할 수 있습니다.

---

## 7. Rate Limiting

## 7.1 현재 구현

현재 코드에는 `waitingroom-app`에 이벤트 단위 rate limiting이 들어가 있습니다.

- key: `rate_limit:event:{eventId}:{epochSecond}`
- 방식: Redis `INCR` + `EXPIRE`
- 제한: 초당 100개 진입 허용

즉, 토큰 발급 구간의 DB 쓰기/토큰 발급 폭주를 제어합니다.

## 7.2 왜 waitingroom에 먼저 적용했는가

가장 큰 순간 트래픽이 몰리는 지점이 **티켓 오픈 직후의 대기열 통과 구간**이기 때문입니다.

- 모든 사용자가 동시에 status polling
- 통과 직전 rank 사용자들이 한꺼번에 token 발급 시도
- DB write + Redis remove + token persist가 집중

이 구간을 먼저 보호하는 것이 전체 플랫폼 안정성에 가장 효과적입니다.

## 7.3 추가로 권장하는 Rate Limiting

### gateway 레벨

- IP 기준 burst limit
- userId 기준 sliding window
- `/api/v1/payments/confirm` 별도 보호

### payment 레벨

- `userId + reservationId` 기준 confirm 폭주 방지
- `paymentId + cancel` 기준 cancel 폭주 방지

### admin plane

- Jenkins/Grafana 로그인 시도 rate limit
- oauth2-proxy 실패 횟수 기반 lockout 또는 외부 IdP 정책 연계

---

## 8. 비밀정보 관리

현재 문서에 아래 원칙을 명시해두면 좋습니다.

- PG secret, Redis password, DB password는 코드 저장소에 커밋하지 않음
- 환경변수 또는 secret store를 통해 주입
- recovery code는 Bitwarden에 저장
- OTP는 Bitwarden/Google Authenticator로 관리
- 운영 계정은 개인 계정과 분리하고 MFA 강제

### 차기 고도화

- AWS SSM Parameter Store 또는 Secrets Manager 연동
- Jenkins credential binding 표준화
- key rotation runbook 문서화

---

## 9. 추가 보안 고도화 권장안

### 9.1 바로 효과가 큰 것

- `user-app`, `concert-app`의 공개 엔드포인트를 역할 기반으로 정리
- 관리자용 API와 일반 사용자용 API 분리
- SecurityConfig의 `permitAll` 범위를 staging/prod profile별로 분리
- Swagger 문서도 내부망 또는 관리자만 접근하도록 제한

### 9.2 다음 단계

- JWT/OIDC Resource Server 도입
- 내부 서비스 간 mTLS 또는 service identity 검토
- 감사 로그(audit log) 별도 적재
- 관리자 작업(취소 재처리, 장애 복구 등)에 대한 role-based access control

---

## 10. 포트폴리오에서 꼭 지켜야 할 정직한 표현

문서에는 반드시 **현재 구현**과 **계획/로드맵**을 구분해서 적는 것이 좋습니다.

### 좋은 표현

> 현재는 VPN-only ingress와 OIDC/OAuth2 기반 운영 도구 보호, 헤더 기반 사용자 식별 표준화, `/internal/**` 차단, Redis 멱등성 제어를 구현했고, 차기 단계로 JWT/OIDC Resource Server와 Secrets Manager 연동을 계획하고 있습니다.

### 피해야 할 표현

- “전체 서비스가 JWT 인증을 이미 사용한다”
- “전 구간 mTLS를 적용했다”
- “완전한 Zero Trust 구조다”

구현하지 않은 것을 과장하면 면접에서 바로 드러납니다.

---

## 11. 핵심 한 줄

이 프로젝트의 보안 강점은 **복잡한 보안 제품을 많이 붙였다는 것**이 아니라,  
**노출면 축소, 운영 접근 중앙통제, 내부 API 분리, 결제 멱등성 보호**를 실제 운영 흐름에 맞게 설계했다는 점입니다.