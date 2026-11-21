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

## MySQL image

- 既定は本番と同じ MySQL 8.4 LTS の固定tag `mysql:8.4.13`（`docker-compose.yml` と `verify-offline-sim.ps1` の補助imageの両方。test の既定値 `src/test/java/com/ses/test/MySqlTestImages.java` と揃える）。
- 本機に 8.4 のimageが無く、新たに取得しない（downloadしない）場合は、実行前に `SES_OFFLINE_MYSQL_IMAGE=mysql:8.0.36` を設定する。設定しないと compose が `mysql:8.4.13` を pull しにいく。

```powershell
$env:SES_OFFLINE_MYSQL_IMAGE = 'mysql:8.0.36'
pwsh infra/ecs/offline/verify-offline-sim.ps1
```

- その結果は 8.0 での確認であり、8.4 の証拠にはならない。8.4 の offline simulation は 8.4 のimageを取得できる環境で行う（CI では実行していない）。

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
