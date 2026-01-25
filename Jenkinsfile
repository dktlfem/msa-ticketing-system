pipeline {
    agent any

    // 1. 빌드 시 선택할 수 있는 파라미터 정의
    parameters {
        choice(name: 'TARGET_MODULE', 
               choices: ['user-app', 'waitingroom-app', 'concert-app', 'booking-app', 'payment-app'], 
               description: '빌드 및 배포할 마이크로서비스 모듈을 선택하세요.')
    }
    
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
                
                        // 3. 전체 모듈 JAR 파일 생성 (의존성 포함)
                        sh '/bin/bash ./gradlew clean build -x test --refresh-dependencies' 

                        // 동적 경로 탐색 로직
                        // [MSA 동적 할당] 선택한 파라미터(TARGET_MODULE)를 변수에 주입
                        def moduleName = params.TARGET_MODULE
                        def jarPath = sh(script: "ls ${moduleName}/build/libs/*.jar | grep -v plain", returnStdout: true).trim()

                        echo "--- Detected JAR Path: ${jarPath} ---"
                        
                        // [디버깅] 파일 정보 출력
                        sh "ls -l ${jarPath}"
                
                        // 🌟 [핵심] --build-arg를 사용하여 Dockerfile의 JAR_PATH에 동적 경로 주입
                        // --build-arg를 사용하여 Dockerfile의 JAR_PATH에 동적 경로를 전달합니다.
                        echo "--- Building Docker Image for ${moduleName} ---"
                        sh "docker build --no-cache --build-arg JAR_PATH=${jarPath} -t ${DOCKER_IMAGE}:${BUILD_NUMBER} ."
                
                        // 4. 생성된 이미지 푸시
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

                            # 1. 작업 디렉토리 이동 (가장 먼저 수행)
                            cd /home/ubuntu/app/ || exit

                            # 2. .env 파일 동적 생성 (Jenkins 변수를 활용하여 매번 새로 작성)
                            export BUILD_NUMBER=${BUILD_NUMBER} 
                            export SPRING_DATASOURCE_URL="${SPRING_DATASOURCE_URL}"
                            export SPRING_DATASOURCE_USERNAME="${SPRING_DATASOURCE_USERNAME}"
                            export SPRING_DATASOURCE_PASSWORD="${SPRING_DATASOURCE_PASSWORD}"
                            export SPRING_PROFILES_ACTIVE=dev
                            
                            # 3. Nginx 안전장치 (없으면 켬)
                            docker ps | grep nginx_proxy || docker-compose up -d nginx_proxy

                            # 4. Docker Hub 로그인 (리눅스 변수니까 앞에 \\\$ 붙임)
                            docker login -u \$(cat ~/.docker_user) -p \$(cat ~/.docker_pass) &&

                            # 5. 현재 실행 중인 포트 확인 (Blue/Green 판별)
                            CURRENT_PORT=\$(grep -oE "app_[a-z]+:([0-9]+);" nginx.conf | grep -oE "[0-9]+")

                            # 6. 다음 배포할 서비스 결정
                            if [ "\$CURRENT_PORT" = "8081" ]; then
                                NEXT_PORT="8082"
                                NEXT_SERVICE="app_green:8082"
                                OLD_CONTAINER="app_blue"
                            else
                                NEXT_PORT="8081"
                                NEXT_SERVICE="app_blue:8081"
                                OLD_CONTAINER="app_green"
                            fi

                            echo "--- Deploying to: \$NEXT_SERVICE with .env variables ---"

                            # 7. 새 버전 이미지 풀 및 특정 서비스만 실행
                            # .env 파일 덕분에 별도의 -e 옵션 없이도 DB 정보가 주입됩니다.
                            docker pull ${DOCKER_IMAGE}:${BUILD_NUMBER}
                            docker-compose up -d --no-deps \$(echo \$NEXT_SERVICE | cut -d: -f1)

                            # docker-compose가 쉘의 환경변수를 읽도록 실행
                            # docker-compose up -d --no-deps nginx_proxy \$(echo \$NEXT_SERVICE | cut -d: -f1)

                            # 8. Health Check 대기 (스프링 부트가 완전히 뜰 때까지)
                            echo "--- Waiting for Spring Boot Startup (15s)... ---"
                            sleep 15

                            # 9. Nginx 설정 변경 및 리로드
                            sed -i "s/server app_.*;/server \$NEXT_SERVICE;/g" nginx.conf
                            docker exec nginx_proxy nginx -s reload
                            echo "--- Traffic Switched to \$NEXT_SERVICE ---"

                            # 10. 구 버전(Old) 컨테이너 정리
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
