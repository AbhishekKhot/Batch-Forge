# ── Build stage: compile + package the jar ──────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies first (only re-downloads if pom.xml changes)
COPY pom.xml .
COPY .mvn/ .mvn/
COPY mvnw .
RUN ./mvnw -B dependency:go-offline

# Build (skip tests — they need the full stack; CI runs them separately)
COPY src/ src/
RUN ./mvnw -B clean package -DskipTests

# ── Runtime stage: JRE + the jar only ───────────────────────────────────────
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user
RUN groupadd --system batchforge && useradd --system --gid batchforge batchforge
USER batchforge

COPY --from=build /build/target/batchforge-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]