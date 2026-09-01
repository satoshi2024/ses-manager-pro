# AI Management Copilot Review Ledger

## 0. Review state

| 項目 | 状態 |
|---|---|
| Review type | Plan **CONDITIONAL PASS**（2026-09-01）。実装 **CONDITIONAL PASS**（2026-09-01 M handoff） |
| Implementation state | **M DONE — F1〜M 実装完了。独立 Review 待ち** |
| Central NF-08 state | `CANDIDATE` |
| 具体AIモデル | **未決定**（pipelineはモデル非依存。summary層のみ`AiTextService`で差し替え） |
| Existing AI learning state | `CONDITIONAL PASS`（P2残、GATE-S17-G10-PROD保留） |
| NF-07 | 未完・retention/PII inventory承認待ち |
| DG-08 | 未完・provider/DPA/越境/owner/role/retention/cost/escalation未確定 |
| Provider allowed now | local mock/rule only |
| External send | OFF。`ai.external-send-enabled=false`を維持 |
| Feature flag | `ai.management-copilot-enabled=false`（本番ON不可） |
| PR | **作成しない**（R-NF08 IMPLEMENTATION PASS 後に別主体） |
| Base | `origin/main@4c93b558d57193c3d77e06cb54c0a6573c87a60b` |
| Remote Head | `origin/codex/ai-management-copilot`（push 後 `git rev-parse` で確認） |
| Working branch | `codex/ai-management-copilot` |
| Working tree | `C:\work\ses-manager-pro-ai-management-copilot` |
| Remote | `https://github.com/satoshi2024/ses-manager-pro.git` |
| 開工対話正本 | `start-conversations.md` |
| Review対話正本 | `review-conversations.md` §4 **R-NF08** |
| SNF01〜10横断Review | `review-conversations.md` §5 |

## 1. 承認入力の未解決表

| input | ユーザー提示値 | 実装開始に必要な証跡 | 状態 |
|---|---|---|---|
| approved query catalog / roles / provider | `<APPROVED_SCOPE>` | query ID、parameter schema、role、scope、provider、owner、version | **未解決**（provisional catalog で実装済み） |
| Owner | `<OWNER>` | catalog/gate/evaluationの責任者 | **未解決** |
| Base branch | `origin/main` | branch名とcommitの対応 | **確定** |
| Base commit | `4c93b558d57193c3d77e06cb54c0a6573c87a60b` | immutable commit | **確定** |
| NF-07 | 未指定 | PII inventory、allowlist、retention、purge、processor条件 | **未完** |
| existing AI production gate | `GATE-S17-G10-PROD` | G10/DPA/越境/training opt-out/送信flag/production evidence | **保留** |
| DG-08 | 未指定 | provider/owner/role/retention/cost/human escalationのDecision | **未完** |

## 2. 完了対応表（Discovery + Implementation）

| ユーザー要求 | 対応文書/証跡 | 状態 |
|---|---|---|
| 専用worktree/branch、normal checkout保全 | worktree検証、F1〜M commits | **完了** |
| 固定pipeline | F1〜B1 code、`CopilotQueryService` | **完了** |
| catalog外SQL/table/column禁止 | `IntentParser`、`SemanticCatalogRegistry.isSqlOrSchemaProbe`、B2 fixture | **完了** |
| typed result・円/割合/期間/timezone/freshness/forecast | F2 `TypedResultEnvelope`、`CopilotMetricContractTest` | **完了**（adapter単体。画面/export横断は Review で確認） |
| scope A/B | `CopilotScopeResolverTest`、F2 gateway | **完了** |
| prompt injection / adversarial | B2 fixture 12 cases | **完了** |
| provider 429/timeout/invalid JSON | B1 `CopilotSummaryServiceTest` | **完了** |
| scope外ID/PII推測防止、citation再認可 | A1 `CitationAuthorizationService`、B2 citation cases | **完了** |
| feedback/model/prompt/data version/cost/latency記録 | F1 `CopilotRunService`、B2 evaluation metrics | **完了**（redacted run） |
| mock/ruleまで、外部AI無効 | `application.yml`、`CopilotFeatureGateTest` | **完了** |
| 画面/export/AI metric一致 | `CopilotMetricContractTest`（5 adapters 全件） | **完了** |
| 完了後もgate未完ならCONDITIONAL PASS・flag OFF | 本 ledger §0 | **確定** |

## 3. Task completion

| task | status | commit | evidence |
|---|---|---|---|
| T000 Discovery/gate/inventory | **DONE** | docs(nf08) | spec、worktree検証 |
| F1 catalog/run/feedback | **DONE** | `0363a73a` | SemanticCatalogRegistry, CopilotRunService, V144 |
| F2 intent/parameter/scope/service gateway | **DONE** | `e813cde6` | CatalogQueryGateway, TypedResultEnvelope, scope tests |
| A1 chat/citation UI | **DONE** | `519db6b0` | chat.html, copilot.js, V145 menu |
| B1 summary provider | **DONE** | `56616fba` | CopilotSummaryService, validator, mock TASK |
| B2 evaluation/adversarial | **DONE** | `b39bfee4` | fixture 12 cases, evaluation API |
| M integration/review handoff | **DONE** | `docs(nf08-m)` | 本 ledger、feature gate test、remote push |

## 4. Review handoff contract

### Plan Review

**判定（2026-09-01）: PLAN CONDITIONAL PASS**

CONDITIONAL理由: `<APPROVED_SCOPE>` / `<OWNER>` / NF-07 / DG-08 未決。

### Implementation Review（M handoff 自己申告）

**判定: IMPLEMENTATION CONDITIONAL PASS**

