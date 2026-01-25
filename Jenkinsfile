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

                        // 🌟 [MSA 포인트] 빌드할 대상 모듈과 JAR 경로 정의
                        // 현재는 user-app을 배포하지만, 나중에 다른 모듈로 쉽게 바꿀 수 있다.
                        def moduleName = "user-app"
                        def jarPath = "${moduleName}/build/libs/${moduleName}-0.0.1-SNAPSHOT.jar"
                
                        // 🌟 [핵심] --build-arg를 사용하여 Dockerfile의 JAR_PATH에 경로 주입
                        echo "--- Building Docker Image for ${moduleName} ---"
                        sh "docker build --no-cache --build-arg JAR_PATH=${jarPath} -t ${DOCKER_IMAGE}:${BUILD_NUMBER} ."

                        // 4. Docker 이미지 빌드 및 푸시
                        sh "docker build --no-cache -t ${DOCKER_IMAGE}:${BUILD_NUMBER} ."
                        sh "docker push ${DOCKER_IMAGE}:${BUILD_NUMBER}"
                    }
                }
            }
        }
        
        stage('Deploy to AWS EC2') {
            steps {
                // 1. 열쇠 꺼내기 (sshUserPrivateKey 사용)
                withCredentials([sshUserPrivateKey(credentialsId: 'EC2-DEPLOY-KEY', keyFileVariable: 'KEY_FILE')]) {
                    // 2. 쉘 스크립트 실행 (따옴표 3개 주의!)
                    sh """
                        ssh -i $KEY_FILE -o StrictHostKeyChecking=no ubuntu@15.134.88.109 '
                            # ---------------------------------------------------------
                            # [주의] Jenkins 변수는 그냥 \${...}, 리눅스 변수는 \\\$... 로 씁니다.
                            # ---------------------------------------------------------
                    
                            # Jenkins가 빌드 번호를 주입해줌
                            export BUILD_NUMBER=${BUILD_NUMBER}
                            cd /home/ubuntu/app/ &&

                            # 0. Nginx 안전장치 (없으면 켬)
                            docker ps | grep nginx_proxy || docker-compose up -d nginx_proxy

                            # 1. Docker Hub 로그인 (리눅스 변수니까 앞에 \\\$ 붙임)
                            docker login -u \$(cat ~/.docker_user) -p \$(cat ~/.docker_pass) &&

                            # 2. 현재 실행 중인 포트 확인 (Blue/Green 판별)
                            CURRENT_PORT=\$(grep -oE "app_[a-z]+:([0-9]+);" nginx.conf | grep -oE "[0-9]+")

                            # 3. 다음 배포할 포트/서비스 결정
                            if [ "\$CURRENT_PORT" = "8081" ]; then
                                NEXT_PORT="8082"
                                NEXT_SERVICE="app_green:8082"
                                OLD_CONTAINER="app_blue"
                            else
                                NEXT_PORT="8081"
                                NEXT_SERVICE="app_blue:8081"
                                OLD_CONTAINER="app_green"
                            fi

                            echo "--- Current: \$CURRENT_PORT, Deploying to: \$NEXT_SERVICE ---"

                            # 4. 새 버전(Next) 컨테이너 실행
                            docker pull dktlfem/ci-cd-test:${BUILD_NUMBER}
                            docker-compose up -d --no-deps nginx_proxy \$(echo \$NEXT_SERVICE | cut -d: -f1)

                            # 5. Health Check (새 서버가 뜰 때까지 대기)
                            echo "--- Waiting for Health Check... ---"
                            sleep 10

                            # 6. Nginx 설정 변경 (핵심: 문법 안 깨지게 조심!)
                            sed -i "s/server app_.*;/server \$NEXT_SERVICE;/g" nginx.conf

                            # 7. Nginx Reload (트래픽 전환)
                            docker exec nginx_proxy nginx -s reload
                            echo "--- Traffic Switched to \$NEXT_SERVICE ---"

                            # 8. 구 버전(Old) 컨테이너 정리
                            docker-compose stop \$OLD_CONTAINER
                            docker-compose rm -f \$OLD_CONTAINER
                            echo "--- Cleanup Complete: \$OLD_CONTAINER ---"
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
