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
        EC2_HOST = '15.134.88.109'
        REDIS_PASSWORD = 'qkqhqhqkq1w2R$$'
        SPRING_DATASOURCE_URL = 'jdbc:mysql://cd-mysql-db.cluuo6ag6qpg.ap-southeast-2.rds.amazonaws.com:3306/dev_db?serverTimezone=Asia/Seoul&useSSL=false&allowPublicKeyRetrieval=true'
        SPRING_DATASOURCE_USERNAME = 'admin'
        SPRING_DATASOURCE_PASSWORD = 'qkqhqhqkq1w2o(p)'
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
                // 1. 열쇠 꺼내기 (sshUserPrivateKey 사용)
                withCredentials([sshUserPrivateKey(credentialsId: 'EC2-DEPLOY-KEY', keyFileVariable: 'KEY_FILE')]) {
                    script {
                        // 1. .env 파일 생성 로직 (가장 안전한 writeFile 방식)
                        def envContent = """BUILD_NUMBER=${env.BUILD_NUMBER}
SPRING_DATASOURCE_URL='${env.SPRING_DATASOURCE_URL}'
SPRING_DATASOURCE_USERNAME='${env.SPRING_DATASOURCE_USERNAME}'
SPRING_DATASOURCE_PASSWORD='${env.SPRING_DATASOURCE_PASSWORD}'
SPRING_PROFILES_ACTIVE=dev
REDIS_PASSWORD='${env.REDIS_PASSWORD}'"""
                        writeFile file: '.env', text: envContent
                        
                        // 2. 파일 전송 (변수명을 정확히 ${}로 감싸 오타 방지)
                        sh "scp -i ${KEY_FILE} -o StrictHostKeyChecking=no .env ubuntu@${env.EC2_HOST}:/home/ubuntu/app/.env"

                        // 3. 원격 실행 (Heredoc 방식을 사용하여 SSH 내부의 $ 기호를 보호함)
                        sh """
                            ssh -i ${KEY_FILE} -o StrictHostKeyChecking=no ubuntu@${env.EC2_HOST} 'bash -s' << 'EOF'
                                cd /home/ubuntu/app/ || exit
                                MODULE="${params.TARGET_MODULE}"
                                MODULE_SHORT=\${MODULE%-app}

                                CURRENT_PORT=\$(grep -A 10 "upstream \${MODULE_SHORT}_servers" nginx.conf | grep -oE "[0-9]+" | head -n 1)
                                
                                if [ "\$MODULE_SHORT" = "user" ]; then B=8081; G=8082;
                                elif [ "\$MODULE_SHORT" = "waitingroom" ]; then B=8085; G=8086;
                                elif [ "\$MODULE_SHORT" = "concert" ]; then B=8087; G=8088;
                                elif [ "\$MODULE_SHORT" = "booking" ]; then B=8089; G=8090;
                                else B=8091; G=8092; fi

                                if [ "\$CURRENT_PORT" = "\$B" ]; then 
                                    NEXT_SERVICE="\${MODULE_SHORT}-green:\$G"; OLD_SLOT="\${MODULE_SHORT}-blue";
                                else 
                                    NEXT_SERVICE="\${MODULE_SHORT}-blue:\$B"; OLD_SLOT="\${MODULE_SHORT}-green";
                                fi

                                docker login -u \$(cat ~/.docker_user) -p \$(cat ~/.docker_pass)
                                docker-compose pull \${NEXT_SERVICE%:*}
                                docker-compose up -d --no-deps \${NEXT_SERVICE%:*}
                                
                                echo "--- Waiting for Spring Boot Startup (20s) ---"
                                sleep 20

                                sed -i "/upstream \${MODULE_SHORT}_servers/,/}/ s/server .*:.*;/server \$NEXT_SERVICE;/" nginx.conf
                                docker exec nginx_proxy nginx -s reload
                                
                                # 이전 컨테이너 정리 (리소스 확보)
                                docker-compose stop \$OLD_SLOT
                                echo "--- MSA Cluster Deploy Success: \$NEXT_SERVICE ---"
EOF
                        """
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
