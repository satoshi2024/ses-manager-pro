# AI Management Copilot — 開工対話集

> **正本**: 本ファイルが NF-08 実装対話の唯一の入口である。
> 中央 `2026-08-27-post-acceptance-start-conversations.md` §S-NF08 は概要のみ。詳細は本書をコピーする。
> Review は [review-conversations.md](review-conversations.md) を別対話で使用する。

## 1. 使い分け

| 用途 | 使用する対話 |
|---|---|
| 初回・pipeline 全体の確認 | 本書 §2（S0 総開工） |
| F1 semantic catalog / run / feedback | 本書 §3 F1 |
| F2 intent / parameter / scope / gateway | 本書 §3 F2 |
| A1 chat / citation UI | 本書 §3 A1 |
| B1 summary provider（モデル差し替え層） | 本書 §3 B1 |
| B2 evaluation / adversarial | 本書 §3 B2 |
| M 統合・Review handoff | 本書 §3 M |
| 中断後の再開 | 本書 §4 |

1 task = 1 commit + push。実装対話では PR を作らない。M 完了後に独立 Review へ remote Head を渡す。

## 2. S0 — 総開工対話

以下を新しい実装対話へコピーする。

---

あなたは `ai-management-copilot`（NF-08）の主実装 AI です。これは F1〜M の総開工対話です。

### 発注者前提（2026-09-01）

- **本番で使う具体モデル（Gemini / OpenAI / Claude 等）は未決定**。コードにモデル名をハードコードしない。
  `AiConfig`（`ai.provider` / `ai.model`）と `AiArtifactVersion.modelVersion` で差し替える。
- **先に AI の役割と pipeline を構築する**。業務指標の正本は `DashboardService` 等の既存 Java service。
  LLM は redacted claim に基づく **summary 生成のみ**。モデル切替は pipeline を変えない。
- NF-07 / DG-08 / `GATE-S17-G10-PROD` は未完。`ai.external-send-enabled=false` を維持し、
  本番外部送信・credential 配置・`management-copilot-enabled=true`（本番）は行わない。
- 内部 **provisional catalog**（query ID / role / `enabled` flag）は実装に使ってよいが、
  `<APPROVED_SCOPE>` の正式値は推測で確定せず `review-ledger.md` に `provisional` と記録する。

### 作業規約

- 専用 worktree `C:\work\ses-manager-pro-ai-management-copilot`、branch `codex/ai-management-copilot` のみ変更。
- 通常 checkout `C:\work\ses-manager-pro` は変更しない。WIP があれば本 branch へ移植し spec pipeline に合わせて書き直す。
- 読むもの: `AGENTS.md`、本 spec の `README.md`、`requirements.md`、`design.md`、`tasks.md`、`review-ledger.md`、
  `ai-feedback-learning`（gateway / allowlist / run / feedback）、正本 service、`DataScopeService`。
- Task 完了ごとに 1 commit + push。完了 task だけ `tasks.md` を `- [x]` にする。

### 固定 pipeline（変更禁止）

```text
質問（未信頼）
  → IntentParser（候補 query ID のみ。SQL / bean 名は出さない）
  → SemanticCatalogRegistry.resolve(queryId)（catalog 外は拒否）
  → TypedParameterBinder + validation + parameterHash
  → CopilotScopeResolver（role / menu / DataScope）
  → CatalogQueryGateway → CanonicalServiceAdapter（正本 service のみ）
  → TypedResultEnvelope（円 / 割合 / period / timezone / freshness / basis / state）
  → SummaryProvider（redacted claim + claimKeys のみ。AiTextService へ委譲）
  → CitationAuthorizationService（再認可）
  → AnswerRenderer（数値は typed result から。LLM text を parse しない）
  → Run / Feedback metadata
```

### モデル切替ルール

1. `CatalogQueryGateway` / adapter / scope / citation に `AiTextService` やモデル名を import しない。
2. Summary のみ `CopilotSummaryService` → `AiExecutionGateway`（`useCase=MANAGEMENT_COPILOT`）。
3. run metadata に `provider` / `modelVersion` / `promptVersion` / `catalogVersion` / `dataVersion` を保存。
4. モデル変更 = 設定 + artifact 更新 + contract / adversarial suite 再実行。業務ロジックは不変。
5. `MockAiResponses` に `[TASK:MANAGEMENT_COPILOT]` 専用分岐（PII なし JSON）を置く。

