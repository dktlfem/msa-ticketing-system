pipeline {
    agent any 
    
    // 환경 변수 설정 (사용자님의 Docker Hub ID와 EC2 정보로 변경해야 합니다.)
    environment {
        // 1. Docker Hub ID와 이미지 이름으로 변경하세요.
        DOCKER_IMAGE = 'dktlfem/ci-cd-test' 
        EC2_USER = 'ubuntu'
        // 2. AWS EC2 퍼블릭 IP 또는 DNS 주소로 변경하세요.
        EC2_HOST = '15.134.88.109'
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
                
                        # 1. EC2에서 Docker Hub에 로그인
                        docker login -u \$(cat /home/${EC2_USER}/.docker_user) -p \$(cat /home/${EC2_USER}/.docker_pass) &&
                
                        # 2. 애플리케이션 디렉토리로 이동 및 정리
                        cd /home/${EC2_USER}/app/ &&
                        docker-compose down --remove-orphans &&
                
                        # 3. 최신 이미지 다운로드 및 서비스 재시작
                        docker pull ${DOCKER_IMAGE}:${BUILD_NUMBER} &&
                        docker-compose up -d --force-recreate
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
