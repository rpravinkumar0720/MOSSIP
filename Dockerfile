# Multi-stage Dockerfile for MOSIP Face Liveness & PAD Service
# Build stage: Maven with JDK 17
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy Maven parent and module definitions
COPY pom.xml .
COPY liveness-api/pom.xml liveness-api/
COPY liveness-core/pom.xml liveness-core/
COPY liveness-device-mock/pom.xml liveness-device-mock/
COPY liveness-server/pom.xml liveness-server/
COPY liveness-eval/pom.xml liveness-eval/

# Pre-fetch dependencies
RUN mvn dependency:go-offline -B -DskipTests || true

# Copy all source trees
COPY liveness-api/src liveness-api/src
COPY liveness-core/src liveness-core/src
COPY liveness-device-mock/src liveness-device-mock/
COPY liveness-server/src liveness-server/src
COPY liveness-eval/src liveness-eval/src

# Build and package all modules
RUN mvn clean package -DskipTests -B

# Runtime stage: Lightweight Temurin 17 JRE
FROM eclipse-temurin:17-jre-jammy

LABEL maintainer="MOSIP Community <info@mosip.io>"
LABEL description="Production-ready MOSIP Face Liveness & PAD Service (ISO/IEC 19794-5 & ISO 30107-3 Baseline)"

WORKDIR /app

# Non-root service user for production container hardening
RUN groupadd -r mosip && useradd -r -g mosip mosip

# Copy shaded fat JAR from build stage
COPY --from=builder /build/liveness-server/target/liveness-server-1.0.0-SNAPSHOT.jar /app/liveness-server.jar

# Configuration defaults
ENV PORT=8080 \
    LIVENESS_DIAGNOSTIC=false \
    LIVENESS_DEMO=true \
    LIVENESS_LOCKOUT_SECONDS=60

USER mosip

EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:${PORT}/api/health || exit 1

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/liveness-server.jar"]
