FROM maven:3.9.6-eclipse-temurin-17 as builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /app/target/classes /app/classes
COPY --from=builder /root/.m2 /root/.m2

ENTRYPOINT ["java", "-cp", "/app/classes", "Benchmark"]