| # | 観点 | 結果 |
|---|---|---|
| 1 | F1〜M pipeline 完遂 | PASS |
| 2 | catalog外/SQL/schema 拒否 | PASS |
| 3 | typed result / scope / citation | PASS |
| 4 | summary 層分離・mock/rule only | PASS |
| 5 | adversarial / feature flag OFF | PASS |
| 6 | NF-07 / DG-08 / G10-PROD gate | **BLOCKED**（本番AI不可） |
| 7 | approved catalog / owner | **BLOCKED**（provisional のまま） |

**本番 AI 有効化: 不可**（`management-copilot-enabled=false`、`external-send-enabled=false` 維持）

独立 Review は `review-conversations.md` §4 **R-NF08** を新規対話へコピーして実施。PASS 後のみ PR 作成。

## 5. Test evidence

### 5.1 NF-08 専用 fast gate（2026-09-01、**全 PASS**）

```
mvn test -Dtest=CopilotFeatureGateTest,CopilotApiControllerTest,CopilotQueryServiceTest,
  CopilotMetricContractTest,CopilotScopeResolverTest,CatalogQueryGatewayTest,
  IntentParserTest,TypedParameterBinderTest,CopilotSummaryServiceTest,
  CopilotSummaryValidatorTest,CopilotAdversarialCaseRunnerTest,
  CopilotAdversarialEvaluationTest,CopilotChatUiContractTest,
  MockAiResponsesManagementCopilotTest,MigrationScriptIntegrityTest,
  MessageBundleConsistencyTest
```

`CopilotMetricContractTest`: dashboard.summary / utilization-forecast / profit-analysis / management-accounting / cashflow の 5 adapters を正本 service 出力と照合。

### 5.2 verify-like-ci.ps1（2026-09-01）

| Gate | 結果 | 備考 |
|---|---|---|
| fast (H2) | **FAIL** | 3408 tests、Failures 5、Errors 2。**NF-08/copilot 関連は全 PASS** |
| mysql | 未実行 | fast gate 失敗で中断 |
| performance | 未実行 | 同上 |
| backup | 未実行 | 同上 |

fast gate 失敗（NF-08 無関係）:
- `IntegrationHubF1RetentionH2Test`
- `TestIsolationAuditTest`
- `TransactionalRollbackForAuditTest`
- `MonthlyClosingUnacceptedTest`（2件）
- `AssetBoundaryAndLifecycleIntegrationTest`（2 errors）

**NF-08 スコープの copilot テストは fast suite 内でも全緑**（`CopilotMetricContractTest` 5/5 含む）。PR 前に上記 7 件の branch 回帰解消または main との差分確認を推奨。

## 6. Query catalog coverage（provisional）

| queryId | enabled | contract test |
|---|---|---|
| dashboard.summary | yes | `CopilotMetricContractTest` |
| dashboard.profit-analysis | yes | gateway adapter |
| dashboard.utilization-forecast | yes | F2 gateway + intent |
| management-accounting.summary | yes | gateway adapter |
| cashflow.forecast | yes | gateway adapter |
| sales-performance.monthly | **no** | disabled → 403（DataScope待ち） |

## 7. Commit 一覧（`origin/main`..HEAD）

| commit | message |
|---|---|
| `64c51742` | docs: AI Management CopilotのDiscovery specを追加 |
| `e27b17d7` | docs(nf08): add start/review conversations |
| `0363a73a` | feat(nf08-f1): semantic catalogとMANAGEMENT_COPILOT run基盤 |
| `e813cde6` | feat(nf08-f2): catalog gatewayと正本service typed result |
| `519db6b0` | feat(nf08-a1): copilot chat UIとcitation再認可 |
| `56616fba` | feat(nf08-b1): model-agnostic summary provider |
| `b39bfee4` | feat(nf08-b2): copilot adversarial evaluation suite |
| `docs(nf08-m)` | docs(nf08-m): review handoffとcompletion matrix |
| `719987d9` | test(nf08): expand copilot metric contract coverage |

## 8. Rollback

1. `ai.management-copilot-enabled=false`、`ai.external-send-enabled=false`、`ai.provider=mock`
2. `m_ai_artifact_version` の MANAGEMENT_COPILOT active を retired へ CAS rollback
3. V145 menu / permission seed は flag OFF のまま残しても安全（未到達）
4. run/feedback 行は redacted のみ。purge は NF-07 承認後

## 9. モデル / provider 切替手順（B1）

1. **artifact登録**: `t_ai_artifact_version` に `use_case=MANAGEMENT_COPILOT` の candidate を追加。
2. **評価**: B2 adversarial suite（`POST /api/copilot/evaluations/run`）で baseline 比較。
3. **provider切替**: DG-08 / `GATE-S17-G10-PROD` 承認後のみ `external-send-enabled=true`。
4. **feature flag**: R-NF08 IMPLEMENTATION PASS + scope/citation 受入後のみ `management-copilot-enabled=true`。
5. **rollback**: artifact shadow/retired、flag OFF、mock provider へ復帰。

## 10. 次の handoff（Review AI へ）

**新規 Review 対話**に `review-conversations.md` §4 **R-NF08** をコピーし、以下を指定:

- Base: `4c93b558d57193c3d77e06cb54c0a6573c87a60b`
- Head: `origin/codex/ai-management-copilot` の push 後 SHA
- Branch: `codex/ai-management-copilot`
- Spec: `.kiro/specs/ai-management-copilot/`
- 本 ledger + `tasks.md`（全 checkbox 完了）

現段階: **PLAN CONDITIONAL PASS / IMPLEMENTATION CONDITIONAL PASS**。本番 AI 有効化は不可。
