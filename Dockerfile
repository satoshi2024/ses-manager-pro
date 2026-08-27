# syntax=docker/dockerfile:1
# SES Manager Pro production app image (offline AWS / local ECS-like simulation).
# Build: pwsh infra/ecs/build-image.ps1
# Does NOT push ECR. LOCAL_IMAGE_ID is not an ECR registry digest.
# ECS container HEALTHCHECK = liveness; ALB TG must use /actuator/health/readiness.
ARG JRE_BASE=eclipse-temurin:21-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf
FROM ${JRE_BASE}

ARG APP_JAR=ses-manager-pro-1.0.0-SNAPSHOT.jar

RUN groupadd --gid 10001 ses \
 && useradd --uid 10001 --gid ses --shell /usr/sbin/nologin --create-home ses \
 && apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY target/${APP_JAR} /app/app.jar
RUN chown ses:ses /app/app.jar

USER ses:ses
EXPOSE 8080

ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# ALB/ECS liveness equivalent. Offline prod sim may send X-Forwarded-Proto via compose HEALTHCHECK.
HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=5 \
  CMD curl -fsS -H "X-Forwarded-Proto: https" http://127.0.0.1:8080/actuator/health/liveness >/dev/null || exit 1

# -Dserver.shutdown=graceful: YAML 以外でも確実に排水モードを強制（REV-ECS-P2-003）
ENTRYPOINT ["java", "-Dserver.shutdown=graceful", "-jar", "/app/app.jar"]
