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

## prod 必須設定（環境変数・secret）

- prod が起動を拒否する設定（29項目）の**正本は `.kiro/specs/production-launch/deploy-runbook.md` §1.0** である。各項目の用途・形式・secret かどうか・出処はそこに書く。
- `task-definition.prod.json` には、その名前を placeholder（`*_PLACEHOLDER`、`ACCOUNT_ID` の ARN）で載せるだけで、値は書かない。
- 配備では全ての placeholder を置き換える。置き換え忘れがあると、アプリの起動時検査か ECS の secret 取得で止まる。
- `validate-task-definition.ps1` は、§1.0 の名前が environment か secrets に全てあること、secret を environment に置いていないことを検査する。
- 次の3点に注意する。
  - reviewer fingerprint の鍵（§1.0 の23・24）は OS 環境変数では渡せないため、`SPRING_APPLICATION_JSON` の secret で渡す。
  - `BREAK_GLASS_USERNAMES` は、bootstrap の初回起動では空にする。runbook §5.5 でちょうど2名にする。
  - freee の4項目（§1.0 の26〜29）は、freee を使わない配備でも必要である。

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
