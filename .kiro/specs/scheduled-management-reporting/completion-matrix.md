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
| F1 | template/version/schedule/run/snapshot/delivery DDL | 完了 | `V112__scheduled_management_reporting.sql`、`V113__scheduled_management_report_audit.sql`、H2 schema、7 entity/mapper | `9b342c79d8495ce52e81d1c2a862d603f3b8581a` / `b4b8c3b1`。compile成功、AllMappersSchemaSweepTest 175/175、fresh/legacy MySQL migration smoke 5/5 |
| F2 | snapshot orchestration | 完了 | `ReportSnapshotServiceImpl`、`ReportRecipientPreviewServiceImpl`、保存scope、明示system principal、再生成version、現在日付での再認可、append-only section attempt監査 | `fde702a1` / `573cf60b` / `19f1aacb` / `b4b8c3b1`、snapshot 6/6、recipient scope 2/2、合同gate 246/246 |
| A1 | template/preview/run UI | 完了 | `ManagementReportApiController`、`management-reports/index.html`、静的role境界、preview hash必須、画面ID契約 | `fde702a1` / `c7d73a43`、`MobileResponsiveLayoutTest` 29/29 |
| B1 | document | 完了 | `ReportDocumentServiceImpl`、PDF/XLSX/CSV renderer、DocumentService登録API | `b36f91a7`、`ReportDocumentServiceImplTest` 3/3 |
| B2 | delivery | 完了 | `ManagementReportScheduler`、schedule CAS/cron、保存scope、delivery token/scope/reauth/retry API、delivery preview経路、outbox idと`ENQUEUED`状態、dispatcher結果同期、retry due claim、汎用Document経路遮断、ENQUEUED/PROCESSING再配布抑止 | `0e0d4d50` / `87d055a4` / `573cf60b` / `75490e79` / `95818da1` / `b925a1fc` / `b4b8c3b1`、delivery 8/8、dispatcher 5/5、scheduler 3/3、schedule 3/3、FileScope 6/6 |
| M | test / restore / drill / base-head evidence | 完了 | contract/month-end/timezone、double-start/retry/DLQ、scope change、restore/rollback、responsive、required-gate結果、独立Review前のP1再検証 | `879c1ce7` / `c0634abf` / `445ce5f1` / `573cf60b` / `75490e79` / `95818da1` / `19f1aacb` / `b925a1fc` / `c7d73a43` / `b4b8c3b1`、下記M証跡 |

## Review に渡すもの

- 実装対象 branch: `codex/scheduled-management-reporting`
- 専用 worktree: `C:\work\ses-scheduled-management-reporting`
- 通常 checkout: `C:\work\ses-manager-pro`（実装中の変更なし）
- 観測 base: `origin/main@455fc92e3aa259d2a93f25c6a545ca6c6af835bc`
- 承認Base: `455fc92e3aa259d2a93f25c6a545ca6c6af835bc`
- 取り込みcommit: `a86af3f30f89feff28e88bf4dda5e10974852cdd`
- 最終remote Head: 最終fix commitをpush後に `git ls-remote origin refs/heads/codex/scheduled-management-reporting` で観測し、最終応答へ転記する（この行はhandoff直前に実測値へ置換）。
- Review 入力: 本ディレクトリの承認済みrequirements/design/tasks、inventory、sample snapshot
- Review 判定: 実装対話ではPRを作成しない。上記のapproved plan/spec/tasksと本completion matrixを独立Reviewへ渡し、PLAN/IMPLEMENTATIONの双方PASS後だけPR作成可否を判断する。

## Plan self-review

内部Plan self-reviewはPASS。approved scopeの利用者・組織scope・対象10 section・NF-02までのServiceDesk除外、速報/確定とcutoff、Asia/Tokyo、7年保持、immutable snapshot/version、recipient previewと生成/download再認可、outbox/link/re-auth、system principal、部分失敗時配布停止、backup/restore rollbackがrequirements/design/tasksと実装証跡で矛盾なく対応している。前回独立Reviewのscope越境、target month、保存scope、cron/retry、明示再生成version指摘を修正し、今回のP1指摘（run直結preview経路、section attempt上書き監査、outbox送信状態の早期SENT判定、recipient scope包含方向、汎用Document経路、ENQUEUED再配布）も、delivery専用preview、V113 append-only attempt、`ENQUEUED`＋dispatcher同期、owner scope ⊆ recipient scope、MANAGEMENT_REPORT汎用経路拒否へ修正した。再自己ReviewでPASSを確認した。report独自SQL・集計式・丸め、HTTP session依存scheduler、メール添付は追加していない。

