# scheduled-management-reporting（NF-10）

## 開工判定

- 状態: `APPROVED / IMPLEMENTED / INDEPENDENT_REVIEW_PENDING`
- Approved report/recipient: 管理者（全社）およびマネージャー（許可された組織scope）
- Owner: 管理者（経営管理責任者）
- Base policy: 再開時にfetchした最新 `origin/main`
- 実観測 Base: `origin/main` / `455fc92e3aa259d2a93f25c6a545ca6c6af835bc`
- 実装 branch: `codex/scheduled-management-reporting`
- 専用 worktree: `C:\work\ses-scheduled-management-reporting`
- 最終remote Head: handoff時の `git ls-remote` 観測値を最終証拠とし、completion matrixと実装対話の最終応答へ転記する。

NF-10/DG-10 は2026-08-28に承認済みである。月次reportの対象は売上、粗利、売上予測、稼働率、Bench、管理会計、Cash Flow、AR aging、BP支払予定、契約終了・更新見込みとし、NF-02 PASSまではServiceDesk/SLAを含めない。timezoneは`Asia/Tokyo`、snapshot/document保持は7年、PDF/XLSX/CSVは同一immutable snapshotから生成する。

## 成果物

- [inventory.md](inventory.md): 既存の指標・経路・正本 service/DTO・cutoff/timezone・scope owner・snapshot・document・recipient/delivery の対応表。
- [sample-snapshot-spec.md](sample-snapshot-spec.md): 実装・migration ではない、契約検討用の仮 snapshot 例。
- [requirements.md](requirements.md): 承認済み要求。
- [design.md](design.md): 承認済み設計と正本再利用、時刻、スコープ、状態の規約。
- [tasks.md](tasks.md): 承認済みの実行台帳。
- [completion-matrix.md](completion-matrix.md): 完了対応表と Review 引き渡し条件。

## 読了した根拠資料

- `AGENTS.md`
- `.kiro/roadmap/2026-08-27-post-acceptance-requirements-design.md`
- `.kiro/roadmap/2026-08-27-post-acceptance-feature-backlog.md`
- `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md`
- `.kiro/specs/customer-product-expansion-2026/platform-invariants.md`
- Dashboard / RevenueForecast 相当 / CashFlow / ManagementAccounting / UtilizationForecast / SalesPerformance / AR の正本 service、DTO、API、画面、export
- `DocumentService` / `DocumentServiceImpl`
- notification service / outbox / dispatcher / scheduler
- `ops/backup/` の README、restore、cutover、failure、health、key rotation runbook

`src/main` に ServiceDesk/SLA の正本実装は存在しない。NF-02 が受入・PASS するまで ServiceDesk section を実装対象に含めない。

## 承認済み実装境界

- 管理者は全社、マネージャーは許可された組織scopeを対象とする。schedule有効化は管理者のみ。
- 速報は未締めデータとして`dataAsOf`/freshnessを表示し、確定版は月次締め完了後のみ生成する。
- template変更・現在DB値・現在権限変更で過去runを変化させない。明示的な再生成は新version、通常retryは同一runの同一snapshotを再利用する。
- section失敗時は`PARTIAL`/`FAILED`として配布停止する。
- 配布はnotification outbox経由のアプリ内通知＋期限付きlinkのみ。メール添付は使用しない。recipient previewを生成前に必須とし、生成時/download時のscope検証、期限切れ時の再認可、download時再認証を行う。
- schedulerは明示system principalを使用し、HTTP sessionに依存しない。既存正本service/DTOを利用し、report独自SQL・集計式・丸めを作らない。

承認済みplan/spec/tasksへ昇格済みであり、F1/F2/A1/B1/B2/Mを実装・検証済みである。実装対話ではPRを作成しない。Review用のbase/head、task対応、テスト証拠は`completion-matrix.md`に集約する。Java 21 loopback制約により全面fast/MySQL/browserの一部既存HTTPテストは環境エラーとなるため、feature-specific green、targeted MySQL smoke、responsive DOM、backup/recoveryの証跡と分離して記録する。
