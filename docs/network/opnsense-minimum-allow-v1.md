# OPNsense 최소 허용 네트워크 연결 v1

## 1. 작업 이름

**OPNsense Minimum-Allow Network Policy v1** — WSL2 스테이징 ↔ Hyper-V 홈랩 최소 허용 연결

## 2. 목표 한 줄

WSL2 스테이징(192.168.124.100)과 Hyper-V 홈랩(10.10.10.x) 사이에 필요한 서비스/포트만 단방향으로 허용하여 관측 연결을 확보한다.

## 3. 왜 지금 OPNsense 최소 허용 네트워크 연결이 먼저인지

1. 현재 10.10.10.x와 192.168.124.x는 서로 통신이 불가능하다. 어떤 관측/모니터링 통합도 네트워크 연결이 선행되어야 한다.
2. Prometheus Federation, InfraOps Portal healthcheck, Loki 로그 수집 모두 네트워크 경로가 있어야 동작한다.
3. 네트워크 연결 없이 Grafana를 옮기면 빈 대시보드만 뜬다. 관측 데이터 경로가 먼저다.
4. 전체 대역 개방이 아닌 최소 허용으로 시작해야 나중에 정책 축소가 아닌 정책 확장 방향으로 관리할 수 있다.
5. 이 단계가 완료되어야 이후 Federation → healthcheck → Loki 수집 → Grafana 이관 순서를 진행할 수 있다.

## 4. 왜 10.10.10.0/24 ↔ 192.168.124.0/24 전체 허용이 위험한지

1. WSL2에는 Jenkins, GitLab, MySQL 등 인증 정보가 있는 서비스가 있다. 전체 개방 시 홈랩 VM이 침해되면 스테이징 전체가 노출된다.
2. 홈랩은 학습/실험 환경이라 설정 실수, 취약한 서비스가 올라갈 수 있다. 이런 환경이 스테이징에 무제한 접근하면 안 된다.
3. k3s(10.10.10.30)에는 향후 다양한 워크로드가 올라간다. 전체 허용 시 k3s에서 스테이징 DB/CI에 직접 접근 가능해진다.
4. 양방향 전체 개방은 문제 발생 시 어떤 트래픽이 원인인지 추적이 불가능하다. 최소 허용이면 룰에 없는 트래픽 = 비정상으로 즉시 판별된다.
5. 포트폴리오/면접에서 "전체 개방했다"는 보안 의식 부재로 평가된다. "최소 허용 정책을 적용했다"가 실무 수준이다.

## 5. 현재 환경 역할 재정의

| 대역 | 역할 | 핵심 서비스 |
|---|---|---|
| 192.168.124.100 | **서비스 스테이징 plane** | MSA 5개, Jenkins, GitLab, MySQL, Prometheus, ELK, InfluxDB |
| 10.10.10.10 | **운영 관측 plane** | InfraOps Portal, Prometheus (Federation 수집) |
| 10.10.10.21 | **EL9 운영 실습** | Rocky Linux, httpd |
| 10.10.10.30 | **Kubernetes 검증** | k3s 단일 노드 (이번 v1 최소화) |
| 10.10.10.40 | **중앙 로그 수집** | Loki single-binary, (향후 Grafana 이관 대상) |
| 10.10.10.1 | **경계 라우터/방화벽** | OPNsense — NAT, DHCP, DNS, Firewall |

---

## 6. 192.168.124.100 실제 포트 구조 (compose/문서 기준 확인 결과)

### 6.1 핵심 발견: 대부분의 서비스가 admin-gateway(8080) 뒤에 있다

`infra-doc-consistency.md` 및 `docker-compose.yml` 교차 대조 결과:

| 호스트 포트 | 서비스 | 접근 방식 |
|---|---|---|
| **8080** | admin-gateway (nginx) | **주 진입점**. 하위 경로로 각 서비스 라우팅 |
| 8090 | scg-app 직접 접근 | 디버그용. 문서 미기재 |
| 8081 | GitLab | 외부 공개 |
| 8086 | InfluxDB | k6 write용 외부 공개 |
| 80 | MSA nginx_proxy | 서비스 간 내부 라우팅 |

### 6.2 admin-gateway(8080) 하위 경로와 인증

