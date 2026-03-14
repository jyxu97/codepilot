# ── Stage 1: build ──────────────────────────────────────────────────────────
# Use a full Maven + JDK image to compile and package the application.
FROM maven:3.9-eclipse-temurin-21-alpine AS build

WORKDIR /app

# Copy the POM first so Maven can download dependencies in a separate layer.
# This layer is only invalidated when pom.xml changes — not on every code edit.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build the fat JAR (skip tests — they run in CI, not here)
COPY src ./src
RUN mvn package -DskipTests -q

# ── Stage 2: runtime ─────────────────────────────────────────────────────────
# Use a minimal JRE image — no compiler, no Maven, no source code in the final image.
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Copy only the fat JAR from the build stage
COPY --from=build /app/target/*.jar app.jar

# Run as a non-root user for security
RUN addgroup -S orchestrator && adduser -S -u 1001 orchestrator -G orchestrator
USER orchestrator

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