## M証跡

- contract/shape: `ReportSnapshotServiceImplTest` 6/6、`ReportRecipientPreviewServiceImplTest` 2/2、`ReportDeliveryServiceImplTest` 8/8、`FileScopeValidationServiceTest` 6/6、`NotificationOutboxDispatcherTest` 5/5、`NotificationServiceImplTest` 9/9、`ManagementReportSchedulerTest` 3/3、`ReportScheduleServiceImplTest` 3/3、`ReportDocumentServiceImplTest` 3/3、`CashFlowForecastServiceTest` 14/14、`ActionPermissionResolverTest` 11/11、`AllMappersSchemaSweepTest` 175/175、`FlywaySelfServiceSchemaSmokeTest` 3/3、合同targeted gate 248/248（Cash Flow scope、preview hash必須、retry due claim、section attempt append-only、delivery outbox同期、recipient scope包含、汎用Document経路拒否、ENQUEUED/PROCESSING重複配布拒否、画面/全形式exportのvalueJson契約を含む）。全件 failure/error/skipped=0。
- 月末/timezone: `2026-08-01..31` と `Asia/Tokyo`、速報 `GENERATED_AT`、確定 `MONTHLY_CLOSING`、未締め確定拒否を検証。
- snapshot/retry/regeneration: 同一run retryは成功sectionを再生成せず、明示regenerationは新version/runと親runを作り、schedule初回実行は保存cronの次回発火時刻から開始する。section failureは`PARTIAL`/配布停止。
- scheduler/delivery: ShedLock＋DB CASの二重claim、preview hash、scope変更download拒否、期限切れlink、再認証、attempt 5のDLQ、manual replay、notification dedupeを検証。delivery previewは保存済みdelivery tokenを必須とし、enqueue直後は`ENQUEUED`、outbox dispatcherのSENT/RETRY/FAILEDをdeliveryへ同期する。section retryはV113のappend-only attemptへ保持する。
- document/backup: PDF/XLSX/CSVは同一snapshot入力、backup integrationのrestore、validate-restore、target marker、cutover rollback、RPO/RTO (`rpo_ok=true`, `rto_ok=true`)、secret scan 0を確認。NF-10固有restore smokeで`t_report_run` snapshot version/scope hash、section snapshot/append-only attempt、Document/DocumentVersion hash/version、outbox/delivery linkを復元後に検証した。integration evidence SHAは `c31a8fea8bc6e94267c03326bb5fd9990292b2201bddf619210ff49cdd4b9aaa`（summary）、`d783cf369c8bfa8812a57d6d870c5512b5709db6c8fd504c36c0194970aa3afe`（validate）、`e119f02f146a35ef582dce7f3e9541602b1b5d6a0051b8091ee6c804cc6627cd`（restore）、`2c0a0061ae9f2a492429c5da4d14d68440b7ba13a299bbfd5569a466fee47ef1`（NF-10 report restore contract）。
- UI/performance: `MobileResponsiveLayoutTest` 29/29、performance 1/1（直近証跡`p95=64ms`）。専用browser testはJava 21のloopback制約でTomcat起動前に失敗し、desktop/390px screenshotは未生成。既存loopback系を含むfull fast/MySQL実行は同じ環境制約で赤となったが、feature-specific failuresは0、V112/V113 MySQL smokeは5/5。画面のpreview/run DOM ID契約は`managementReportApp`、`reportPreviewBtn`、`runResult`に統一済み。
- backup unit: preflight 59、quiesce/lock/uploads 45、full backup/manifest 36、binlog 61、watermark 29、restore flow 31、restore plan 44、restore validation 32、cutover/rollback 31、retention 55、target guard 22、harness 8（各 `failures=0`）。

## Rollback

追加migrationはreport専用の新規table/menu/document typeのみで、scheduleは初期無効。rollback時はscheduleを無効化し、outboxを停止・手動replay対象として保全し、document/snapshotは削除せずbackup restoreとcutover rollbackで復旧する。