| 경로 | 대상 서비스 | oauth2-proxy 인증 |
|---|---|---|
| `/prometheus/` | Prometheus (내부 9090) | **있음** |
| `/grafana/` | Grafana (내부 3000) | **있음** |
| `/jenkins/` | Jenkins (내부 8080) | **있음** |
| `/alertmanager/` | Alertmanager (내부 9093) | **있음** |
| `/kibana/` | Kibana (내부 5601) | **있음** |
| `/jaeger/` | Jaeger (내부 16686) | **없음** |
| `/api/` | SCG (내부 8080) | **없음** (JWT 인증) |

### 6.3 MSA 서비스 포트 — 외부 직접 접근 불가

| 서비스 | 포트 매핑 | 접근 |
|---|---|---|
| waitingroom-app | `127.0.0.1:18082:8080` | localhost 한정 |
| concert-app | `127.0.0.1:18081:8080` | localhost 한정 |
| booking-app | `127.0.0.1:18083:8080` | localhost 한정 |
| payment-app | `expose: 8080` | 내부 네트워크만 |
| user-app | `expose: 8080` | 내부 네트워크만 |

> **결론**: MSA 서비스에 개별 포트(8081, 8085, 8087...)로 외부에서 직접 접근하는 것은 불가능하다.
> healthcheck는 SCG(8090) 또는 admin-gateway(8080) 경유로만 가능하다.

---

## 7. 이전 설계에서 틀렸던 것

| 이전 설계 | 왜 틀렸는지 | 올바른 방향 |
|---|---|---|
| R1: TCP 9090 직접 허용 | Prometheus가 호스트에 9090으로 publish되지 않음. admin-gateway(8080) 뒤 oauth2-proxy 경유 | oauth2-proxy 우회 경로 확보 또는 Prometheus 직접 포트 노출 필요 |
| R2: TCP 8080-8089 범위 허용 | MSA 서비스는 개별 포트로 외부 접근 불가. 127.0.0.1 바인딩 또는 expose only | SCG(8090) 또는 admin-gateway(8080) 경유로 healthcheck |
| 9093 Alertmanager 허용 | admin-gateway(8080) 뒤 oauth2-proxy 경유. 직접 접근 불가 | 불필요 (현재) |

---

## 8. 선행 확인 사항 (룰 설계 전 필수)

### 8.1 OPNsense 인터페이스 확인

```
OPNsense GUI → Interfaces → Overview
```

- OPNsense WAN이 192.168.124.0/24에 직접 붙어 있는가?
- 붙어 있다면 → static route 불필요
- 없다면 → 192.168.124.100/32 단일 호스트 route만 추가

### 8.2 192.168.124.100의 정체 확인

```bash
# Ubuntu VM (10.10.10.10) 에서
traceroute 192.168.124.100
```

- 직접 도달 → static route 불필요
- 타임아웃 → 경로 설정 필요 (192.168.124.100/32 단일 호스트만)

### 8.3 Prometheus Federation 가능 여부 확인 (WSL2에서)

```bash
# WSL2 내부에서 먼저 확인 — 외부 접근 테스트 전에 내부에서 되는지 확인
# admin-gateway 경유
curl -s "http://localhost:8080/prometheus/federate?match[]={job=~'.*'}" | head -5
# → oauth2-proxy가 리다이렉트 또는 401 반환할 가능성 높음

# Prometheus 직접 접근 (Docker 내부)
docker exec -it <prometheus-container> wget -qO- "http://localhost:9090/federate?match[]={job=~'.*'}" | head -5
# → 메트릭이 나오면 /federate 엔드포인트 자체는 정상
```

**Federation이 외부에서 동작하려면 아래 중 하나가 필요하다:**

| 방법 | 설명 | 난이도 |
|---|---|---|
| A. admin-gateway에 /federate bypass 추가 | gateway.conf에 `/prometheus/federate` 경로를 oauth2-proxy 없이 직접 prometheus로 프록시 | 낮음 (nginx 한 줄 추가) |
| **B. Prometheus 직접 포트 노출 — 확정** | docker-compose에 `ports: "9090:9090"` 추가 | 낮음 (compose 수정) |
| C. 현재 상태 유지 + federation 포기 | 홈랩 Prometheus가 WSL2 Prometheus를 직접 scrape하지 않음 | — |

