FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y --no-install-recommends maven && \
    mvn package -DskipTests -q && \
    apt-get purge -y maven && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
RUN useradd --system --uid 10001 --create-home freightsvc \
    && chown -R freightsvc /app
USER freightsvc
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
