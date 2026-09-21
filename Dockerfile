# Multi-stage build: compile with the full JDK, run on a slim JRE.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

COPY mvnw .
COPY .mvn .mvn
COPY pom.xml .
# Windows checkouts routinely drop the executable bit on this file; don't rely on git's
# stored file mode surviving every contributor's platform.
RUN chmod +x mvnw
RUN ./mvnw -B -q dependency:go-offline

COPY src src
RUN ./mvnw -B -q -DskipTests package
RUN mv target/aatlas-api-*.jar target/app.jar

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN useradd --system --create-home appuser
USER appuser
COPY --from=build /app/target/app.jar app.jar

# Render sets PORT; server.port already reads it (${PORT:8080}).
EXPOSE 8080
# The JVM's default container-aware heap sizing is conservative (25% of available
# memory); on a small instance that is not enough headroom for Hibernate's metamodel
# plus a JPA/JDBC pool plus whatever else is loaded during startup - let it use most
# of what the container actually has instead.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar", "--spring.profiles.active=render"]