> **확정: 방법 B (Prometheus 9090 직접 노출)**
>
> 선택 이유:
> 1. gateway.conf를 건드리지 않으므로 기존 인증 구조에 영향 없음
> 2. compose에 `ports: "9090:9090"` 한 줄만 추가하면 됨
> 3. Federation은 머신 간 통신이므로 oauth2-proxy 경유가 불필요
> 4. OPNsense에서 출발지를 HOMELAB_OBSERVER(10.10.10.10)로 한정하므로 보안 수준 유지
>
> WSL2 스테이징 docker-compose 수정 사항:
> ```yaml
> prometheus:
>   # 기존 expose만 되어 있던 것을 ports로 변경
>   ports:
>     - "9090:9090"
> ```
> OPNsense에 R1-B(TCP 9090) 룰 추가 필요. 기존 R1(TCP 8080)은 healthcheck 전용으로 유지.

### 8.4 healthcheck 경로 확인 (보류 — Federation 성공 이후 진행)

MSA 서비스 healthcheck는 SCG 경유로만 가능하다.
SCG 라우팅 설정(`scg-app/src/main/resources/application.yml`)에서 각 서비스의 actuator 경로를 확인해야 한다.

```bash
# WSL2에서 SCG 직접 접근 (8090)
curl -s http://localhost:8090/api/v1/concerts/actuator/health
# 또는 admin-gateway 경유 (8080)
curl -s http://localhost:8080/api/v1/concerts/actuator/health
```

> 위 경로는 예시다. 실제 SCG route 설정에서 각 서비스의 prefix와 actuator endpoint 접근 가능 여부를 확인해야 한다.
> **보류 사유**: SCG route 설정이 아직 확정되지 않았고, actuator 노출 범위도 미정.
> Federation(9090) + Loki(3100)이 먼저 성공한 후 healthcheck 경로를 확정한다.

### 8.5 Windows Host 방화벽 확인 (OPNsense 이전 또는 병행)

OPNsense 룰만으로는 부족하다. WSL2가 실행되는 Windows 호스트의 방화벽(Windows Defender Firewall)이 인바운드 트래픽을 차단할 수 있다.

**확인 순서:**

```powershell
# 1. Windows 호스트에서 — 현재 인바운드 룰 확인
Get-NetFirewallRule -Direction Inbound -Enabled True | 
  Where-Object { $_.Action -eq "Allow" } | 
  Get-NetFirewallPortFilter | 
  Where-Object { $_.LocalPort -in @("8080","9090","3100") }

# 2. 9090 포트가 실제로 listen 중인지 확인
netstat -an | findstr ":9090"
# 또는
Test-NetConnection -ComputerName localhost -Port 9090

# 3. 외부(10.10.10.10)에서 접근 가능한지 확인
# → OPNsense 룰 적용 후 Ubuntu VM에서:
Test-NetConnection -ComputerName 192.168.124.100 -Port 9090
```

**인바운드 룰 추가가 필요한 경우:**

```powershell
# Prometheus Federation용 (TCP 9090)
New-NetFirewallRule -DisplayName "Prometheus Federation (homelab)" `
  -Direction Inbound -Protocol TCP -LocalPort 9090 `
  -RemoteAddress 10.10.10.0/24 -Action Allow

# Loki push 응답용은 불필요 — outbound 연결이므로 Windows 방화벽이 자동 허용
```

> **주의**: WSL2 네트워크 모드(NAT vs bridged)에 따라 Windows 방화벽 동작이 다르다.
> `bridged` 모드면 Windows 방화벽 인바운드 룰이 직접 적용된다.
> `NAT` 모드면 portproxy 설정도 함께 확인해야 한다.

---

## 9. 허용 포트표 (실제 구조 기반)

### 9.1 확정 허용 (3개)

| # | 출발지 | 목적지 | 포트 | 용도 | 비고 |
|---|---|---|---|---|---|
| R1-B | 10.10.10.10 | 192.168.124.100 | **TCP 9090** | Prometheus Federation 직접 | 방법 B 확정. compose에 `ports: "9090:9090"` 추가 필요 |
| R2 | 192.168.124.100 | 10.10.10.40 | **TCP 3100** | Loki HTTP push | WSL2 → Loki 로그 전송 |
| R1 | 10.10.10.10 | 192.168.124.100 | **TCP 8080** | healthcheck (SCG/admin-gateway 경유) | healthcheck 경로 확정 후 추가. 당장은 보류 가능 |

> **실행 우선순위**: R2(Loki 3100) 먼저 → R1-B(Prometheus 9090) → R1(8080 healthcheck, 보류)

### 9.2 선택 허용 (진단용, 2개)

