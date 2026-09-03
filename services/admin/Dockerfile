FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && useradd -r -d /app -s /usr/sbin/nologin appuser \
    && mkdir /app && chown -R appuser:appuser /app
COPY --from=build /src/target/*.jar /app/app.jar
EXPOSE 8085
USER appuser
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
