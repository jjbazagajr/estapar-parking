# syntax=docker/dockerfile:1.6

FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew
COPY src src
RUN ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN groupadd --system spring && useradd --system --gid spring --no-create-home spring
COPY --from=build --chown=spring:spring /workspace/build/libs/*.jar app.jar
USER spring
EXPOSE 3003
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
