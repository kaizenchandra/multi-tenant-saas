FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src src
RUN mvn -q package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/target/multi-tenant-saas-1.0.0.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:+UseG1GC","-XX:InitialRAMPercentage=30","-XX:MaxRAMPercentage=60","-XX:MaxGCPauseMillis=50","-jar","app.jar"]
