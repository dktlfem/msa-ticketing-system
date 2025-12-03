# 1단계: 빌드 환경 (Build Stage) - 필요한 모든 빌드 도구와 소스 코드를 포함한다.
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Gradle Wrapper 및 설정 파일 복사
COPY gradlew .
COPY gradle gradle

# build.gradle, settings.gradle 파일 복사 (의존성 다운로드를 위해)
COPY build.gradle settings.gradle .

# 소스 코드를 복사하기 전에 의존성만 미리 다운로드 (레이어 캐싱 최적화)
# 'clean build' 대신 'dependencies' 작업을 사용하여 캐싱 효율을 높일 수 있다.
RUN ./gradlew dependencies

# 💡 [필수 추가] application-prod.properties 파일을 리소스 경로에 명시적으로 복사
COPY src/main/resources/application-prod.properties src/main/resources/

# 소스 코드 복사 및 최종 빌드
COPY src src
RUN ./gradlew clean build -x test

# 2단계: 실행 환경 (Run Stage) - 애플리케이션 실행에 필요한 최소한의 환경만 포함한다.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 빌드 스테이지에서 생성된 실행 가능한 JAR 파일을 복사한다.
# build.gradle 설정에 따라 JAR 파일 경로를 정확히 확인하고 수정해야 한다.
# (일반적으로 build/libs/YOUR-APP-NAME.jar 경로에 생성된다.)
COPY --from=builder /app/build/libs/*.jar app.jar

# 애플리케이션 실행에 필요한 포트 노출
EXPOSE 8083

# 컨테이너 실행 명령 정의
ENTRYPOINT ["java", "-jar", "app.jar"]
