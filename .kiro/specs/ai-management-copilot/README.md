# ai-management-copilot（NF-08）

## 状態

| 項目 | 値 |
|---|---|
| Central NF-08 | `CANDIDATE`（Discovery spec 完了。実装は F1 以降） |
| Plan Review | **CONDITIONAL PASS**（2026-09-01 独立Review。owner/`<APPROVED_SCOPE>`/DG-08/NF-07 未決） |
| Implementation | **NOT STARTED** |
| 本番外部 AI | **不可**（`ai.external-send-enabled=false`、`GATE-S17-G10-PROD` 保留） |
| 具体モデル | **未決定**（Gemini/OpenAI/Claude 等）。pipeline はモデル非依存で先に構築する |
| branch | `codex/ai-management-copilot` |
| worktree | `C:\work\ses-manager-pro-ai-management-copilot` |

## 対話の正本（SNF01〜10 横断 Review 用）

| 用途 | 正本ファイル | 中央 roadmap からの参照 |
|---|---|---|
| 実装・開工（F1〜M） | [start-conversations.md](start-conversations.md) | `2026-08-27-post-acceptance-start-conversations.md` §S-NF08 は本書へ委譲 |
| 独立 Review（Plan / Task / 最終） | [review-conversations.md](review-conversations.md) | `2026-08-27-post-acceptance-review-conversations.md` §R-NF08 は本書へ委譲 |

**SNF01〜10 を一括 Review する場合**: 各 NF の中央 §R-NF0X は入口のみ。NF-08 の詳細観点・task 別 Review・モデル切替契約は **本 spec の `review-conversations.md` を正**とする。横断 Review AI は本 README と `review-ledger.md` の completion matrix を必ず読む。

## 成果物

| ファイル | 内容 |
|---|---|
| [requirements.md](requirements.md) | 要件 AI-MC-R1〜R10 |
| [design.md](design.md) | 固定 pipeline、catalog、scope、provider 境界 |
| [tasks.md](tasks.md) | T000 / F1 / F2 / A1 / B1 / B2 / M |
| [review-ledger.md](review-ledger.md) | gate 表、task 証跡、Review 判定 |
| [start-conversations.md](start-conversations.md) | 開工対話集（モデル未決定・役割分離を明記） |
| [review-conversations.md](review-conversations.md) | 独立 Review 対話集 |

## AI の役割分離（モデル未決定でも固定）

```text
Deterministic core（モデル不要・必須）
  Intent → Catalog → TypedParameter → Scope → CanonicalService → TypedResult

Pluggable summary layer（B1。モデルは AiTextService で差し替え）
  Redacted claimKeys のみ → AiExecutionGateway → AiTextService（ai.provider / ai.model）
```

数値・scope・期間の正本は Java service。LLM は説明文（summary）のみ。モデル変更は設定と artifact version の更新で行い、pipeline を変えない。

## 読了した根拠資料

- `AGENTS.md`
- `.kiro/roadmap/2026-08-27-post-acceptance-{requirements-design,feature-backlog,traceability,start-conversations,review-conversations}.md`
- `.kiro/specs/ai-feedback-learning/`（gateway、G10 allowlist、run/feedback/evaluation）
- `AiExecutionGateway`、`DataScopeService`
- 正本 service: `DashboardService`、`UtilizationForecastService`、`ManagementAccountingService`、`CashFlowForecastService`、`SalesPerformanceService`

## 実装境界（provisional）

- catalog 候補は requirements §AI-MC-R3 に列挙。`<APPROVED_SCOPE>` 確定前は **provisional** とし、registry では `enabled=false` を既定とする。
- `sales-performance.monthly` は `SalesPerformanceService` の DataScope 統合完了まで **常に disabled**。
- ローカル WIP（`CopilotApiController` 直結 gateway 等）は spec pipeline に合わせて **F2 で書き直す**。そのまま push しない。
