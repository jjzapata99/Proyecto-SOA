FROM maven:3.9.6-eclipse-temurin-17 as builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package dependency:copy-dependencies -DoutputDirectory=target/lib -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/classes /app/classes
COPY --from=builder /app/target/lib /app/lib

ENTRYPOINT ["java", "-cp", "/app/classes:/app/lib/*", "Benchmark"]
