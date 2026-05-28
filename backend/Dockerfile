# Multi-stage build for OnWork backend (Spring Boot 3.3 / Java 21)
# Render/Railway/Fly.io 자동 감지. 빌드 캐시 최적화 위해 deps 먼저, src 나중.

# ---- 1) Build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies || true
COPY src ./src
RUN ./gradlew --no-daemon bootJar -x test

# ---- 2) Runtime ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
# PORT는 호스트(Render/Railway)가 주입 — application.yml에서 ${PORT:8080} 으로 매핑
EXPOSE 8080
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
