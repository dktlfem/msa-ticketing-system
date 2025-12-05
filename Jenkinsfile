pipeline {
    agent any 
    
    // 환경 변수 설정 (사용자님의 Docker Hub ID와 EC2 정보로 변경해야 합니다.)
    environment {
        // 1. Docker Hub ID와 이미지 이름으로 변경하세요.
        DOCKER_IMAGE = 'dktlfem/ci-cd-test' 
        EC2_USER = 'ubuntu'
        // 2. AWS EC2 퍼블릭 IP 또는 DNS 주소로 변경하세요.
        EC2_HOST = '15.134.88.109'

        SPRING_DATASOURCE_URL = 'jdbc:mysql://cd-mysql-db.cluuo6ag6qpg.ap-southeast-2.rds.amazonaws.com:3306/dev_db?serverTimezone=Asia/Seoul&useSSL=false&allowPublicKeyRetrieval=true'
        SPRING_DATASOURCE_USERNAME = 'admin'
        SPRING_DATASOURCE_PASSWORD = 'admin1234'
    }

    options {
        // 빌드가 시작될 때마다 워크스페이스를 완전히 정리하도록 설정 (필수)
        skipDefaultCheckout() // 기본 checkout 로직 비활성화
    }

    stages {
        stage('Initialize') {
            steps {
                // 🌟🌟 Checkout보다 먼저 Git Global 설정을 등록하여 버그를 우회 🌟🌟
                sh 'git config --global --add safe.directory /var/jenkins_home/workspace/ci-cd-test-pipeline'
                sh 'git config --global --add safe.directory /var/jenkins_home/workspace/ci-cd-test-pipeline@tmp'
                
                // 이후 Git SCM Checkout 실행
                checkout scm
            }
        }

        stage('Install Docker Client') {
            steps {
                sh '''
                    apt-get update
                    apt-get install -y docker.io
                    # 💡 [필수 추가] docker.io 패키지 설치 후 'docker' 명령 사용 가능하도록 심볼릭 링크 생성 (데비안 계열 OS)
                    ln -s /usr/bin/docker.io /usr/local/bin/docker || true
                '''
            }
        }
        
        stage('Docker Build and Push') {
            steps {
                script {
                    // Jenkins Credentials ID를 사용하여 Docker Hub 로그인 정보를 가져옵니다.
                    withCredentials([usernamePassword(credentialsId: 'dktlfem', usernameVariable: 'USER', passwordVariable: 'PASS')]) {
                        
                        // 1. Docker Hub 로그인 (보안 구문 사용)
                        sh 'echo "$PASS" | docker login -u "$USER" --password-stdin' 
                
                        // 2. dos2unix 및 gradlew 준비 (줄 끝 문자 오류 해결)
                        sh 'apt-get install -y dos2unix' 
                        sh 'dos2unix ./gradlew'
                        sh 'chmod +x ./gradlew'
                
                        // 3. JAR 파일 생성
                        sh '/bin/bash ./gradlew clean build -x test --refresh-dependencies' 
                
                        // 4. Docker 이미지 빌드 및 푸시
                        sh "docker build --no-cache -t ${DOCKER_IMAGE}:${BUILD_NUMBER} ."
                        sh "docker push ${DOCKER_IMAGE}:${BUILD_NUMBER}"
                    }
                }
            }
        }
        
        stage('Deploy to AWS EC2') {
            steps {
                // AWS EC2 서버에 SSH 접속하여 배포 명령 실행 (EC2-DEPLOY-KEY 사용)
                withCredentials([sshUserPrivateKey(credentialsId: 'EC2-DEPLOY-KEY', keyFileVariable: 'KEY_FILE')]) {
                    sh """
                        # 💡 Groovy 변수에는 백슬래시를 사용하지 않습니다.
                        ssh -i ${KEY_FILE} -o StrictHostKeyChecking=no ${EC2_USER}@${EC2_HOST} '
                    
                            # 0. 디렉토리 이동
                            cd /home/${EC2_USER}/app/ &&

                            # 1. EC2에서 Docker Hub에 로그인 (배포에 필요)
                            docker login -u \$(cat ~/.docker_user) -p \$(cat ~/.docker_pass) &&

                            # 2. 현재 Active 상태의 서비스 포트 확인 (Nginx 설정 파일을 읽어 현재 Active 상태 파악)
                            CURRENT_PORT=\$(grep -oE "app_[a-z]+:([0-9]+);" nginx.conf | grep -oE "[0-9]+")
                    
                            # 3. 다음으로 배포할 서비스 (Next)의 포트 결정
                            if [ "\$CURRENT_PORT" = "8081" ]; then
                                NEXT_PORT="8082"
                                NEXT_SERVICE="app_green:8082"
                                CURRENT_SERVICE="app_blue:8081"
                                OLD_CONTAINER="app_blue"
                            else
                                NEXT_PORT="8081"
                                NEXT_SERVICE="app_blue:8081"
                                CURRENT_SERVICE="app_green:8082"
                                OLD_CONTAINER="app_green"
                            fi

                            echo "--- Current Active Service: \$CURRENT_SERVICE, Deploying to Next Service: \$NEXT_SERVICE ---"

                            # 4. Next 서비스 컨테이너 구동 (새 이미지 사용)
                            # --no-deps 옵션으로 Nginx 재시작 방지. Compose 파일에 Next 서비스만 포함
                            # --scale 옵션을 사용하여 Next 서비스만 강제로 1개 띄웁니다.
                            docker pull ${DOCKER_IMAGE}:${BUILD_NUMBER}
                            docker-compose up -d --no-deps --scale \${OLD_CONTAINER}=0 \${OLD_CONTAINER} &&
                            docker-compose up -d --no-deps \$(echo \${NEXT_SERVICE} | cut -d: -f1)

                            # 5. Health Check (새 버전이 정상적으로 뜰 때까지 대기 - 10초 예시)
                            sleep 10 
                            # 💡 실제 환경에서는 curl을 사용하여 Health Check 엔드포인트가 200 OK를 반환할 때까지 루프를 돌려야 합니다.
                            echo "--- Health Check passed on \$NEXT_PORT ---"
                    
                            # 6. Nginx 설정 파일의 Upstream 변경 (무중단 전환 로직)
                            sed -i "s/\${CURRENT_SERVICE}/\${NEXT_SERVICE}/g" nginx.conf
                    
                            # 7. Nginx 설정 Reload (무중단 트래픽 전환)
                            docker exec nginx_proxy nginx -s reload

                            # 8. 이전 Active 컨테이너 종료 및 정리 (구 버전 정리)
                            echo "--- Switching complete. Stopping old container: \${OLD_CONTAINER} ---"
                            docker-compose stop \${OLD_CONTAINER}
                            docker-compose rm -f \${OLD_CONTAINER}

                            echo "--- Deployment to \$NEXT_SERVICE complete! ---"
                            '
                        """  
                    }
                }
            } 
        } 
    post {
        always {
            echo 'Pipeline finished.'
        }
        failure {
            echo 'Pipeline failed! Please check the build logs.'
        }
    }
}
