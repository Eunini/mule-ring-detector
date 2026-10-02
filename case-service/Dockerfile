# syntax=docker/dockerfile:1

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q -DskipTests package && cp target/case-service.jar /tmp/app.jar

FROM eclipse-temurin:21-jre
RUN groupadd --system mrd && useradd --system --gid mrd --uid 10001 --home-dir /app mrd
WORKDIR /app
COPY --from=build /tmp/app.jar /app/app.jar
USER mrd
EXPOSE 8085
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