### 禁止

- LLM 生成 SQL / table / column / 任意 service bean の実行
- 回答経路からの業務状態自動更新
- schema / repository / raw prompt の外部送信
- `sales-performance.monthly` を DataScope 統合前に enabled にすること
- gate 未完での本番 feature flag ON

順序: **F1 → F2 → A1 → B1 → B2 → M**。前 task の test / Demo が PASS するまで次へ進まない。
各 task 完了後、必要なら [review-conversations.md](review-conversations.md) の該当 § で増分 Review を依頼できる。最終は §R-NF08。

開始時に worktree / branch / remote / base / `git status` を報告し、次に着手する task（通常 F1）を宣言してください。

---

## 3. Task 別開工対話

### F1 — Semantic catalog / run / feedback

```text
あなたは `ai-management-copilot` の F1 実装 AI です。Task: **F1 Semantic catalog / run / feedback 基盤**。
前提: T000（Discovery）完了、Plan Review CONDITIONAL PASS。総開工 §2 の契約をすべて適用する。

【Objective】
provisional catalog をコード化し、catalog 外実行を型と runtime で拒否する。
`MANAGEMENT_COPILOT` の run / feedback を既存 AI ledger へ接続する。モデルは未決定のまま mock / rule のみ。

【着手前】
1. worktree `C:\work\ses-manager-pro-ai-management-copilot`、branch `codex/ai-management-copilot` を検証。
2. `git fetch origin` 後、必要なら `origin/main` を merge。migration 番号は latest+1 のみ。
3. ローカル WIP の `IntentParser` があれば移植し、query ID を spec catalog ID に揃える:
   `dashboard.summary`, `dashboard.profit-analysis`, `dashboard.utilization-forecast`,
   `management-accounting.summary`, `cashflow.forecast`, `sales-performance.monthly`（**enabled=false**）。
4. `g10-pii-allowlist.md` / `g10-allowlist.json` に `MANAGEMENT_COPILOT` use case を追加。

【実装】
- `SemanticCatalogEntry` / `SemanticCatalogRegistry`（static immutable。DB から任意 bean 名を読まない）
- `CatalogNotFoundException` / `CatalogDisabledException`
- `AiGatewayRequest.USE_COPILOT`、`ai.management-copilot-enabled`（既定 false）
- `CopilotFeedbackType`: HELPFUL / INCORRECT / UNSAFE（既存 ACCEPT/REJECT/HOLD と分離）
- F1 では SummaryProvider を呼ばない。gateway は useCase 登録と run metadata 配線まで。

【Test】
- unknown queryId / SQL / table 名 → 拒否
- disabled entry（`sales-performance.monthly`）→ 実行拒否
- PII canary、duplicate trace
- run に catalogVersion, parameterHash, provider=mock, modelVersion（artifact 由来）が redacted で残る

【Demo】
flag OFF → API 503。flag ON（test のみ）→ catalog resolve + run 記録。外部 egress なし。

【Commit】`feat(nf08-f1): semantic catalogとMANAGEMENT_COPILOT run基盤`
完了後 `review-ledger.md` を更新し、増分 Review なら review-conversations.md §R-F1 へ。
```

### F2 — Intent / parameter / scope / service gateway

```text
あなたは `ai-management-copilot` の F2 実装 AI です。Task: **F2 Intent / typed parameter / scope / service gateway**。
前提: F1 PASS。

【Objective】
質問 → typed parameter → scope → 正本 service → typed result まで通す。F2 では LLM を呼ばない。

【実装】
- `TypedParameterBinder` + DTO（`YearMonth`、期間 1..12 等）
- `CopilotScopeResolver`、`CatalogQueryGateway` + adapters（Dashboard / UtilizationForecast / ManagementAccounting / CashFlow）
- `TypedResultEnvelope` / `MetricValue`、`CopilotQueryService`
- `CopilotApiController` は薄い HTTP 層。WIP の gateway 直結は削除し本 pipeline へ置換。
- `sales-performance.monthly` は registry で enabled=false のまま。

【Test】
Scope A/B、営業 DataScope、catalog 外 / SQL injection 風、0/NULL/forecast、
**metric contract**（画面 API / export と typed result 一致）。

【Commit】`feat(nf08-f2): catalog gatewayと正本service typed result`
```

