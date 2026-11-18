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

# JVM のタイムゾーンを Asia/Tokyo に固定する（deploy-runbook §1.0・§4。必須）。account lock の期限と監査等の日時は
# JVM 既定のタイムゾーンで作られ、UTC のままだと30分の lock が約9時間30分続き、時刻も9時間ずれる。
# task definition で JAVA_TOOL_OPTIONS を上書きする場合も -Duser.timezone=Asia/Tokyo を残す。
ENV TZ=Asia/Tokyo
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Duser.timezone=Asia/Tokyo"

# ALB/ECS liveness equivalent. Healthy only on HTTP 200: curl -f treats 302 as success, and
# X-Forwarded-Proto from loopback is never trusted, so the health paths answer over plain HTTP (FWD-SANITIZE P2-1).
HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=5 \
  CMD test "$(curl -sS -o /dev/null -m 4 -w '%{http_code}' http://127.0.0.1:8080/actuator/health/liveness)" = 200 || exit 1

# -Dserver.shutdown=graceful: YAML 以外でも確実に排水モードを強制（REV-ECS-P2-003）
ENTRYPOINT ["java", "-Dserver.shutdown=graceful", "-jar", "/app/app.jar"]
