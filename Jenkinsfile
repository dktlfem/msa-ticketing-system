pipeline {
    agent any

    parameters {
        choice(name: 'TARGET_MODULE', 
               choices: ['user-app', 'waitingroom-app', 'concert-app', 'booking-app', 'payment-app'], 
               description: '배포할 모듈을 선택하세요.')
    }
    
    // 환경 변수 설정
    // 1. 파라미터 블록을 삭제하고 environment에 MODULES 리스트를 정의
    environment {
        DOCKER_IMAGE = 'dktlfem/ci-cd-test' 
        EC2_USER = 'ubuntu'
        EC2_HOST = '3.107.233.84'
        
        SPRING_DATASOURCE_URL = 'jdbc:mysql://dev-db.cluuo6ag6qpg.ap-southeast-2.rds.amazonaws.com:3306/dev_db?serverTimezone=Asia/Seoul&useSSL=false&allowPublicKeyRetrieval=true'
        SPRING_DATASOURCE_USERNAME = 'admin'
        SPRING_DATASOURCE_PASSWORD = 'qkqhqhqkq1w2o(p)'

        SPRING_DATA_REDIS_HOST = '192.168.124.101'
        SPRING_DATA_REDIS_PORT = '6379'
        REDIS_PASSWORD = 'qkqhqhqkq1w2R$$'
    }

    options {
        // 빌드가 시작될 때마다 워크스페이스를 완전히 정리하도록 설정 (필수)
        skipDefaultCheckout() // 기본 checkout 로직 비활성화
    }

    // Git 초기 설정단계
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

        // 도커 클라이언트 설치 단계
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
        
        // 선택한 모듈만 정밀 빌드
        stage('Docker Build and Push') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'dktlfem', usernameVariable: 'USER', passwordVariable: 'PASS')]) {
                        
                        // 1. Docker Hub 로그인 (보안 구문 사용)
                        sh 'echo "$PASS" | docker login -u "$USER" --password-stdin' 
                
                        // 2. dos2unix 및 gradlew 준비 (줄 끝 문자 오류 해결)
                        sh 'apt-get install -y dos2unix' 
                        sh 'dos2unix ./gradlew'
                        sh 'chmod +x ./gradlew'
                
                        // 3. 선택한 모듈만 JAR 파일 생성하여 정밀 빌드 (의존성 포함)
                        //sh '/bin/bash ./gradlew clean build -x test --refresh-dependencies'
                        sh "./gradlew :${params.TARGET_MODULE}:clean :${params.TARGET_MODULE}:build -x test" 

                        def jarPath = sh(script: "ls ${params.TARGET_MODULE}/build/libs/*.jar | grep -v plain", returnStdout: true).trim()
                        sh "docker build --no-cache --build-arg JAR_PATH=${jarPath} -t ${env.DOCKER_IMAGE}:${params.TARGET_MODULE}-${env.BUILD_NUMBER} ."
                        sh "docker push ${env.DOCKER_IMAGE}:${params.TARGET_MODULE}-${env.BUILD_NUMBER}"
                        
                    }
                }
            }
        }
        
        stage('Deploy to AWS EC2') {
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'EC2-DEPLOY-KEY', keyFileVariable: 'KEY_FILE')]) {
                    script {
                        def envContent = """BUILD_NUMBER=${env.BUILD_NUMBER}
SPRING_PROFILES_ACTIVE=dev
SPRING_DATASOURCE_URL=${env.SPRING_DATASOURCE_URL}
SPRING_DATASOURCE_USERNAME=${env.SPRING_DATASOURCE_USERNAME}
SPRING_DATASOURCE_PASSWORD=${env.SPRING_DATASOURCE_PASSWORD}
SPRING_DATA_REDIS_HOST=${env.SPRING_DATA_REDIS_HOST}
SPRING_DATA_REDIS_PORT=${env.SPRING_DATA_REDIS_PORT}
SPRING_DATA_REDIS_PASSWORD=${env.REDIS_PASSWORD}
SPRING_REDIS_PASSWORD=${env.REDIS_PASSWORD}
"""
                        writeFile file: '.env', text: envContent

                        sh """
                            scp -i ${KEY_FILE} -o StrictHostKeyChecking=no .env ubuntu@${env.EC2_HOST}:/home/ubuntu/app/.env
                            scp -i ${KEY_FILE} -o StrictHostKeyChecking=no docker-compose.yml ubuntu@${env.EC2_HOST}:/home/ubuntu/app/docker-compose.yml
                            scp -i ${KEY_FILE} -o StrictHostKeyChecking=no nginx.conf ubuntu@${env.EC2_HOST}:/home/ubuntu/app/nginx.conf
                        """

                        sh("""\
                            ssh -i ${KEY_FILE} -o StrictHostKeyChecking=no ubuntu@${env.EC2_HOST} 'bash -s' <<'EOF'
                            set -e
                            cd /home/ubuntu/app || exit 1

                            if docker compose version >/dev/null 2>&1; then
                                DC="docker compose"
                            else
                                DC="docker-compose"
                            fi

                            MODULE="${params.TARGET_MODULE}"
                            MODULE_SHORT=\${MODULE%-app}

                            if [ "\$MODULE_SHORT" = "user" ]; then
                                B=8081; G=8082
                            elif [ "\$MODULE_SHORT" = "waitingroom" ]; then
                                B=8085; G=8086
                            elif [ "\$MODULE_SHORT" = "concert" ]; then
                                B=8087; G=8088
                            elif [ "\$MODULE_SHORT" = "booking" ]; then
                                B=8089; G=8090
                            elif [ "\$MODULE_SHORT" = "payment" ]; then
                                B=8091; G=8092
                            else
                                echo "Unsupported module: \$MODULE"
                                exit 1
                            fi

                            if grep -A 10 "upstream \${MODULE_SHORT}_servers" nginx.conf | grep -q "server .*:\${B};"; then
                                NEXT_SERVICE="\${MODULE_SHORT}-green"
                                NEXT_PORT="\${G}"
                                OLD_SLOT="\${MODULE_SHORT}-blue"
                            else
                                NEXT_SERVICE="\${MODULE_SHORT}-blue"
                                NEXT_PORT="\${B}"
                                OLD_SLOT="\${MODULE_SHORT}-green"
                            fi

                            cat ~/.docker_pass | docker login -u "\$(cat ~/.docker_user)" --password-stdin

                            \$DC pull "\$NEXT_SERVICE"
                            \$DC up -d --no-deps "\$NEXT_SERVICE"

                            echo "--- Waiting for \$NEXT_SERVICE startup ---"
                            sleep 20

                            if ! docker ps --format '{{.Names}}' | grep -qx "\$NEXT_SERVICE"; then
                                echo "ERROR: \$NEXT_SERVICE is not running"
                                docker logs --tail 200 "\$NEXT_SERVICE" || true
                                exit 1
                            fi

                    sed -i "/upstream \${MODULE_SHORT}_servers/,/}/ s/server .*:.*;/server \$NEXT_SERVICE:\$NEXT_PORT;/" nginx.conf

                    \$DC up -d nginx_proxy
                    docker exec nginx_proxy nginx -t
                    docker exec nginx_proxy nginx -s reload

                    \$DC stop "\$OLD_SLOT" || true

                    echo "--- MSA Cluster Deploy Success: \$NEXT_SERVICE:\$NEXT_PORT ---"
                    EOF
                    """.stripIndent())
                    }
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
