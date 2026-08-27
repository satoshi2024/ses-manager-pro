# Local AWS simulator (Docker + Moto)
#
# Purpose: exercise AWS CLI wiring for Secrets/SSM/Logs/ECS task definition locally
# without a real AWS account.
#
# This does NOT equal real ECS Fargate+ALB production acceptance.
# ECS-ACTUAL stays BLOCKED; PRODUCTION stays NO-GO until real AWS identity + deploy auth.
#
# Why Moto (not LocalStack default)?
# - LocalStack community edition does not implement ECS (Pro-only).
# - Moto freely mocks ecs/secretsmanager/ssm/logs/sts for wiring drills.
#
# Start:
#   docker compose -f infra/local-aws/docker-compose.yml up -d
# Verify:
#   pwsh infra/local-aws/verify-local-aws.ps1
# Stop:
#   docker compose -f infra/local-aws/docker-compose.yml down -v
#
# Optional LocalStack (non-ECS services only) on :4567:
#   docker compose -f infra/local-aws/docker-compose.yml --profile localstack up -d
#
# Required host tools: Docker Desktop, AWS CLI v2
