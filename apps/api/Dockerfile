FROM eclipse-temurin:25-jdk AS build
WORKDIR /build
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp package -DskipTests -Dspotless.skip=true

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system closetos && useradd --system --gid closetos closetos \
    && chown closetos:closetos /tmp && chmod 1777 /tmp
WORKDIR /app
RUN curl --fail --silent --show-error --location --max-time 60 \
    https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem \
    -o rds-global-bundle.pem
COPY --from=build --chown=closetos:closetos /build/target/closetos-api-*.jar app.jar
USER closetos
VOLUME ["/tmp"]
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
