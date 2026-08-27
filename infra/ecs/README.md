# ECS app image — offline AWS / local ECS-like simulation

本番 ECS/Fargate 向けアプリコンテナイメージの**ローカル**ビルド手順。
**AWS アカウントなし — ECR push / ECS deploy / AWS API は OUT-OF-SCOPE。**

## Digest 语义

| 字段 | 含义 |
|---|---|
| `localImageId` | 本地 `docker build` 得到的 **LOCAL_IMAGE_ID**（`docker inspect` Id） |
| `baseImageDigest` | 基础 JRE 镜像 digest（Dockerfile `FROM @sha256:…`） |
| `jarSha256` | 打入镜像的 JAR SHA256 |
| `gitRevision` | 构建时 git HEAD |
| `registryDigest` | **ABSENT** — 不得将 `localImageId` 当作 ECR digest 或 `IMAGE_DIGEST` |

`task-definition.prod.json` 保持 `...@sha256:IMAGE_DIGEST` 占位。

## Probe 規約

- ECS container healthCheck → `/actuator/health/liveness`
- ALB Target Group → `/actuator/health/readiness`（**DEFERRED** until real AWS）

## ビルド & 検証

```powershell
pwsh infra/ecs/build-image.ps1
pwsh infra/ecs/offline/verify-offline-sim.ps1
pwsh infra/ecs/offline/validate-task-definition.ps1
```

## OUT-OF-SCOPE / DEFERRED

ECS-ACTUAL, ECR, IAM actual, ALB actual, CloudWatch actual, EFS actual, SBOM/vuln scan (BLOCKED-TOOLING)

## P3 OPEN

apt パッケージ版は再ビルド日で drift し得る。ベースイメージ再設計は本バッチ対象外。
