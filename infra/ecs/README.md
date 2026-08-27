# ECS app image — offline AWS / local ECS-like simulation

本番 ECS/Fargate 向けアプリコンテナイメージの**ローカル**ビルド手順。
**AWS への push / ECS デプロイは行わない。**

これは **offline AWS / local ECS-like simulation** 用であり、実 AWS 本番合格の代替ではない。

## Probe 規約

- ECS container healthCheck → `/actuator/health/liveness`
- ALB Target Group → `/actuator/health/readiness`（**必須**。liveness を ALB に使うことは禁止）

## ビルド

```powershell
pwsh infra/ecs/build-image.ps1
```

## P3 OPEN

apt リポジトリ由来のパッケージ版は再ビルド日によって変わり得る（REV-ECS-P3-001）。本バッチではベースイメージ再設計は行わない。
