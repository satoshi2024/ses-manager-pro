# AI Management Copilot — 独立 Review 対話集

> **正本**: 本ファイルが NF-08 独立 Review の唯一の詳細入口である。
> 中央 `2026-08-27-post-acceptance-review-conversations.md` §R-NF08 は要約と PR フローのみ。
> 実装対話は [start-conversations.md](start-conversations.md) を使用する。Review AI は原則 read-only。

## 1. 使い分け

| 段階 | 対話 | タイミング |
|---|---|---|
| Stage A — Plan Review | 本書 §2 R-Plan | Discovery spec 完了時（実装前）。**実装開始の gate** |
| 増分 Implementation Review（任意） | 本書 §3 R-F1〜R-B2 | 各 task commit 後。大きな差分の早期検出用 |
| Stage B — 最終 Implementation Review | 本書 §4 R-NF08 | M 完了・remote Head 固定後。**PR 作成の gate** |
| SNF01〜10 横断 Review | 本書 §5 | 全 NF 個別 PASS 後の統合確認。NF-08 詳細は本 spec を正とする |

**PR 作成条件**（中央 roadmap §0 と同一）: 専用 Review worktree、`PLAN PASS` + `IMPLEMENTATION PASS`、remote Head 一致、`gh` で PR 作成/更新のみ（merge しない）。

## 2. R-Plan — Plan Review（Discovery / spec 完了時）

以下を**実装対話とは別の新規 Review 対話**へコピーする。

---

これは `ai-management-copilot` の **Plan Review**（Stage A）です。file 変更は禁止です。

【Review 対象】
- `.kiro/specs/ai-management-copilot/{README,requirements,design,tasks,review-ledger}.md`
- 中央 NF-08 / DG-08: `.kiro/roadmap/2026-08-27-post-acceptance-{requirements-design,traceability}.md`
- Base: `origin/main@<BASE_COMMIT>`、Head: `origin/codex/ai-management-copilot@<HEAD_COMMIT>`
- production code 差分があれば Plan 時点では **FAIL**（Discovery は spec のみが正）

【専用 Review worktree】
`git fetch origin --prune` 後、`origin/codex/ai-management-copilot` の固定 Head を checkout。
通常 checkout・実装 worktree は使用しない。

【Plan Review 観点】
1. `<APPROVED_SCOPE>` / `<OWNER>` / DG-08 / NF-07 を推測で埋めず、CANDIDATE として停止できているか。
2. catalog runtime が SQL / table / column / 任意 bean を実行できない設計か。
3. 固定 pipeline（intent→catalog→parameter→scope→service→typed result→summary→citation）が requirements / design / tasks で一貫しているか。
4. **モデル未決定**を前提に、deterministic core と pluggable summary 層が分離されているか。
5. `SalesPerformanceService` の DataScope gap と `sales-performance.monthly` disabled が明記されているか。
6. PII allowlist、raw prompt 0 日、mock/rule、external flag OFF、G10 gate 保留が一貫しているか。
7. scope A/B、prompt injection、metric contract、provider failure の test 計画があるか。
8. `start-conversations.md` / `review-conversations.md` が存在し、中央 S-NF08 / R-NF08 から委譲されているか。

【判定】
- `PLAN PASS` / `PLAN CONDITIONAL PASS` / `PLAN FAIL`
- CONDITIONAL: owner / approved scope 未決だが実装停止条件は明確（F1 着手可とするかは発注者判断を ledger に記録）
- FAIL: pipeline 欠落、LLM 直結設計、gate 迂回、中央文書との矛盾

Plan FAIL の場合は PR を作らず実装対話へ返す。Plan PASS 後のみ F1 実装または Stage B へ。

---

## 3. 増分 Task Review（R-F1〜R-B2、任意）

各 task の commit 後、早期に safety / scope を確認したい場合に使用する。最終判定は §4 R-NF08 が正。

### R-F1 — catalog / run / feedback

```text
NF-08 F1 増分 Review。Base=<task開始commit>、Head=<F1完了commit>。file変更禁止。
diff + `review-ledger.md` F1 行を読み、catalog 外拒否、disabled entry、MANAGEMENT_COPILOT useCase、
run metadata（catalogVersion/parameterHash/modelVersion）、外部 egress なし、raw prompt 非保存を確認。
P0/P1/P2 と F1 単体 PASS/FAIL を出力。PR は作らない。
```

### R-F2 — gateway / typed result

```text
NF-08 F2 増分 Review。重点: 正本 service 呼出し（LLM で指標を代替していないか）、
TypedResultEnvelope、Scope A/B、metric contract（画面/export 一致）、
sales-performance disabled、WIP gateway 直結の撤去。
P0/P1/P2、query catalog coverage 表、FAIL なら実装対話へ。
```

### R-A1 — UI / citation

```text
NF-08 A1 増分 Review。metrics からの render、summary parse 禁止、citation 再認可、
CSRF、flag OFF、390px。PR なし。
```

### R-B1 — summary provider / モデル境界

```text
NF-08 B1 増分 Review。重点: CopilotSummaryService のみが AiTextService を知る、
F2 pipeline 不変、mock/rule egress なし、429/timeout/invalid JSON、claimKey 検証、
モデル切替手順が ledger にあるか。具体モデル名のハードコードがないか。
```

### R-B2 — evaluation

```text
NF-08 B2 増分 Review。adversarial fixture、min segment、PII/scope leak、
モデル未決定でも pipeline baseline が緑であること。自動 promotion 禁止。
```

## 4. R-NF08 — 最終独立 Review（Stage A + B、PR gate）

