# Offline ECS-like simulation (Docker only)

**NOT real AWS.** No ECR push, no ECS deploy, no AWS API.

## Build image

```powershell
pwsh infra/ecs/build-image.ps1
```

`image-lock.json` records `localImageId` (LOCAL_IMAGE_ID). This is **not** an ECR `registryDigest`.

## Run simulation

```powershell
pwsh infra/ecs/offline/validate-task-definition.ps1
pwsh infra/ecs/offline/verify-offline-sim.ps1
```

## Tear down

```powershell
docker compose -f infra/ecs/offline/docker-compose.yml down -v
```

## Session topology

Single app container only → `ACC-OPS-P1-003 = DEFERRED-SINGLE-INSTANCE`.
If future ECS `desiredCount > 1`, Redis/ElastiCache session must be re-accepted.
