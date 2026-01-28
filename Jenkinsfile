pipeline {
    agent any
    
    // 환경 변수 설정
    // 1. 파라미터 블록을 삭제하고 environment에 MODULES 리스트를 정의
    environment {
        // 1. Docker Hub ID와 이미지 이름으로 변경하세요.
        DOCKER_IMAGE = 'dktlfem/ci-cd-test' 
        EC2_USER = 'ubuntu'
        // 2. AWS EC2 퍼블릭 IP 또는 DNS 주소로 변경하세요.
        EC2_HOST = '15.134.88.109'

        // 배포할 5개 마이크로서비스 리스트
        MODULES = 'user-app,waitingroom-app,concert-app,booking-app,payment-app'

        REDIS_PASSWORD = 'qkqhqhqkq1w2R$$'
        SPRING_DATASOURCE_URL = 'jdbc:mysql://cd-mysql-db.cluuo6ag6qpg.ap-southeast-2.rds.amazonaws.com:3306/dev_db?serverTimezone=Asia/Seoul&useSSL=false&allowPublicKeyRetrieval=true'
        SPRING_DATASOURCE_USERNAME = 'admin'
        SPRING_DATASOURCE_PASSWORD = 'qkqhqhqkq1w2o(p)'
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
        
        stage('Docker Build and Push All') {
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

                        // 4. MODULES 리스트를 순회하며 이미지를 각각 생성하고 푸쉬함.
                        def moduleList = env.MODULES.split(',')
                        for (module in moduleList) {
                            echo "--- Processing: ${module} ---"
                            def jarPath = sh(script: "ls ${module}/build/libs/*.jar | grep -v plain", returnStdout: true).trim()
                            
                            // 이미지 태그에 모듈 이름을 포함시켜 구분합니다. (예: ci-cd-test:user-app-341)
                            sh "docker build --no-cache --build-arg JAR_PATH=${jarPath} -t ${env.DOCKER_IMAGE}:${module}-${env.BUILD_NUMBER} ."
                            sh "docker push ${env.DOCKER_IMAGE}:${module}-${env.BUILD_NUMBER}"
                        }
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
                                
                                # 서버에 저장된 인증 정보로 로그인
                                docker login -u \$(cat ~/.docker_user) -p \$(cat ~/.docker_pass)
                                
                                # 5개 이미지 최신화 및 컨테이너 실행
                                docker-compose pull
                                docker-compose up -d --remove-orphans
                                
                                # Nginx 프록시 설정 반영
                                docker exec nginx_proxy nginx -s reload
                                
                                echo "--- MSA Cluster Deployment Complete (All 5 Services Up) ---"
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
