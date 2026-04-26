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
        DOCKER_IMAGE = 'dktlfem/ci-cd-test'

        // Argo CD가 실제로 바라보는 브랜치와 맞춰야 함
        GITOPS_REPO_URL = 'https://github.com/dktlfem/homelab-gitops.git'
        GITOPS_BRANCH   = 'chore/jenkinsfile-update-test'
        GITOPS_BRANCH   = 'main'

        DOCKER_CREDENTIALS_ID = 'dktlfem'
        GIT_CREDENTIALS_ID    = 'github-pat'

        K8S_NAMESPACE = 'default'
        K8S_APP_KUBECONFIG_CREDENTIALS_ID = 'k3s-app-kubeconfig'

        KUBECTL_VERSION = 'v1.34.1'
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
                sh(
                    script: '''
                        apt-get update
                        apt-get install -y docker.io dos2unix git python3 curl ca-certificates
                        ln -sf /usr/bin/docker /usr/local/bin/docker || true

                        curl -fsSL -o /usr/local/bin/kubectl \
                          https://dl.k8s.io/release/${KUBECTL_VERSION}/bin/linux/amd64/kubectl
                        chmod +x /usr/local/bin/kubectl

                        kubectl version --client
                    '''.stripIndent()
                )
            }
        }

        stage('Preflight Config Contract') {
            steps {
                script {
                    def requiredConfigMapKeys = [
                        'user-app': [
                            'SPRING_DATASOURCE_URL',
                            'SPRING_PROFILES_ACTIVE',
                            'SPRING_DATA_REDIS_URL'
                        ]
                    ]

                    def requiredSecretKeys = [
                        'user-app': [
                            'SPRING_DATASOURCE_USERNAME',
                            'SPRING_DATASOURCE_PASSWORD',
                            'REDIS_PASSWORD'
                        ]
                    ]

                    def configMapNameMap = [
                        'user-app': 'user-app-config'
                    ]

                    def secretNameMap = [
                        'user-app': 'user-app-secret'
                    ]

                    def deploymentNameMap = [
                        'user-app': 'user-app'
                    ]

                    def cmKeys = requiredConfigMapKeys[params.TARGET_MODULE] ?: []
                    def secretKeys = requiredSecretKeys[params.TARGET_MODULE] ?: []
                    def configMapName = configMapNameMap[params.TARGET_MODULE]
                    def secretName = secretNameMap[params.TARGET_MODULE]
                    def deploymentName = deploymentNameMap[params.TARGET_MODULE] ?: params.TARGET_MODULE

                    if (!configMapName || !secretName) {
                        error("No config contract defined for ${params.TARGET_MODULE}")
                    }

                    env.CONFIGMAP_NAME = configMapName
                    env.SECRET_NAME = secretName
                    env.DEPLOYMENT_NAME = deploymentName
                    env.REQUIRED_CONFIGMAP_KEYS = cmKeys.join('\n')
                    env.REQUIRED_SECRET_KEYS = secretKeys.join('\n')
                }

                withCredentials([file(
                    credentialsId: "${env.K8S_APP_KUBECONFIG_CREDENTIALS_ID}",
                    variable: 'KUBECONFIG_FILE'
                )]) {
                    sh(
                        script: '''
python3 - <<'PY'
import json
import os
import subprocess
import sys

namespace = os.environ['K8S_NAMESPACE']
kubeconfig = os.environ['KUBECONFIG_FILE']
configmap_name = os.environ['CONFIGMAP_NAME']
secret_name = os.environ['SECRET_NAME']

required_cm = [x for x in os.environ.get('REQUIRED_CONFIGMAP_KEYS', '').splitlines() if x.strip()]
required_secret = [x for x in os.environ.get('REQUIRED_SECRET_KEYS', '').splitlines() if x.strip()]

def get_keys(kind, name):
    cmd = [
        'kubectl', '--kubeconfig', kubeconfig,
        '-n', namespace, 'get', kind, name, '-o', 'json'
    ]
    raw = subprocess.check_output(cmd, text=True)
    doc = json.loads(raw)
    return set((doc.get('data') or {}).keys())

cm_keys = get_keys('configmap', configmap_name)
secret_keys = get_keys('secret', secret_name)

missing_cm = sorted(set(required_cm) - cm_keys)
missing_secret = sorted(set(required_secret) - secret_keys)

print(f'ConfigMap checked: {configmap_name}')
print(f'Secret checked: {secret_name}')
print(f'ConfigMap keys present: {sorted(cm_keys)}')
print(f'Secret keys present: {sorted(secret_keys)}')

if missing_cm or missing_secret:
    if missing_cm:
        print(f'Missing ConfigMap keys: {missing_cm}', file=sys.stderr)
    if missing_secret:
        print(f'Missing Secret keys: {missing_secret}', file=sys.stderr)
    sys.exit(1)

print('Preflight config contract passed.')
PY
                        '''.stripIndent()
                    )
                }
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
                            'user-app': 'cluster-a/apps/user-app/deployment.yaml'
                        ]

                        def manifestPath = manifestPathMap[params.TARGET_MODULE]
                        if (!manifestPath) {
                            error("No GitOps manifest path configured for ${params.TARGET_MODULE}")
                        }

                        env.MANIFEST_PATH = manifestPath
                    }

                    sh(
                        script: '''
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

                        git diff -- "${MANIFEST_PATH}"
                        '''.stripIndent()
                    )
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

        stage('Verify Rollout') {
            steps {
                withCredentials([file(
                    credentialsId: "${env.K8S_APP_KUBECONFIG_CREDENTIALS_ID}",
                    variable: 'KUBECONFIG_FILE'
                )]) {
                    sh '''
                        TARGET_IMAGE="${DOCKER_IMAGE}:${IMAGE_TAG}"

                        echo "Waiting for Argo CD to apply image: ${TARGET_IMAGE}"

                        for i in $(seq 1 60); do
                          CURRENT_IMAGE=$(kubectl --kubeconfig "$KUBECONFIG_FILE" -n "$K8S_NAMESPACE" \
                            get deploy "$DEPLOYMENT_NAME" -o jsonpath='{.spec.template.spec.containers[0].image}' || true)

                          echo "Current image: ${CURRENT_IMAGE}"

                          if [ "$CURRENT_IMAGE" = "$TARGET_IMAGE" ]; then
                            echo "Target image detected in deployment."
                            break
                          fi

                          if [ "$i" -eq 60 ]; then
                            echo "Timed out waiting for deployment image update."
                            exit 1
                          fi

                          sleep 5
                        done

                        kubectl --kubeconfig "$KUBECONFIG_FILE" -n "$K8S_NAMESPACE" \
                          rollout status deploy/"$DEPLOYMENT_NAME" --timeout=300s
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