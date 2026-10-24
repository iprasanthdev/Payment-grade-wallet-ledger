# ==============================================================================
# Multi-stage Dockerfile for Payment-Grade Wallet Ledger
# Stage 1: Build JAR using Gradle & JDK 21
# Stage 2: Minimalist, secure JRE 21 Runtime Image
# ==============================================================================

FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app

# Cache gradle dependencies
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
COPY gradle.properties .
RUN chmod +x ./gradlew && ./gradlew dependencies --no-daemon || true

# Copy source code and build production fat JAR
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# ==============================================================================
# Stage 2: Production Runtime
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

# Install curl for docker healthchecks
RUN apk add --no-cache curl tzdata

# Create unprivileged application user
RUN addgroup -S wallet && adduser -S wallet -G wallet
USER wallet

# Copy compiled JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

ENV SERVER_PORT=8080
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD curl -f http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