M 完了後、以下を**新規 Review 対話**へコピーする。中央 roadmap §R-NF08 の詳細版。

---

これは `ai-management-copilot` の独立 **AI safety / data scope Review** です。
Base=<BASE_COMMIT>、Head=<HEAD_COMMIT>。

file 変更は禁止。AGENTS.md、NF-08/DG-08、本 spec 一式、AI allow-list/gateway/evaluation、
semantic catalog、各正本集計 service、DataScope、provider 契約 gate、diff を読んでください。

【Worktree】
専用 Review worktree で `origin/codex/ai-management-copilot` の固定 Head を Review。
通常 checkout / 実装 worktree は禁止。`git fetch` 後 remote Head 一致を確認。

【Review 順序】
1. **Stage A — Plan Review**: §2 R-Plan と同等（spec / tasks / ledger の完遂性）。未了なら PLAN FAIL。
2. **Stage B — Implementation Review**: 以下の重点観点で code / test / Demo を検証。
3. **PLAN PASS かつ IMPLEMENTATION PASS** のときのみ `gh` で PR 作成/更新（merge しない）。

【重点 Review — safety / pipeline】
- LLM 生成 SQL / table / column / service 名の任意実行経路が存在しないか。
- intent→catalog→typed parameter→scope→service→typed result→summary→citation の各境界。
- **deterministic core と summary 層の分離**。adapter / gateway にモデル依存が漏れていないか。
- 画面 / export / AI 値の口径、円/割合/期間/timezone/freshness/confirmed/forecast/NULL/0。
- source link と個票再認可。回答文 / error / log / run / feedback から scope 外 ID / PII の推測。
- prompt injection、DB 本文の instruction 化、巨大 result、token/cost limit、provider retention/越境 gate。
- model / prompt / catalog / data version、latency / cost、feedback / outcome、回答再現性。
- AI 回答が業務状態を自動更新せず、command 候補が確認/承認境界を通るか。
- mock/rule/real provider の feature flag と本番 gate。

【独立 test 確認】
- tenant/scope A/B、catalog 外質問、SQL injection 風、文書内 prompt injection。
- 0/NULL/forecast、同じ指標の画面/export/AI contract 一致。
- 429/timeout/invalid JSON/partial citation、PII canary egress/log scan。

【モデル未決定の扱い】
- 具体モデル未選定は **本番ブロッカー** とするが、**IMPLEMENTATION FAIL 理由にはしない**（pipeline + mock/rule が PASS なら CONDITIONAL PASS 可）。
- `AiTextService` / `ai.provider` / artifact `modelVersion` の差し替え点と再評価手順が `review-ledger.md` にあること。

【出力必須】
1. P0/P1/P2（finding ID 形式: `nf08-R-<severity>-<nn>`）
2. query catalog coverage 表（enabled / disabled / contract test 有無）
3. metric 一致表（画面正本 vs AI typed result）
4. PII/provider gate 表（G10、NF-07、DG-08、external-send、feature flag）
5. 総合判定: PLAN / IMPLEMENTATION / 総合（PASS / CONDITIONAL PASS / FAIL）
6. **本番 AI 有効化可否**（management copilot + 外部 provider）
7. PR 作成した場合は URL。FAIL なら実装対話への返却事項。

---

## 5. SNF01〜10 横断 Review 時の NF-08 チェックリスト

全 NF（NF-01〜NF-10）を一括 Review する対話では、各 NF の中央 §R-NF0X を入口とし、
**NF-08 の詳細は本ファイル §4 と spec `review-ledger.md` を正**とする。横断 Review AI は次を確認する。

| # | 横断観点 | NF-08 証跡の読み方 |
|---|---|---|
| 1 | 既存 AI gateway との競合 | `MANAGEMENT_COPILOT` useCase が `AiExecutionGateway` 経由のみ。直結 HTTP を増やしていないか |
| 2 | 正本 service 口径 | Dashboard / UtilizationForecast / ManagementAccounting / CashFlow と **同一** service を AI が呼ぶか。NF-10 定期レポートと metric 定義が矛盾しないか |
| 3 | DataScope / 組織 scope | NF-08 `CopilotScopeResolver` が `DataScopeService` と NF-02/NF-10 と同じ母集団か |
| 4 | PII / retention | NF-07 と G10 allowlist。raw prompt 0 日。copilot 独自 allowlist を作っていないか |
| 5 | 本番 gate | `GATE-S17-G10-PROD`、DG-08、NF-07 が他 NF と同時に未完了でも、NF-08 だけ flag ON していないか |
| 6 | モデル / provider | 具体モデル未決定でも pipeline が完成しているか。他 NF の AI 機能と `ai.provider` 設定が矛盾しないか |
| 7 | 実装状態 | `review-ledger.md` completion matrix。Discovery のみなら NF-08 は **横断 PASS に含めない**（NOT READY） |

横断 Review で NF-08 が「spec のみ・実装なし」の場合: **NF-08 は NOT READY** とし、他 NF の PASS を阻害しない（dependency 表に従う）。
NF-08 実装完了後は §4 R-NF08 の総合判定を横断表へ転記する。

## 6. 再 Review 対話

```text
ai-management-copilot の再 Review を再開します。OPEN issue、fix commit delta、
direct regression、変更された catalog/scope/provider 境界だけを確認してください。
closed issue を新証拠なしに再開しないでください。remote Head を再 fetch し、
review 済み Head と一致しない場合は停止して Head 再確定を要求してください。
Plan Review 済みかつ Implementation 差分のみの場合は Stage B から再開してよい。
```