| # | 출발지 | 목적지 | 포트 | 용도 |
|---|---|---|---|---|
| R3 | 10.10.10.10 | 192.168.124.100 | ICMP | ping 진단 |
| R4 | 10.10.10.40 | 192.168.124.100 | ICMP | ping 진단 |

### 9.4 허용하지 않는 것

| 포트/서비스 | 이유 |
|---|---|
| 개별 MSA 포트 (8081-8092) | 호스트에 publish되지 않음. 외부에서 접근 자체가 불가 |
| TCP 9090 (HOMELAB_OBSERVER 외 출발지) | Federation은 10.10.10.10에서만 허용. 그 외 출발지는 차단 |
| TCP 9093 (Alertmanager) | admin-gateway(8080) 뒤 oauth2-proxy 경유. 별도 포트 불필요 |
| TCP 22 (SSH) | 필요 확인 안 됨 |
| TCP 3306 (MySQL) | 스테이징 내부 전용 |
| TCP 8081 (GitLab) | CI 도구, 홈랩 접근 불필요 |
| TCP 50000 (Jenkins) | CI 도구, 홈랩 접근 불필요 |
| TCP 3000 (Grafana) | admin-gateway 뒤. 이관 전 불필요 |
| 10.10.10.21 → ANY | Rocky VM 접근 사유 없음 |
| 10.10.10.30 → ANY | k3s v1 접근 불필요 |

---

## 10. OPNsense Alias (3개)

| Alias 이름 | 타입 | 값 | 설명 |
|---|---|---|---|
| `WSL2_STAGING` | Host(s) | 192.168.124.100 | WSL2 스테이징 서버 (단일 호스트) |
| `HOMELAB_OBSERVER` | Host(s) | 10.10.10.10 | Ubuntu VM — 관측/관리 주체 |
| `HOMELAB_LOKI` | Host(s) | 10.10.10.40 | Loki 로그 수집 VM |

---

## 11. OPNsense 방화벽 룰

### 11.1 룰 인터페이스 결정

- 홈랩(10.10.10.x) → 스테이징(192.168.124.100): 패킷이 OPNsense **LAN**으로 들어옴 → **LAN 룰**
- 스테이징(192.168.124.100) → 홈랩(10.10.10.40): 패킷이 OPNsense 어느 인터페이스로 들어오는지 **8번 선행 확인에서 결정**

### 11.2 LAN 룰

#### 룰 #1: Prometheus Federation 직접 접근 (방법 B 확정)

| 항목 | 값 |
|---|---|
| Action | Pass |
| Interface | LAN |
| Direction | in |
| Protocol | TCP |
| Source | `HOMELAB_OBSERVER` |
| Destination | `WSL2_STAGING` |
| Destination Port | 9090 |
| Description | [v1-R1-B] Prometheus Federation direct (방법 B) |

#### 룰 #2: admin-gateway 접근 (healthcheck — 보류, 경로 확정 후 추가)

| 항목 | 값 |
|---|---|
| Action | Pass |
| Interface | LAN |
| Direction | in |
| Protocol | TCP |
| Source | `HOMELAB_OBSERVER` |
| Destination | `WSL2_STAGING` |
| Destination Port | 8080 |
| Description | [v1-R1] admin-gateway — healthcheck (SCG 경유) |

> **보류**: SCG route 설정에서 actuator 경로가 확정될 때까지 이 룰 추가를 미룰 수 있다.
> Federation(9090)이 성공한 후에 추가해도 무방하다.

#### 룰 #3: ICMP Observer (선택)

| 항목 | 값 |
|---|---|
| Action | Pass |
| Interface | LAN |
| Protocol | ICMP |
| ICMP type | Echo Request |
| Source | `HOMELAB_OBSERVER` |
| Destination | `WSL2_STAGING` |
| Description | [v1-R3] Ping observer→staging |

#### 룰 #4: ICMP Loki (선택)

| 항목 | 값 |
|---|---|
| Action | Pass |
| Interface | LAN |
| Protocol | ICMP |
| ICMP type | Echo Request |
| Source | `HOMELAB_LOKI` |
| Destination | `WSL2_STAGING` |
| Description | [v1-R4] Ping loki→staging |

### 11.3 Loki Push 룰 (인터페이스는 선행 확인 후 결정)

| 항목 | 값 |
|---|---|
| Action | Pass |
| Interface | **(8번에서 확인한 인터페이스)** |
| Protocol | TCP |
| Source | `WSL2_STAGING` |
| Destination | `HOMELAB_LOKI` |
| Destination Port | 3100 |
| Description | [v1-R2] Loki push from staging |

