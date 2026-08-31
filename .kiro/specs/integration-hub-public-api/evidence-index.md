# NF-05 M verification evidence index

B2 独立 Implementation Review PASS（P0/P1/P2=0/0/0、Linux connector 5/5）後の M フェーズ証跡。
production enablement、実 credential、PR/merge は Review PASS 前禁止。

## Wave status

| Wave | Review | M 証跡 |
|---|---|---|
| F1 | IMPLEMENTATION PASS | F1 H2 31 + MySQL concurrency 5（既存） |
| F2 | IMPLEMENTATION PASS | security chain integration（既存） |
| A1 | IMPLEMENTATION PASS | DTO contract + read mapper（既存） |
| B1 | IMPLEMENTATION PASS | worker/signer/replay（既存） |
| B2 | IMPLEMENTATION PASS | inbound H2 + Linux connector E2E 5/5 |
| M | IMPLEMENTATION_REVIEW_PENDING | 本 index |

## M test matrix

| 領域 | テスト | 要件 |
|---|---|---|
| penetration / scope | `IntegrationHubMPenetrationTest` | IH-R4 client A/B、count/list/detail 非観測、存在秘匿 |
| failure drill | `IntegrationHubMFailureDrillTest` | stale lease recovery、restore epoch、attempt 8 DLQ |
| performance boundary | `IntegrationHubMPerformanceBoundaryTest` | minute=60 exact、burst=20 拒否、Retry-After |
| key rotation / revoke | `IntegrationHubKeyRotationRecoveryDrillTest` | 24h overlap、revoke、usable 失効 |
| secret / PII scan | `IntegrationHubSecretLogScanTest` | log への plaintextSecret/rawBody/decrypt 禁止 |
| security chain | `ExternalApiSecurityChainIntegrationTest`（既存） | deny-only、fall-through なし |
| cross-client SQL | `ExternalApiReadMapperIntegrationTest`（既存） | related scope 同一 WHERE |
| rate exact | `ApiUsageBucketServiceTest`（既存） | burst refill、clock rollback |
| crypto rotation | `IntegrationHubSecretCryptoServiceTest`（既存） | IHG1 envelope、overlap 復号 |
| credential lifecycle | `CredentialVersionServiceTest`（既存） | issue overlap、revoke CAS |
| inbound E2E | `ExternalApiInboundConnectorE2ETest`（既存） | 202/200/409/403/400、audit |
| read E2E | `ExternalApiEnabledConnectorE2ETest`（既存） | enabled chain 到達 |
| MySQL concurrency | `IntegrationHubF1MySqlConcurrencyTest`（既存） | usage/delivery CAS、stale lease |
| DTO allow-list | `ExternalApiDtoContractTest`（既存） | entity serialization 禁止 |
| metrics cardinality | `ExternalApiMetricsRecorderTest`（既存） | 有限 label 集合 |

## Operations artifacts

| 種別 | パス |
|---|---|
| runbook | `ops/security/runbooks/integration-hub-public-api.md` |
| spec tasks | `.kiro/specs/integration-hub-public-api/tasks.md` Task M |
| completion matrix | `.kiro/specs/integration-hub-public-api/completion-matrix.md` |
| review ledger | `.kiro/specs/integration-hub-public-api/review-ledger.md` |

## 実行コマンド（M focused suite）

```powershell
cd c:\work\ses-manager-pro-integration-hub-public-api
.\apache-maven-3.9.6\bin\mvn test -Dtest=IntegrationHubMPenetrationTest,IntegrationHubMFailureDrillTest,IntegrationHubMPerformanceBoundaryTest,IntegrationHubKeyRotationRecoveryDrillTest,IntegrationHubSecretLogScanTest
```

## 禁止事項（M 完了まで）

- production `integration.hub.public-api.enabled=true`
- 実顧客 credential / 実 provider 送信
- main checkout 変更、force push、PR 作成、merge

## Handoff

M 実装 commit 後、remote/local Head を独立 Review へ固定する。
PLAN/IMPLEMENTATION 双方 PASS 後のみ PR 作成を許可する。
