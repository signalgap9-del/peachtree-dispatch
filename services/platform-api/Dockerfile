FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src src
RUN mvn -q package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/platform-api-0.1.0.jar app.jar
RUN useradd --system --uid 10001 --create-home atmospath \
    && chown -R atmospath /app
USER atmospath
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
