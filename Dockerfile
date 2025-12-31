# 1단계: 빌드 환경 (Build Stage)
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /app

# Gradle Wrapper, 설정 파일, 빌드 스크립트 복사
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle .

# 소스 코드 및 모든 리소스 파일 복사 (프로파일 파일 포함)
# 🌟 src 디렉토리 전체를 복사하여 모든 application-{profile}.properties 파일을 포함시킵니다.
COPY src src

# 최종 빌드 명령 (테스트 제외)
RUN ./gradlew clean build -x test

# 🌟 빌드 결과 JAR 파일명을 확인합니다. (예: build.gradle의 rootProject.name + version)
# 🌟 아래 변수는 예시입니다. 실제 JAR 파일명을 확인하여 수정해야 합니다.
ARG JAR_FILE_NAME=your-app-name-0.0.1-SNAPSHOT.jar 


# 2단계: 실행 환경 (Run Stage)
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 빌드 스테이지에서 생성된 실행 가능한 JAR 파일을 정확한 이름으로 복사
# 🌟 와일드카드 (*) 대신 정확한 JAR 파일 이름을 사용합니다.
# 기존 작성 내용 : COPY --from=builder /app/build/libs/${JAR_FILE_NAME} app.jar (${JAR_FILE_NAME} 변수명 사용 x)
COPY --from=builder /app/build/libs/app.jar app.jar

# 애플리케이션 실행에 필요한 포트 노출 (Docker Compose에서 이미 8081/8082로 지정됨)
EXPOSE 8081 

# 컨테이너 실행 명령 정의
ENTRYPOINT ["java", "-jar", "app.jar"]