# 1. 기술 스택 정의 (Technical Stack)

## 1.1 애플리케이션 스택
* 언어 및 프레임워크 : Java 21, Spring Boot v3.4.0
* 빌드 도구 : Gradle 9.2.1
* DB 드라이버 : MySQL Connector/J 8.3.0

## 1.2 인프라 및 CI/CD 스택
* 클라우드 환경 : AWS EC2
* DB 환경 : AWS RDS (MySQL 8.0)
* CI/CD 오케스트레이션 : Jenkins Pipeline (Declarative)
* 컨테이너 기술 : Docker (Docker Compose v3.8)

# 2. 시스템 다이어그램 및 배포 흐름

## 2.1 통합 환경 (CI/CD Flow)
전체 배포 과정은 다음 3단계로 자동화되어 있으며, Jenkinsfile을 통해 제어됨.

1. Source (소스) : 개발자는 로컬에서 feature/ 브랜치에 커밋 후, GitLab에 Merge Request를 생성하고 main으로 병합함. (Jenkins 빌드 트릐거)
2. Build & Test (빌드 및 테스트) : Jenkins Agent가 코드를 가져와 Gradle로 빌드하고, Docker 이미지 생성한 후 Docker Hub에 푸시함.
3. Deploy (배포) : Jenkins가 SSH를 통해 AWS EC2에 접속하여 Blue/Green 전환을 실행함.

## 2.2 Blue/Green 무중단 배포 로직 (Deployment Logic)
EC2 인스턴스에서는 Docker Compose를 사용하여 2개의 동일한 환경(Blue : 8081, Green : 8082)을 실행하고, Nginx 프록시를 통해 트래픽을 전환함.
* Active 서버 식별 : nginx.conf 파일의 upstream 설정을 읽어 현재 Active 서버를 자동으로 결정함.
* Next 서버 구동 : 비활성 환경(Next)에 새 버전을 배포하고, 10초 Health Check 후 Nginx 설정(Active <-> Down)을 변경함.
* 무중단 전환 : docker exec nginx_proxy nginx -s reload 명령으로 Nginx를 재시작 없이 트래픽을 전환함.