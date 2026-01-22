# 빌드 단계 없이 바로 실행 환경만 정의함.
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# 빌드 시점에 어떤 JAR를 가져올지 결정하는 인자
ARG JAR_PATH

# 빌드 스테이지에서 생성된 app.jar만 복사 -> 단일 모듈
# COPY --from=builder /app/app.jar app.jar -> 단일 모듈

# 맥북에서 scp로 보낸 최신 JAR 파일만 컨테이너 내부로 복사함.
COPY ${JAR_PATH} app.jar

# 기본 포트 노출
EXPOSE 8080

# 컨테이너 실행 명령 정의
ENTRYPOINT ["java", "-jar", "app.jar"]