---

## 12. 적용 순서

```
[Step 1] 토폴로지 확인 (8.1, 8.2)
         OPNsense 인터페이스, traceroute, 192.168.124.100 정체
         → 확인 없이 다음 단계 금지

[Step 2] Windows Host 방화벽 확인 (8.5)
         인바운드 9090, 3100 허용 여부 확인
         필요시 방화벽 룰 추가
         → WSL2 NAT/bridged 모드 확인

[Step 3] 라우팅 (필요한 경우에만)
         192.168.124.100/32 단일 호스트 route만
         서브넷 전체(/24) 금지

[Step 4] ping 테스트
         10.10.10.10 → ping 192.168.124.100
         → 실패하면 STOP

[Step 5] Alias 3개 생성
         OPNsense: Firewall → Aliases

[Step 6] Loki push 룰 먼저 추가 + 검증 (R2, TCP 3100)
         가장 단순한 통신부터 성공시킨다
         → curl POST 204 확인

[Step 7] WSL2에서 Prometheus 9090 직접 노출 (방법 B)
         docker-compose에 ports: "9090:9090" 추가
         docker compose up -d prometheus (재시작)
         → WSL2 내부에서 curl localhost:9090/federate 확인

[Step 8] Prometheus Federation 룰 추가 + 검증 (R1-B, TCP 9090)
         OPNsense LAN 룰 추가
         → Ubuntu VM에서 curl 192.168.124.100:9090/federate 확인

[Step 9] ICMP 룰 추가 (R3, R4 — 선택)

[Step 10] (보류) healthcheck 룰 추가 (R1, TCP 8080)
          SCG route 설정에서 actuator 경로 확정 후 진행
```

> **핵심 실행 순서**: Loki 3100 먼저 성공 → Prometheus 9090 직접 publish → healthcheck 경로는 마지막

---

## 13. 검증 방법

### 13.1 Loki Push 확인 (가장 먼저)

```bash
# WSL2 (192.168.124.100) 에서
curl -s -o /dev/null -w "%{http_code}" \
  -X POST "http://10.10.10.40:3100/loki/api/v1/push" \
  -H "Content-Type: application/json" \
  -d '{"streams":[{"stream":{"job":"test","host":"wsl2"},"values":[["'$(date +%s)000000000'","opnsense-v1-test-log"]]}]}'
# → 204 성공 / timeout 실패
```

### 13.2 Prometheus Federation 확인 (방법 B — 9090 직접)

```bash
# Ubuntu VM (10.10.10.10) 에서

# 방법 B 확정 — 9090 직접 접근:
curl -s "http://192.168.124.100:9090/federate?match[]={job=~'.*'}" | head -20

# → 메트릭 텍스트 출력 = 성공
# → connection refused = Prometheus가 9090으로 publish 안 됨 (compose 확인)
# → timeout = OPNsense 룰 또는 라우팅 문제 또는 Windows 방화벽 차단
```

### 13.3 healthcheck 확인 (SCG 경유)

```bash
# Ubuntu VM (10.10.10.10) 에서
# SCG actuator (정확한 경로는 SCG route 설정 확인 후 결정)
curl -s -o /dev/null -w "%{http_code}" http://192.168.124.100:8080/api/<확인된경로>/actuator/health
```

### 13.4 차단 확인

```bash
# Ubuntu VM (10.10.10.10) 에서 — 아래는 모두 실패해야 정상
curl -s --connect-timeout 3 http://192.168.124.100:3306   # MySQL → timeout
curl -s --connect-timeout 3 http://192.168.124.100:3000   # Grafana → timeout

# Rocky VM (10.10.10.21) 에서 — 전부 실패해야 정상
ping -c 2 -W 3 192.168.124.100    # timeout
```

---

## 14. 실패 판정 기준

| 단계 | 실패 조건 | 조치 |
|---|---|---|
| Step 4 ping | timeout | 토폴로지 재확인. static route 필요 여부 판단 |
| Step 6 Loki push | timeout | Loki 3100 listen 확인. 방화벽 룰 인터페이스 재확인. Windows 방화벽 확인 |
| Step 7 내부 federate | connection refused | compose에 `ports: "9090:9090"` 미적용. 컨테이너 재시작 확인 |
| Step 8 Federation 외부 | timeout | OPNsense LAN 룰 확인. Windows 방화벽 인바운드 9090 확인 |
| Step 8 Federation 외부 | connection refused | WSL2에서 9090이 외부 바인딩 안 됨. `netstat -an | grep 9090` 확인 |
| 차단 확인 | 열지 않은 포트 응답 | OPNsense default deny 확인. 기존 allow-all 룰 존재 여부 확인 |

