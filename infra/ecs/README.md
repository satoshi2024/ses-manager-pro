# ECS app image — offline AWS / local ECS-like simulation

本番 ECS/Fargate 向けアプリコンテナイメージの**ローカル**ビルド手順。
**AWS アカウントなし — ECR push / ECS deploy / AWS API は OUT-OF-SCOPE。**

## Digest の意味

| 項目 | 意味 |
|---|---|
| `localImageId` | ローカルの `docker build` で得た **LOCAL_IMAGE_ID**（`docker inspect` の Id） |
| `baseImageDigest` | ベース JRE イメージの digest（Dockerfile の `FROM @sha256:…`） |
| `jarSha256` | イメージに含めた JAR の SHA256 |
| `gitRevision` | ビルド時の git HEAD |
| `registryDigest` | **ABSENT** — `localImageId` を ECR digest や `IMAGE_DIGEST` として扱ってはならない |

`task-definition.prod.json` は `...@sha256:IMAGE_DIGEST` のプレースホルダーのままにする。

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
