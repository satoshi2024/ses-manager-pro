# ECS app image (offline build)

本番 ECS/Fargate 向けアプリコンテナイメージのローカルビルド手順。
**AWS への push / ECS デプロイは行わない** — digest 記録と Dockerfile 検証のみ。

## 前提

- Docker Desktop
- JDK 21 + Maven（`apache-maven-3.9.6/` またはシステム `mvn`）
- `mvn package` 済み JAR（スクリプトが自動実行）

## ビルド

```powershell
pwsh infra/ecs/build-image.ps1
```

成果物:

- ローカルイメージ `ses-manager-pro:local`
- `infra/ecs/image-lock.json` — JRE ベース digest + ビルドイメージ digest/id
- `.kiro/reviews/.../aws-local/IMAGE-BUILD.md` — 証跡

## 必須ランタイム環境変数（本番）

| 変数 | 例 / 備考 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod`（必須） |
| `DB_URL` | MySQL JDBC（本番は TLS パラメータ推奨 — P2 未解） |
| `DB_USERNAME` / `DB_PASSWORD` | Secrets Manager から注入 |
| `AI_PROVIDER` | `mock`（DPA 前） |
| `AI_EXTERNAL_SEND_ENABLED` | `false` |
| `CLOUDSIGN_ENABLED` | `false` |
| `DIGITAL_INVOICE_PROVIDER` | `none` |

## ECS task definition との関係

- ローカル Moto 配線: `infra/local-aws/ecs/task-definition.local.json`
- 本番用テンプレート: `infra/ecs/task-definition.prod.json`（`ses-manager-pro:local` → ECR digest に差し替え）

## 注意

- `VERDICT_ECS_ACTUAL` / `PRODUCTION` は digest 記録だけでは **PASS にならない**
- ECR push と Fargate 実起動は別途 AWS 認証 + 明示承認が必要