---

## 15. 이번 v1에서 하지 말아야 할 것

- 192.168.124.0/24 서브넷 전체 static route
- 개별 MSA 포트(8081-8092) 허용 시도 (호스트에 publish 안 됨)
- 8080-8089 범위 통 허용
- 9093, 22 필요 확인 없이 허용
- Grafana(3000) 이관
- k3s(10.10.10.30), Rocky(10.10.10.21) ↔ 스테이징 연결
- 전체 대역 ANY→ANY 개방
- VLAN/IDS/IPS/Suricata 고급 설계

---

## 16. 문서/Runbook

```
docs/network/opnsense-minimum-allow-v1.md    ← 현재 파일
```

향후:

```
docs/network/opnsense-minimum-allow-v2.md    ← healthcheck 경로 확정 + SCG route 검증 후
docs/network/opnsense-minimum-allow-v3.md    ← Grafana 이관 완료 후
```

---

## 17. 최종 요약

### 17.1 지금 바로 적용할 최소 허용 포트표

| # | 출발지 | 목적지 | 포트 | 용도 | 우선순위 |
|---|---|---|---|---|---|
| R2 | 192.168.124.100 | 10.10.10.40 | TCP 3100 | Loki push | 1순위 |
| R1-B | 10.10.10.10 | 192.168.124.100 | TCP 9090 | Prometheus Federation (방법 B) | 2순위 |
| R3 | 10.10.10.10 | 192.168.124.100 | ICMP | ping (선택) | — |
| R4 | 10.10.10.40 | 192.168.124.100 | ICMP | ping (선택) | — |
| R1 | 10.10.10.10 | 192.168.124.100 | TCP 8080 | healthcheck (보류) | 3순위 |

### 17.2 지금 바로 넣을 최소 룰

**Loki push용 (1개)** — 인터페이스는 선행 확인 후:
1. `[v1-R2]` WSL2_STAGING → HOMELAB_LOKI : TCP 3100

**OPNsense LAN (2~3개)**:
2. `[v1-R1-B]` HOMELAB_OBSERVER → WSL2_STAGING : TCP 9090 (Federation)
3. `[v1-R3]` HOMELAB_OBSERVER → WSL2_STAGING : ICMP (선택)
4. `[v1-R4]` HOMELAB_LOKI → WSL2_STAGING : ICMP (선택)

**보류**:
5. `[v1-R1]` HOMELAB_OBSERVER → WSL2_STAGING : TCP 8080 (healthcheck 경로 확정 후)

### 17.3 OPNsense 외에 해야 할 것

| 작업 | 이유 | 시점 |
|---|---|---|
| docker-compose에 `ports: "9090:9090"` 추가 (방법 B 확정) | Prometheus /federate 엔드포인트 외부 노출 | Step 7 |
| Windows Host 방화벽 인바운드 9090 확인/추가 | OPNsense만으로는 Windows 호스트 도달 불가할 수 있음 | Step 2 |
| WSL2 NAT/bridged 모드 확인 | 네트워크 경로에 따라 방화벽 동작이 달라짐 | Step 2 |

### 17.4 이번 v1에서 하지 말아야 할 것

- 개별 MSA 포트 허용 (호스트에 publish 안 됨)
- 서브넷 전체 static route
- 9093/22/3000 허용
- Grafana 이관
- k3s/Rocky ↔ 스테이징 연결
- gateway.conf 수정 (방법 A 불채택)

---

**작성일**: 2026-04-16
**버전**: v1.3 (Federation 방법 B 확정, Windows Host 방화벽 검증 추가, healthcheck 보류 처리)
**변경 이력**:
- v1.0: 초안 (포트 구조 오류 포함)
- v1.1: 사용자 피드백 반영 (static route /32, 범위 허용 제거, 토폴로지 선행 확인 추가)
- v1.2: 전면 재작성 (실제 포트 구조 반영. admin-gateway 8080 경유 구조, MSA 개별 포트 접근 불가 반영)
- v1.3: Federation 방법 B(9090 직접) 확정, Windows Host 방화벽 검증 섹션 추가, healthcheck 보류 처리
**다음 버전 트리거**: SCG healthcheck 경로 확정 시 → v2
