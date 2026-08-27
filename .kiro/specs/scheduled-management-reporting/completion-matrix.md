# 完了対応表 / Review handoff ledger

## 状態

`APPROVED / IMPLEMENTED / INDEPENDENT_REVIEW_PENDING`

2026-08-28にNF-10/DG-10が正式承認された。Ownerは管理者（経営管理責任者）、Base branchは`origin/main`、Base policyは再開時fetchの最新`origin/main`。今回の承認Baseは`455fc92e3aa259d2a93f25c6a545ca6c6af835bc`。

## 完了対応表

| Task | 対応要求 | 状態 | 成果物 / 証拠 | commit / remote |
|---|---|---|---|---|
| T0 | 開工境界、通常 checkout 非変更 | 完了 | `README.md`、worktree/branch/status/remote/base の観測値 | `cf6b7f7e` |
| T1 | NF-10 inventory、正本再利用、scope/time/document/outbox/backup 対応 | 完了 | `inventory.md`、`requirements.md`、`design.md` | `86826538` |
| T2 | sample snapshot spec | 完了 | `sample-snapshot-spec.md`、actual/forecast、cutoff、timezone、freshness、scope、source hash、section status | `86826538` |
| T3 | 最新Base取り込みとapproved plan/spec/tasks昇格 | 完了 | `origin/main@455fc92e3aa259d2a93f25c6a545ca6c6af835bc`、中央traceability、承認済みspec | `a86af3f30f89feff28e88bf4dda5e10974852cdd` |
| F1 | template/version/schedule/run/snapshot/delivery DDL | 完了 | `V112__scheduled_management_reporting.sql`、H2 schema、6 entity/mapper | `9b342c79d8495ce52e81d1c2a862d603f3b8581a`。compile成功、AttendanceSchemaTest 6/6 |
| F2 | snapshot orchestration | 完了 | `ReportSnapshotServiceImpl`、`ReportRecipientPreviewServiceImpl`、明示system principal | `fde702a1`、snapshot targeted test 4/4、合同gate 199/199 |
| A1 | template/preview/run UI | 完了 | `ManagementReportApiController`、`management-reports/index.html`、静的role境界 | `fde702a1`、`MobileResponsiveLayoutTest` 29/29 |
| B1 | document | 完了 | `ReportDocumentServiceImpl`、PDF/XLSX/CSV renderer、DocumentService登録API | `b36f91a7`、`ReportDocumentServiceImplTest` 2/2 |
| B2 | delivery | 完了 | `ManagementReportScheduler`、schedule CAS、delivery token/scope/reauth/retry API、notification outbox接続 | `0e0d4d50` / `87d055a4`、delivery 6/6、scheduler 2/2 |
| M | test / restore / drill / base-head evidence | 完了 | contract/month-end/timezone、double-start/retry/DLQ、scope change、restore/rollback、responsive、required-gate結果 | `879c1ce7` / `c0634abf`、下記M証跡 |

## Review に渡すもの

- 実装対象 branch: `codex/scheduled-management-reporting`
- 専用 worktree: `C:\work\ses-scheduled-management-reporting`
- 通常 checkout: `C:\work\ses-manager-pro`（実装中の変更なし）
- 観測 base: `origin/main@455fc92e3aa259d2a93f25c6a545ca6c6af835bc`
- 承認Base: `455fc92e3aa259d2a93f25c6a545ca6c6af835bc`
- 取り込みcommit: `a86af3f30f89feff28e88bf4dda5e10974852cdd`
- 最終remote Head: `c33bff464aa3d5dbbcee2599672573d5de409ad3`
- Review 入力: 本ディレクトリの承認済みrequirements/design/tasks、inventory、sample snapshot
- Review 判定: 実装対話ではPRを作成しない。上記のapproved plan/spec/tasksと本completion matrixを独立Reviewへ渡し、PLAN/IMPLEMENTATIONの双方PASS後だけPR作成可否を判断する。

## Plan self-review

内部Plan self-reviewはPASS。approved scopeの利用者・組織scope・対象10 section・NF-02までのServiceDesk除外、速報/確定とcutoff、Asia/Tokyo、7年保持、immutable snapshot/version、recipient previewと生成/download再認可、outbox/link/re-auth、system principal、部分失敗時配布停止、backup/restore rollbackがrequirements/design/tasksと実装証跡で矛盾なく対応している。report独自SQL・集計式・丸め、HTTP session依存scheduler、メール添付は追加していない。

## M証跡

- contract/shape: `ReportSnapshotServiceImplTest` 4/4、`ReportDeliveryServiceImplTest` 6/6、`ManagementReportSchedulerTest` 2/2、`ReportDocumentServiceImplTest` 2/2、`ActionPermissionResolverTest` 11/11、`AllMappersSchemaSweepTest` 174/174、合同targeted gate 199/199。
- 月末/timezone: `2026-08-01..31` と `Asia/Tokyo`、速報 `GENERATED_AT`、確定 `MONTHLY_CLOSING`、未締め確定拒否を検証。
- snapshot/retry/regeneration: 同一run retryは成功sectionを再生成せず、明示regenerationは新version/runと親runを作る。section failureは`PARTIAL`/配布停止。
- scheduler/delivery: ShedLock＋DB CASの二重claim、preview hash、scope変更download拒否、期限切れlink、再認証、attempt 5のDLQ、manual replay、notification dedupeを検証。
- document/backup: PDF/XLSX/CSVは同一snapshot入力、backup integrationのrestore、validate-restore、target marker、cutover rollback、RPO/RTO (`rpo_ok=true`, `rto_ok=true`)、secret scan 0を確認。integration evidence SHAは `9061b3f8db3cee80a6de668e15d33010b84b960de843b66696dae6ab69aadccd`（summary）、`2f1236b02aa37d4b2a64de84f372f53841e393c4d99f57ecdc6039aa075cb5a0`（validate）、`a140975fb1e305debab2d2c04da4135749fc61587572b2824d1116ab62c1deb1`（restore）。
- UI/performance: `MobileResponsiveLayoutTest` 29/29、performance 1/1 (`p95=68ms`)。専用browser testはJava 21のloopback制約でTomcat起動前に失敗し、desktop/390px screenshotは未生成。既存loopback系を含むfull fast/MySQL実行は同じ環境制約で赤となったが、feature-specific failuresは0、V112 MySQL smokeは5/5。
- backup unit: preflight 59、quiesce/lock/uploads 45、full backup/manifest 36、binlog 61、watermark 29、restore flow 31、restore plan 44、restore validation 32、cutover/rollback 31、retention 55、target guard 22、harness 8（各 `failures=0`）。

## Rollback

追加migrationはreport専用の新規table/menu/document typeのみで、scheduleは初期無効。rollback時はscheduleを無効化し、outboxを停止・手動replay対象として保全し、document/snapshotは削除せずbackup restoreとcutover rollbackで復旧する。
