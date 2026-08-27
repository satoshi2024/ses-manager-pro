# Local ECS Fargate+ALB wiring contract (documentation for real AWS later).
# Validated against Moto LOCAL-SIM only — not ECS-ACTUAL.
#
# Topology decision:
#   desiredCount = 1  → SESSION-TOPOLOGY = SINGLE-INSTANCE-CONDITIONAL
#   ACC-OPS-P1-003 remains open if desiredCount later > 1 (Redis/ElastiCache required).
#   ALB sticky session MUST NOT close ACC-OPS-P1-003.
#
# ALB health checks (to configure on real Target Group):
#   Healthy path:   /actuator/health/liveness
#   Optional deep:  /actuator/health/readiness  (DB-sensitive; prefer TG = liveness, ECS task = both)
#   Matcher:        HTTP 200
#   Interval:       30s
#   Unhealthy:      3
#   Healthy:        2
#   Timeout:        5s
#
# Fail-closed env (must be present in task definition / SSM):
#   SPRING_PROFILES_ACTIVE=prod
#   AI_PROVIDER=mock
#   AI_EXTERNAL_SEND_ENABLED=false
#   CLOUDSIGN_ENABLED=false
#   DIGITAL_INVOICE_PROVIDER=none
#   FREEE_* unset → MISCONFIGURED
#
# Secrets:
#   DB credentials via Secrets Manager arn …:secret:ses/staging/db
#
# Logs:
#   /ecs/ses-manager-staging  (awslogs)
#
# Resources:
#   cpu=1024 memory=2048 (Fargate)
#
# Circuit breaker:
#   deploymentCircuitBreaker.enable=true rollback=true
#
# Network (real AWS):
#   private subnets + task SG egress to MySQL/Secrets/CW
#   ALB SG ingress 443 from corp/VPN only
