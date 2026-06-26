FROM maven:3.9.6-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
COPY src src

# Build the project
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*-SNAPSHOT.jar app.jar

# Expose the HTTPS port
EXPOSE 8443
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
