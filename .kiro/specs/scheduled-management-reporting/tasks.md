# 実行台帳（NF-10 / DG-10 承認済み）

## Discovery

- [x] T0: 専用 worktree、branch、base、status、remote を検証する。
  - Objective: 通常 checkout を変更しない開始証拠を残す。
  - Evidence: `README.md` の開工判定。通常 checkout は `main`、専用 branch は `codex/scheduled-management-reporting`。
  - Demo: `git worktree list`、`git status --short --branch`、`git remote -v`。
- [x] T1: NF-10/DG-10、platform invariants、既存正本 service/DTO/API/UI/export、DocumentService、outbox、backup/recovery を読了し inventory を作成する。
  - Objective: 集計式を再実装せず、正本と制約を対応付ける。
  - Evidence: `inventory.md`。
  - Demo: section ごとに正本、cutoff/timezone、scope owner、snapshot、document、delivery を追跡できる。
- [x] T2: 仮の sample snapshot spec を作成し、画面/export/report 共通契約、immutability、freshness、partial failure、recipient guard を示す。
  - Objective: DG-10 の議論用の最小契約を用意する。
  - Evidence: `sample-snapshot-spec.md`。
  - Demo: JSON 例に actual/forecast、cutoff、timezone、data freshness、scope、source hash、section status が存在する。
- [x] T3: 承認済みscope、DG-10、Baseをrequirements/design/tasksへ反映し、Plan self-reviewで矛盾を解消する。
  - Objective: F1開始前に実装境界と受入条件を固定する。
  - Evidence: `README.md`、`requirements.md`、`design.md`、`completion-matrix.md`、中央traceability。
  - Demo: schedule権限、actual/forecast、7年保持、retry不変性、partial/failed配布停止、recipient scope、outbox/link/re-auth、ServiceDesk除外が一貫して記載されている。

## Implementation

- [x] F1: template/version/schedule/run/snapshot/delivery の DDL。最新migration+1、V1/H2同期、shape test、7年保持を実装する。
  - Evidence: `V112__scheduled_management_reporting.sql`、`V113__scheduled_management_report_audit.sql`、`schema-scheduled-management-reporting-h2.sql`、7 entity/mapper、test schema location。
  - Demo: `AttendanceSchemaTest` 6件全緑、compile成功。MySQL migration smokeはMで実施する。
- [x] F2: explicit system principal/scope の snapshot orchestration。管理者/マネージャーscope、速報/確定、retry不変性、partial/failed停止を実装する。
  - Objective: 正本service/DTOをadapter経由で呼び、scope・cutoff・freshness・hashとともにimmutable section snapshotへ固定する。
  - Evidence: `ReportSnapshotServiceImpl`、`ReportRecipientPreviewServiceImpl`、`ReportSectionAttempt`。run/section一意キー、通常retry再利用、明示再生成時の新version、新規run、部分失敗時配布停止状態、section attempt append-only監査を実装。
  - Demo: template versionのrecipient preview hashを生成APIへ渡し、Asia/Tokyoのperiod/dataAsOfとsection statusを表示する。
- [x] A1: template/preview/run UI。管理者有効化、recipient preview、actual/forecast、dataAsOf/freshness、Asia/Tokyoを表示する。
  - Objective: 管理者/マネージャーがtemplate/version、recipient preview、速報/確定runを操作できる画面/APIを提供する。
  - Evidence: `ManagementReportApiController`、`ManagementReportPageController`、`management-reports/index.html`、`management-reports.js`、`SecurityConfig`の静的role境界。
  - Demo: `/management-reports`で公開済みversion、対象月、cutoffを選択し、preview hashを経由してrunとsection snapshotを表示する。
- [x] B1: PDF/XLSX/CSV と DocumentService 登録。同一snapshot、hash/version/CLEAN、7年保持、scope/access auditを実装する。
  - Objective: 成功済みsnapshotのみを共通入力とし、PDF/XLSX/CSVを生成してDocumentServiceのscan/hash/version/retention経路へ登録する。
  - Evidence: `ReportDocumentServiceImpl`、`ReportDocumentArtifact`、document API。XLSX/CSVのformula injection対策と生成サイズ上限を含む。
  - Demo: 同一runの3形式を生成し、各artifact hash、Document version、`MANAGEMENT_REPORT`の月末transaction dateを確認する。
- [x] B2: schedule、outbox、link/re-auth、retry、DLQ/manual replay。アプリ内通知＋期限付きlink、生成/download scope、再認証を実装する。
  - Objective: 管理者有効化のscheduleをShedLock＋DB CASで実行し、system principalからsnapshot生成・DocumentService登録・recipient scope再確認・通知outbox配布まで接続する。
  - Evidence: `ReportScheduleServiceImpl`、`ReportScheduleMapper`、`ManagementReportScheduler`、`ReportDeliveryServiceImpl`、delivery API、`NotificationOutboxDispatcher`。保存済みscopeとcronをscheduleへ固定し、tokenはhashのみ保存、期限7日、download前password再認証10分、権限・組織scopeを再検証、deliveryを`ENQUEUED`からoutbox dispatch結果へ同期し、retry/DLQ/manual replayを実装。
  - Demo: 同一scheduleのCAS二重claim、PARTIAL run配布停止、期限切れlink拒否、再認証後のdownload、notification dedupeをテストする。
- [x] M: contract test、月末境界、desktop/390px、restore、配布障害訓練、base/head 証拠。required gatesをskip 0で実施する。
  - Objective: 同一immutable snapshot契約、月末・Asia/Tokyo境界、二重起動・retry・DLQ、recipient scope変更、document restore、画面responsive、backup/recoveryの受入証拠を固定する。
  - Evidence: `ReportSnapshotServiceImplTest` 6/6、`ReportDeliveryServiceImplTest` 6/6、`NotificationOutboxDispatcherTest` 5/5、`NotificationServiceImplTest` 9/9、`ManagementReportSchedulerTest` 3/3、`ReportScheduleServiceImplTest` 3/3、`ReportDocumentServiceImplTest` 2/2、`CashFlowForecastServiceTest` 14/14、`ActionPermissionResolverTest` 11/11、`AllMappersSchemaSweepTest` 175/175、合同targeted gate 234/234、`MobileResponsiveLayoutTest` 29/29、MySQL V112/V113 smoke 5/5、performance 1/1 (p95=64ms)。backup unit各suiteはfailures=0、backup integrationはskip 0でSUCCESS（RPO/RTO、restore、cutover rollback、secret scanを含む）。
  - Boundary/incident evidence: 月末 `2026-08-01..31`、Asia/Tokyo、速報の `GENERATED_AT` cutoff、確定の月次締め拒否、同一run retry再利用、明示regenerationの親run・snapshot version記録、scope変更download拒否、delivery attempt 5のDLQ、manual replay、期限切れlink拒否、recipient preview hashを検証した。schedule初回next_run_atは保存cronの次回発火時刻を使う。
  - Demo: `/management-reports` のdesktop/390px DOM/responsive検証は `MobileResponsiveLayoutTest` 29/29。専用browser testはJava 21のloopback制約でTomcat起動前に再現失敗し、スクリーンショットは生成されなかったため、Mの環境制約として記録する。document restoreはbackup integrationのrestore/validate-restoreとcutover rollbackで証明する。V113のfresh/legacy MySQL migration smokeは5/5。

各完了taskは独立commitしてremoteへpushし、completion matrixへBase/Head、テスト、Demo、rollbackを記録する。実装対話ではPRを作成しない。
