# Offline AWS / local ECS-like simulation (Docker only)

**NOT real AWS.** No ECR push, no ECS deploy, no AWS API.
This is an **offline AWS / local ECS-like simulation**, not a substitute for AWS actual acceptance.

## Probe convention (REV-ECS-P1-001)

| Probe | Path | Use |
|---|---|---|
| ECS container healthCheck | `/actuator/health/liveness` | Process alive |
| **ALB Target Group** | `/actuator/health/readiness` | **Required** — includes `db` |
| Forbidden | ALB → liveness | Would keep sending traffic when DB is down |

## Build image

```powershell
pwsh infra/ecs/build-image.ps1
```

`LOCAL_IMAGE_ID` is not an ECR registry digest.

## Run simulation 2.1

```powershell
pwsh infra/ecs/offline/validate-task-definition.ps1
pwsh infra/ecs/offline/verify-offline-sim.ps1 -EgressNegativeSelfTestOnly
pwsh infra/ecs/offline/verify-offline-sim.ps1
```

## Uploads (REV-ECS-P1-002)

- Compose: named volume `ses-offline-uploads` (not tmpfs)
- `/tmp` remains tmpfs
- Task definition: EFS placeholder on `uploads` volume — **EFS-ACTUAL=BLOCKED** without real AWS

## P3 OPEN

- Base image apt package versions are not fully reproducible across rebuild dates (REV-ECS-P3-001 remains OPEN).

## Tear down

```powershell
docker compose -f infra/ecs/offline/docker-compose.yml down -v
```
