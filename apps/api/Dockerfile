FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp package -DskipTests -Dspotless.skip=true

FROM eclipse-temurin:25-jre
RUN groupadd --system closetos && useradd --system --gid closetos closetos
WORKDIR /app
COPY --from=build --chown=closetos:closetos /build/target/closetos-api-*.jar app.jar
USER closetos
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
