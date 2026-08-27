# scheduled-management-reporting（NF-10）

## 開工判定

- 状態: `DISCOVERY_ONLY / BLOCKED_DG-10`
- Approved report/recipient: `<APPROVED_SCOPE>`（未解決のプレースホルダー）
- Owner: `<OWNER>`（未解決のプレースホルダー）
- 指定 Base: `<BASE_COMMIT>`（未解決のプレースホルダー）
- 指定 Base branch: `<BASE_BRANCH>`（未解決のプレースホルダー）
- 実観測 Base: `main` / `f131f51c50dbfb68ffc8e71878da52947560c80e`
- 実装 branch: `codex/scheduled-management-reporting`
- 専用 worktree: `C:\work\ses-scheduled-management-reporting`

受入後トレーサビリティでは NF-10 が `CANDIDATE`、DG-10 が未決定である。したがって、開始対話の停止条件に従い、既存集計 inventory と sample snapshot spec までを成果物とする。F1 の DDL、アプリケーションコード、画面、生成、配布、運用訓練は着手しない。

## 成果物

- [inventory.md](inventory.md): 既存の指標・経路・正本 service/DTO・cutoff/timezone・scope owner・snapshot・document・recipient/delivery の対応表。
- [sample-snapshot-spec.md](sample-snapshot-spec.md): 実装・migration ではない、契約検討用の仮 snapshot 例。
- [requirements.md](requirements.md): DG-10 決定前の要求ドラフト。
- [design.md](design.md): 正本再利用、時刻、スコープ、状態の設計ドラフト。
- [tasks.md](tasks.md): discovery 完了と DG-10 待ちの実行台帳。
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

## 停止中の境界

DG-10 で少なくとも次を決定する必要がある。

1. 利用者、template/version の管理者、速報/確定の表示・確定条件。
2. snapshot の保持期間、再生成の意味、version 差分表示、section 部分失敗時の配布可否。
3. recipient の型、scope owner の決定方法、preview の承認者、link の期限切れ再認可、delivery channel。

決定後にのみ、承認済み plan/spec/tasks を確定版へ昇格し、F1 以降を開始する。実装対話では PR を作成しない。Review 用に必要な base/head と本ディレクトリの成果物は `completion-matrix.md` に集約する。
