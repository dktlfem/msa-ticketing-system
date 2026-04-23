pipeline {
    agent any

    parameters {
        choice(
            name: 'TARGET_MODULE',
            choices: ['user-app'],
            description: '빌드하고 GitOps repo의 이미지 태그를 갱신할 모듈'
        )
    }

    environment {
        // 권장: user-app도 포함해서 전부 같은 레포 규칙으로 통일
        DOCKER_IMAGE = 'dktlfem/ci-cd-test'

        // GitOps repo
        GITOPS_REPO_URL = 'https://github.com/dktlfem/homelab-gitops.git'
        GITOPS_BRANCH   = 'chore/jenkinsfile-update-test'

        // Jenkins Credentials
        // - dockerhub-creds: Docker Hub username/password
        // - github-pat: GitHub username + PAT or token
        DOCKER_CREDENTIALS_ID = 'dktlfem'
        GIT_CREDENTIALS_ID    = 'github-pat'
    }

    options {
        skipDefaultCheckout()
        timestamps()
    }

    stages {
        stage('Initialize') {
            steps {
                sh '''
                    git config --global --add safe.directory "$WORKSPACE"
                    git config --global --add safe.directory "$WORKSPACE@tmp"
                '''
                checkout scm
            }
        }

        stage('Install Tools') {
            steps {
                sh '''
                    apt-get update
                    apt-get install -y docker.io dos2unix git python3
                    ln -sf /usr/bin/docker /usr/local/bin/docker || true
                '''
            }
        }

        stage('Test') {
            steps {
                sh '''
                    dos2unix ./gradlew
                    chmod +x ./gradlew
                    ./gradlew :${TARGET_MODULE}:test
                '''
            }
            post {
                always {
                    junit allowEmptyResults: true,
                          testResults: "${params.TARGET_MODULE}/build/test-results/**/*.xml"
                }
            }
        }

        stage('Build and Push Image') {
            steps {
                script {
                    env.SHORT_SHA = sh(script: "git rev-parse --short HEAD", returnStdout: true).trim()
                    env.IMAGE_TAG = "${params.TARGET_MODULE}-${env.BUILD_NUMBER}-${env.SHORT_SHA}"
                }

                withCredentials([usernamePassword(
                    credentialsId: "${env.DOCKER_CREDENTIALS_ID}",
                    usernameVariable: 'DOCKER_USER',
                    passwordVariable: 'DOCKER_PASS'
                )]) {
                    sh '''
                        echo "$DOCKER_PASS" | docker login -u "$DOCKER_USER" --password-stdin

                        ./gradlew :${TARGET_MODULE}:clean :${TARGET_MODULE}:bootJar

                        JAR_PATH=$(ls ${TARGET_MODULE}/build/libs/*.jar | grep -v plain | head -n 1)

                        docker build --no-cache \
                          --build-arg JAR_PATH=${JAR_PATH} \
                          -t ${DOCKER_IMAGE}:${IMAGE_TAG} .

                        docker push ${DOCKER_IMAGE}:${IMAGE_TAG}
                    '''
                }
            }
        }

        stage('Checkout GitOps Repo') {
            steps {
                dir('gitops') {
                    withCredentials([usernamePassword(
                        credentialsId: "${env.GIT_CREDENTIALS_ID}",
                        usernameVariable: 'GIT_USER',
                        passwordVariable: 'GIT_TOKEN'
                    )]) {
                        sh '''
                            git init
                            git remote remove origin || true
                            git remote add origin https://${GIT_USER}:${GIT_TOKEN}@github.com/dktlfem/homelab-gitops.git
                            git fetch --depth=1 origin ${GITOPS_BRANCH}
                            git checkout -B ${GITOPS_BRANCH} origin/${GITOPS_BRANCH}

                            git config user.name "Jenkins"
                            git config user.email "jenkins@local"
                            git config --global --add safe.directory "$WORKSPACE/gitops"
                        '''
                    }
                }
            }
        }

        stage('Update GitOps Manifest') {
            steps {
                dir('gitops') {
                    script {
                        def manifestPathMap = [
                            'user-app'       : 'cluster-a/apps/user-app/deployment.yaml',
                            'waitingroom-app': 'cluster-a/apps/waitingroom-app/deployment.yaml',
                            'concert-app'    : 'cluster-a/apps/concert-app/deployment.yaml',
                            'booking-app'    : 'cluster-a/apps/booking-app/deployment.yaml',
                            'payment-app'    : 'cluster-a/apps/payment-app/deployment.yaml'
                        ]

                        def manifestPath = manifestPathMap[params.TARGET_MODULE]
                        if (!manifestPath) {
                            error("No GitOps manifest path configured for ${params.TARGET_MODULE}")
                        }

                        env.MANIFEST_PATH = manifestPath
                    }

                    sh '''
                        test -f "${MANIFEST_PATH}"

                        python3 - <<'PY'
from pathlib import Path
import os
import re

path = Path(os.environ["MANIFEST_PATH"])
image = f'{os.environ["DOCKER_IMAGE"]}:{os.environ["IMAGE_TAG"]}'
text = path.read_text(encoding='utf-8')

new_text, count = re.subn(
    r'(^\\s*image:\\s*).*$',
    rf'\\1{image}',
    text,
    flags=re.MULTILINE
)

if count == 0:
    raise SystemExit(f"image line not found in {path}")

path.write_text(new_text, encoding='utf-8')
print(f"Updated {path} -> {image}")
PY

                        git diff -- ${MANIFEST_PATH}
                    '''
                }
            }
        }

        stage('Commit and Push GitOps Repo') {
            steps {
                dir('gitops') {
                    sh '''
                        git add "${MANIFEST_PATH}"

                        if git diff --cached --quiet; then
                          echo "No manifest changes to commit."
                          exit 0
                        fi

                        git commit -m "Update ${TARGET_MODULE} image to ${IMAGE_TAG}"
                        git push origin ${GITOPS_BRANCH}
                    '''
                }
            }
        }
    }

    post {
        always {
            echo 'Pipeline finished.'
        }
        failure {
            echo 'Pipeline failed. Check logs.'
        }
    }
}