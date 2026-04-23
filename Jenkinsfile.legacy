pipeline {
    agent any

    parameters {
        choice(name: 'TARGET_MODULE',
               choices: ['user-app', 'waitingroom-app', 'concert-app', 'booking-app', 'payment-app', 'scg-app'],
               description: '배포할 모듈을 선택하세요.')
    }
    
    // 환경 변수 설정
    // 1. 파라미터 블록을 삭제하고 environment에 MODULES 리스트를 정의
    environment {
        DOCKER_IMAGE = 'dktlfem/ci-cd-test' 
        EC2_USER = 'ubuntu'
        EC2_HOST = '3.107.233.84'
        
        // ADR: 민감 정보는 Jenkins Credentials Store에서 주입 — 코드에 하드코딩 금지
        SPRING_DATASOURCE_URL      = credentials('SPRING_DATASOURCE_URL')
        SPRING_DATASOURCE_USERNAME = credentials('SPRING_DATASOURCE_USERNAME')
        SPRING_DATASOURCE_PASSWORD = credentials('SPRING_DATASOURCE_PASSWORD')
        SPRING_DATA_REDIS_HOST     = credentials('SPRING_DATA_REDIS_HOST')
        SPRING_DATA_REDIS_PORT     = credentials('SPRING_DATA_REDIS_PORT')
        REDIS_PASSWORD             = credentials('REDIS_PASSWORD')
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
                    apt-get install -y docker.io dos2unix
                    ln -s /usr/bin/docker.io /usr/local/bin/docker || true
                '''
            }
        }
        
        // 품질 게이트: 선택한 모듈의 단위·통합 테스트 실행
        // ADR: -x test 제거 — 테스트 통과 없이 Docker 이미지를 생성하지 않는다
        stage('Test') {
            steps {
                sh 'dos2unix ./gradlew'
                sh 'chmod +x ./gradlew'
                sh "./gradlew :${params.TARGET_MODULE}:test"
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: "${params.TARGET_MODULE}/build/test-results/**/*.xml"
                }
            }
        }

        // 선택한 모듈만 bootJar 생성 (테스트는 위 stage에서 이미 실행 완료)
        stage('Docker Build and Push') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'dktlfem', usernameVariable: 'USER', passwordVariable: 'PASS')]) {
                        
                        // 1. Docker Hub 로그인 (보안 구문 사용)
                        sh 'echo "$PASS" | docker login -u "$USER" --password-stdin' 
                
                        // 2. 선택한 모듈만 bootJar 생성 (테스트는 Test stage에서 완료)
                        sh "./gradlew :${params.TARGET_MODULE}:clean :${params.TARGET_MODULE}:bootJar" 

                        def jarPath = sh(script: "ls ${params.TARGET_MODULE}/build/libs/*.jar | grep -v plain", returnStdout: true).trim()
                        sh "docker build --no-cache --build-arg JAR_PATH=${jarPath} -t ${env.DOCKER_IMAGE}:${params.TARGET_MODULE}-${env.BUILD_NUMBER} ."
                        sh "docker push ${env.DOCKER_IMAGE}:${params.TARGET_MODULE}-${env.BUILD_NUMBER}"
                        
                    }
                }
            }
        }
        
        stage('Deploy to AWS EC2') {
            // ADR: scg-app은 홈 스테이징 서버(192.168.124.100)에 배포하므로 EC2 배포 단계에서 제외
            when {
                expression { params.TARGET_MODULE != 'scg-app' }
            }
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

        stage('Deploy scg-app to Staging') {
            // ADR: scg-app은 AWS EC2가 아닌 홈 스테이징 서버(192.168.124.100)에 배포
            // - docker-compose.yml의 image명(devops_lab-scg:latest)을 유지하기 위해
            //   Docker Hub에서 pull 후 로컬 태그로 재태그하여 compose 재시작
            when {
                expression { params.TARGET_MODULE == 'scg-app' }
            }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'STAGING-DEPLOY-KEY', keyFileVariable: 'STAGING_KEY')]) {
                    sh("""\
                        ssh -i \${STAGING_KEY} -o StrictHostKeyChecking=no -p 2222 dktlfem@192.168.124.100 'bash -s' <<'EOF'
                        set -e

                        cat ~/.docker_pass | docker login -u "\$(cat ~/.docker_user)" --password-stdin

                        # Jenkins가 push한 이미지를 pull → devops_lab-scg:latest로 재태그
                        docker pull ${env.DOCKER_IMAGE}:scg-app-${env.BUILD_NUMBER}
                        docker tag ${env.DOCKER_IMAGE}:scg-app-${env.BUILD_NUMBER} devops_lab-scg:latest

                        # docker-compose.yml이 있는 devops_lab 디렉토리에서 재시작
                        cd /home/dktlfem/devops_lab
                        docker compose up -d --no-deps scg

                        # 30초 대기 후 컨테이너 기동 확인
                        sleep 30
                        if ! docker ps --format '{{.Names}}' | grep -qx "scg"; then
                            echo "ERROR: scg container is not running"
                            docker logs --tail 200 scg || true
                            exit 1
                        fi

                        echo "--- scg-app Staging Deploy Success: Build ${env.BUILD_NUMBER} ---"
                        EOF
                    """.stripIndent())
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