### A1 — Chat / answer / citation UI

```text
あなたは `ai-management-copilot` の A1 実装 AI です。Task: **A1 Chat / answer / citation UI**。前提: F2 PASS。

【Objective】
typed result を正本として表示する UI。summary は null 可（B1 前でも metrics で完結）。

【実装】
- `templates/copilot/chat.html` + `static/js/modules/copilot.js`
- `CitationAuthorizationService`、menu seed（migration）
- 数値は metrics から render。summary から parse しない。

【Test】CSRF、session expiry、390px、citation 再認可失敗、flag OFF。

【Commit】`feat(nf08-a1): copilot chat UIとcitation再認可`
```

### B1 — Provider / summary（モデル差し替え層）

```text
あなたは `ai-management-copilot` の B1 実装 AI です。Task: **B1 Provider / redaction / timeout / cost**。前提: A1 PASS。

【Objective】
`CopilotSummaryService` を実装し、**モデル / provider を設定で切替可能**にする。F2 pipeline は変更しない。
外部送信は mock / rule のみ（`external-send-enabled=false` 維持）。

【実装】
- `SummaryRequest` / `SummaryResponse`（design §7.1）
- `MockRuleCopilotSummaryProvider`（既定）→ `AiExecutionGateway` → `AiTextService`
- `MockAiResponses`: `[TASK:MANAGEMENT_COPILOT]` → summary + claimKeys のみ
- unknown claim / HTML / 数値再計算 → 拒否。429 / timeout / invalid JSON → summary unavailable、metrics 維持
- `review-ledger.md` にモデル切替手順（provider / artifact / 再評価）を記載

【Commit】`feat(nf08-b1): model-agnostic summary provider`
```

### B2 — Evaluation / adversarial suite

```text
あなたは `ai-management-copilot` の B2 実装 AI です。Task: **B2 Evaluation / adversarial suite**。前提: B1 PASS。

【Objective】
固定匿名 dataset でモデル非依存の品質ゲートを作る。モデル未決定でも pipeline 合格を証明。実モデルは shadow 評価のみ。

【実装】
- `AiOfflineEvaluationService` 拡張、adversarial fixture（scope A/B、prompt injection、catalog 外 SQL 等）
- min segment < 5 は PASS にしない。自動 promotion 禁止。

【Commit】`feat(nf08-b2): copilot adversarial evaluation suite`
```

### M — 統合・Review handoff

```text
あなたは `ai-management-copilot` の M 実装 AI です。Task: **M 統合・production gate・Review handoff**。前提: F1〜B2 PASS。

【Objective】
`verify-like-ci.ps1` 相当、completion matrix 更新、独立 Review へ bundle 引き渡し。PR は作らない。

【実施】
- `review-ledger.md` / `tasks.md` 全 checkbox
- gate 未完なら **CONDITIONAL PASS**、`management-copilot-enabled=false` 維持
- remote Head 確定 → push
- 新規 Review 対話へ [review-conversations.md](review-conversations.md) §R-NF08 を渡す

【Commit】`docs(nf08-m): review handoffとcompletion matrix`
```

## 4. 再開対話

```text
ai-management-copilot を再開してください。専用 worktree `C:\work\ses-manager-pro-ai-management-copilot`、
branch `codex/ai-management-copilot` で、current main、remote Head、`tasks.md`、`review-ledger.md`、
OPEN blocker を再取得し、前回の自己申告を検証してください。完了済み task を再実装せず、
次の未完 task（F1〜M の順）だけを進めてください。モデル未決定でも deterministic core を優先し、
gateway 直結の WIP は F2 pipeline へ書き直してください。
```
