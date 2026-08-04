# =============================================================================
# CityPulse OS backend
#
# Multi-stage: the JDK and the Maven cache stay in the build stage, so the
# runtime image carries a JRE and one jar rather than a full toolchain.
# =============================================================================

FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /build

# Dependencies resolve in their own layer, keyed on the POM alone. Source
# changes then rebuild in seconds instead of re-downloading the world.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B dependency:go-offline -q

COPY src/ src/
# Tests run in CI against a real PostgreSQL service; running them here would
# need a database this stage does not have.
RUN ./mvnw -B clean package -DskipTests -q

# -----------------------------------------------------------------------------

FROM eclipse-temurin:21-jre-alpine AS runtime

# wget backs the compose healthcheck; the JRE image does not ship curl.
RUN apk add --no-cache wget \
    && addgroup -S citypulse \
    && adduser -S -G citypulse -h /app citypulse

WORKDIR /app

COPY --from=build --chown=citypulse:citypulse /build/target/*.jar app.jar

# Never root: a container escape should not start from a privileged account.
USER citypulse

EXPOSE 8080

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError"

# MaxRAMPercentage respects the container memory limit rather than the host's.
# ExitOnOutOfMemoryError makes the orchestrator restart a wedged JVM instead of
# leaving it degraded and still passing a shallow liveness check.

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget --spider -q http://localhost:8080/actuator/health || exit 1

# Exec form so the JVM is PID 1 and receives SIGTERM directly, which lets
# Spring's graceful shutdown drain in-flight requests.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
