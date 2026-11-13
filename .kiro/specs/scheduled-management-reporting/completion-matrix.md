# 完了対応表 / Review handoff ledger

## 状態

`APPROVED / IMPLEMENTED / INDEPENDENT_REVIEW_PENDING`

Review remediation（`codex/nf10-review-remediation`）: delivery TX/outbox/previewHash/system path の再検証中。**COMPLETE にはしない。**

2026-08-28にNF-10/DG-10が正式承認された。Ownerは管理者（経営管理責任者）、Base branchは`origin/main`、Base policyは再開時fetchの最新`origin/main`。今回の承認Baseは`455fc92e3aa259d2a93f25c6a545ca6c6af835bc`。

## Remediation（2026-09-07）

| 項目 | 内容 |
|---|---|
| トランザクション分割 | 短TX（claim/issue）→ TX外で文書生成/storage → 短TX（download記録） |
| 単一recipient発行 | `ReportDeliveryIssueService` が delivery + outbox を同一 `@Transactional` で束ねる |
| ユーザー配布 | `deliverUser(runId, requiredPreviewHash)` — missing/blank/stale は fail-closed |
| scheduler配布 | `deliverScheduled(runId, ReportScheduledDeliveryContext)` — null hash 禁止 |
| 検証 | `ReportDeliveryTransactionIntegrationTest`（実Spring proxy）、`ManagementReportDeliveryApiControllerTest` |

## 完了対応表

| Task | 対応要求 | 状態 | 成果物 / 証拠 | commit / remote |
|---|---|---|---|---|
| T0 | 開工境界、通常 checkout 非変更 | 完了 | `README.md`、worktree/branch/status/remote/base の観測値 | `cf6b7f7e` |
| T1 | NF-10 inventory、正本再利用、scope/time/document/outbox/backup 対応 | 完了 | `inventory.md`、`requirements.md`、`design.md` | `86826538` |
| T2 | sample snapshot spec | 完了 | `sample-snapshot-spec.md`、actual/forecast、cutoff、timezone、freshness、scope、source hash、section status | `86826538` |
| T3 | 最新Base取り込みとapproved plan/spec/tasks昇格 | 完了 | `origin/main@455fc92e3aa259d2a93f25c6a545ca6c6af835bc`、中央traceability、承認済みspec | `a86af3f30f89feff28e88bf4dda5e10974852cdd` |
| F1 | template/version/schedule/run/snapshot/delivery DDL | 完了 | `V112`/`V113`/`V114`、H2 schema、7 entity/mapper | `9b342c79` / `b4b8c3b1` / `e6a9ea27`。AllMappersSchemaSweepTest 175/175、MySQL Flyway smoke latest=114 |
| F2 | snapshot orchestration | 完了 | `ReportSnapshotServiceImpl`、`ReportRecipientPreviewServiceImpl`、保存scope、明示system principal、再生成version、生成直前の現在entity scope再解決、現在日付での再認可、append-only section attempt監査 | `fde702a1` / `573cf60b` / `19f1aacb` / `b4b8c3b1` / `88cc779f`、snapshot 7/7、recipient scope 2/2、合同gate 256/256 |
| A1 | template/preview/run UI | 完了 | `ManagementReportApiController`、`management-reports/index.html`、draft version作成/編集/公開、静的role境界、preview hash必須、画面ID契約 | `fde702a1` / `c7d73a43` / `88cc779f`、`MobileResponsiveLayoutTest` 29/29、`ReportTemplateServiceImplTest` 2/2 |
| B1 | document | 完了 | `ReportDocumentServiceImpl`、PDF/XLSX/CSV renderer、DocumentService登録API | `b36f91a7`、`ReportDocumentServiceImplTest` 3/3 |
| B2 | delivery | 完了 | `ManagementReportScheduler`、schedule CAS/cron、生成直前のcurrent scope再解決、delivery token/scope/reauth/retry API、delivery preview経路、outbox idと`ENQUEUED`状態、dispatcher結果同期、既存outbox再利用retry/DLQ manual replay、汎用Document経路遮断、非RETRY再配布抑止 | `0e0d4d50` / `87d055a4` / `573cf60b` / `75490e79` / `95818da1` / `b925a1fc` / `b4b8c3b1` / `88cc779f`、delivery 13/13、dispatcher 5/5、scheduler 3/3、schedule 3/3、FileScope 6/6 |
| M | test / restore / drill / base-head evidence | 完了 | contract/month-end/timezone、double-start/retry/DLQ、scope relocation/recipient change、restore/rollback、responsive、required-gate結果、独立Review前のP1再検証 | `879c1ce7` / `c0634abf` / `445ce5f1` / `573cf60b` / `75490e79` / `95818da1` / `19f1aacb` / `b925a1fc` / `c7d73a43` / `b4b8c3b1` / `88cc779f`、下記M証跡 |

## Review に渡すもの

- 実装対象 branch: `codex/scheduled-management-reporting`
- 専用 worktree: `C:\work\ses-scheduled-management-reporting`
- 通常 checkout: `C:\work\ses-manager-pro`（実装中の変更なし）
- 観測 base: `origin/main@455fc92e3aa259d2a93f25c6a545ca6c6af835bc`
- 承認Base: `455fc92e3aa259d2a93f25c6a545ca6c6af835bc`
- 取り込みcommit: `a86af3f30f89feff28e88bf4dda5e10974852cdd`
- 実装fix milestone remote Head: `58ae7eaa`（Flyway smoke latest=114、processing列assert、CANCELLED再配布、管理者限定取消UI）。
- Review 入力: 本ディレクトリの承認済みrequirements/design/tasks、inventory、sample snapshot
- Review 判定: 実装対話ではPRを作成しない。上記のapproved plan/spec/tasksと本completion matrixを独立Reviewへ渡し、PLAN/IMPLEMENTATIONの双方PASS後だけPR作成可否を判断する。

## Plan self-review

内部Plan self-reviewはPASS。2026-08-28独立Review IMPLEMENTATION FAILのP1指摘（取消API、sidebar/配信UI、確定稼働率のUtilizationCalcService、schedule processing lease、指標contract test、deliver TXとdocument分離）を修正済み。V114でprocessing leaseを追加し、claim成功時のみnext_run_atを進める。`AccountingTimezoneResolver`経由のtenant時刻、4bundle `menu.managementReport`、run履歴API/UIを追加した。

## M証跡

- contract/shape: `ReportSnapshotServiceImplTest` 7/7、`ReportRecipientPreviewServiceImplTest` 2/2、`ReportDeliveryServiceImplTest` 13/13、`FileScopeValidationServiceTest` 6/6、`NotificationOutboxDispatcherTest` 5/5、`NotificationServiceImplTest` 9/9、`ManagementReportSchedulerTest` 3/3、`ReportScheduleServiceImplTest` 3/3、`ReportTemplateServiceImplTest` 2/2、`ReportDocumentServiceImplTest` 3/3、`CashFlowForecastServiceTest` 14/14、`ActionPermissionResolverTest` 11/11、`AllMappersSchemaSweepTest` 175/175、`FlywaySelfServiceSchemaSmokeTest` 3/3、合同targeted gate 256/256（Cash Flow scope、current entity scope再解決、preview hash必須、retry due claim、section attempt append-only、delivery outbox同期、既存outbox retry/manual replay、recipient scope包含、別manager download、汎用Document経路拒否、ENQUEUED/PROCESSING/PENDING/SENT重複配布拒否、画面/正本DTO/全形式exportのmetric契約を含む）。全件 failure/error/skipped=0。
- 月末/timezone: `2026-08-01..31` と `Asia/Tokyo`、速報 `GENERATED_AT`、確定 `MONTHLY_CLOSING`、未締め確定拒否を検証。
- snapshot/retry/regeneration: 同一run retryは成功sectionを再生成せず、明示regenerationは新version/runと親runを作り、schedule初回実行は保存cronの次回発火時刻から開始する。section failureは`PARTIAL`/配布停止。
- scheduler/delivery: ShedLock＋DB CASの二重claim、preview hash、scope変更download拒否、期限切れlink、再認証、attempt 5のDLQ、manual replay、notification dedupeを検証。delivery previewは保存済みdelivery tokenを必須とし、enqueue直後は`ENQUEUED`、outbox dispatcherのSENT/RETRY/FAILEDをdeliveryへ同期する。section retryはV113のappend-only attemptへ保持する。
- document/backup: PDF/XLSX/CSVは同一snapshot入力、backup integrationのrestore、validate-restore、target marker、cutover rollback、RPO/RTO (`rpo_ok=true`, `rto_ok=true`)、secret scan 0を確認。NF-10固有restore smokeで`t_report_run` snapshot version/scope hash、section snapshot/append-only attempt、Document/DocumentVersion hash/version、outbox/delivery linkを復元後に検証した。integration evidence SHAは `c31a8fea8bc6e94267c03326bb5fd9990292b2201bddf619210ff49cdd4b9aaa`（summary）、`d783cf369c8bfa8812a57d6d870c5512b5709db6c8fd504c36c0194970aa3afe`（validate）、`e119f02f146a35ef582dce7f3e9541602b1b5d6a0051b8091ee6c804cc6627cd`（restore）、`2c0a0061ae9f2a492429c5da4d14d68440b7ba13a299bbfd5569a466fee47ef1`（NF-10 report restore contract）。
- UI/performance: `MobileResponsiveLayoutTest` 30/30、`ReportTemplateServiceImplTest` 2/2、performance 1/1（直近証跡`p95=64ms`）。専用browser testはJava 21のloopback制約でTomcat起動前に失敗し、desktop/390px screenshotは未生成。feature-specific failuresは0、V112–V114 MySQL Flyway smokeはlatest=114で5/5。
- backup unit: preflight 59、quiesce/lock/uploads 45、full backup/manifest 36、binlog 61、watermark 29、restore flow 31、restore plan 44、restore validation 32、cutover/rollback 31、retention 55、target guard 22、harness 8（各 `failures=0`）。

## Rollback

追加migrationはreport専用の新規table/menu/document typeのみで、scheduleは初期無効。rollback時はscheduleを無効化し、outboxを停止・手動replay対象として保全し、document/snapshotは削除せずbackup restoreとcutover rollbackで復旧する。

## PRD-NF10-001（2026-10-01）

状態: 実装修正・定向検証完了、独立Review待ち。共有deliveryの`reauthenticated_at`をdownload認可根拠から外し、server-side session / Authentication instance / tenant / user / delivery / current scope / 10分TTLに束縛したgrantを追加した。token/delivery/link expiry/recipient/current scope/snapshotの既存service検証は維持。session rotation/logout、期限、tenant/user/delivery/scope境界、role authority差異、anonymous経路と実controller→serviceの二session経路を回帰へ追加した。証拠は`docs/review/NF10-reauth-session-boundary-evidence.md`。Head=`1443fb68943849a3260d7ebe005259f3eb609d60`＋未commit実装（固定commit hash追記待ち）。

初回有効targeted実行は37/0/0/0、zero skip。その後変更したgrant helper classは4/0/0/0、zero skipで単独再実行。合わせた対象範囲は38 checksでfailures/errors/skipsなし。四つの初回Surefire text reportと最終helper `.txt/.xml` は証拠ディレクトリに保存した。独立Review後もfull fast gateはrootの統合スケジュールで実施する。

## NF10-V185 scheduler claim fence（2026-11-12）

状態: **実装済・`821076c0` の定向fast 292/0/0/0・定向MySQL 41/0/0/0・browser 1/0/0/0（PowerShell環境）通過・統合候補 `328c2d8b` へ取込済。** COMPLETE/PASSではない（下表「本番適用の前提」のSRE/DBA承認・Owner判断と、統合候補に対する全gateが未了）。

`93c11457c17e7f082d2773500e8c47de2d50ed95` に対し、独立レビュー（静的）PASS、定向fast 9 class 288/0/0/0、定向MySQL 7 class 41件中1件FAIL（errors/skipped 0）。FAILはtestの行lock待ち観測方法の欠陥と判断して修正した（plan §13）。証拠は主控のrun記録 `nf10-93c11457-fast-targeted` / `nf10-93c11457-mysql-targeted`。

修正commit `821076c0066119b035e3a7a1a84bdc3170341521` は独立レビュー（静的、第3回）PASS。主控Runnerの定向実行は、fast 10 class 292/0/0/0（上記9 class＋`AssetComprehensiveSecretScanTest`）、MySQL 7 class 41/0/0/0（`ReportScheduleClaimFenceMySqlTest` 17/0/0/0、前回FAILのcaseを含む）、browser `ManagementReportBrowserMTest` 1/0/0/0（PowerShell環境）で、いずれもzero skip。Bash tool環境のbrowser実行はNIO loopbackの環境要因で起動不能となり、その記録は環境FAILとして保持している。証拠は主控のrun記録 `nf10-821076c0-fast-targeted` / `nf10-821076c0-mysql-targeted` / `nf10-821076c0-browser-targeted-ps`。`821076c0` は `8dcdbe28` で統合候補へ取り込まれ、`328c2d8b` に含まれる。統合候補に対する全gate（`verify-like-ci`・CI）の通過は本記録の対象外で、未確認である。

| 項目 | 内容 |
|---|---|
| 基準 | `73aa70004484db96ffd9100ffa13dd7acaaf365e`（PR #118候補）。branch `codex/snf-nf10-v185-integration-20261112` |
| migration | `V185__report_schedule_claim_token.sql`（`processing_claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL`、列位置指定なし）。V185はユーザー承認済みの採番で、使い捨てのTestcontainers DB以外の環境には未適用 |
| 実装 | claim token付き全列CAS、token・claimedAt・logicalRunAt一致のcompletion、per-scheduleの秒精度時刻、孤児token回収、実行中retry leaseのLIMIT枠除外、`updateEnabled` の限定更新、lease列の `FieldStrategy.NEVER`、確定的claim SQL失敗の分類 |
| 計画・対応表 | `scheduler-claim-fence-plan.md` §9〜§13（独立レビュー指摘ごとの対応、§7受入試験とtestの対応、定向MySQL FAILの診断） |
| 追加test（`93c11457` で定向実行、1件FAILを修正した `821076c0` で定向fast/MySQL/browserが全件通過） | `ReportScheduleClaimFenceMySqlTest`（`@Tag("mysql")`、`scripts/test-suites/mysql-shard-1.txt`）、`ReportScheduleApiBoundaryTest`、`ReportScheduleMapperH2SqlTest`、`ManagementReportSchedulerTest`/`ReportScheduleServiceImplTest` の追加case、`FlywaySelfServiceSchemaSmokeTest` の列定義assert |
| 本番適用の前提 | rolling禁止・drain先行・検出SQL・復旧案は草案（plan §10、**SRE/DBA承認待ち**）。lease延長・retry上限・timezone変更・metric/alert・起動guardはOwner判断待ち（plan §11）。2026-11-13にユーザーが初回導入を空DBへの新規導入・全停止配備・専用Flyway jobによるmigration単独実行と決定したが、drain手順の確定とSRE/DBA承認は未了 |

## NF10 W2-B8 scheduler運用方針（2026-11-13）

状態: **実装済・r2はテスト未実行（主控Runnerの定向実行待ち）。** COMPLETE/PASSではない。上表「本番適用の前提」のうち、retry上限・metric/alert・起動guardはユーザー決定（B-NF10-1/B-NF10-2）を受けて実装した。lease延長は「行わない」と決定し、重複はrun_key・delivery dedupeで収束させる。§10の手順草案のSRE/DBA承認、alertの設定・配信先、Report Owner署名は未完了のまま。

- r1 `30e058ce1916af52193bdbf4f7fa430513d2a8ae`: 独立レビュー（静的）PASS（P2×1、P3×12）。Runner定向 fast 324/0/0/0、MySQL 23/0/0/0（`ReportScheduleDispatchPolicyMySqlTest` は別seed 20261113でも4/0/0/0）。証拠は主控のrun記録 `nf10-30e058ce-*`。
- r2: review P2-1（cutoffの論理run固定、ユーザー決定）、P3-1・P3-3・P3-8の修正と文書訂正。r2のtestは未実行。

| 項目 | 内容 |
|---|---|
| 基準 | `1836eb73fec57421d2acc9503e3e84fe1f267856`。branch `codex/snf-w2-nf10-scheduler-policy-20261113` |
| 起動guard（A-NF10-3） | token無しlease（legacy lease）が1件でもあれば全tenantのclaimを止める（fail-closed）。運用者がdrainを確認した時刻 `management-report.legacy-lease-drain-confirmed-before`（既定は空）より前、かつ本instance起動前に取得されたleaseだけを期限後に引き継ぐ。それ以降のleaseと、解釈できない設定値は止める。検出の失敗も停止（plan §14.1） |
| retry上限（A-NF10-4 / P2-4） | 初回を含む連続12回目の失敗で `enabled = 0`・`last_error_code = SCHEDULE_RETRY_EXHAUSTED` を所有権CASと同じUPDATEで保存。停止理由はschedule一覧APIで確認でき、管理者の有効化で連続失敗回数を0へ戻して同一論理runから再開する。同一論理runで確定的に失敗する原因の復旧手順（新schedule作成等）とpoison pillの限界を記載。DDL不要（plan §14.2） |
| 確定的SQL失敗（P3-3） | 当該行だけを見送り、同tenantの残り候補は続行。見送った行は待機（5分×回数、上限60分）の間due一覧から除外し、LIMIT枠を占有させない（plan §14.3） |
| 重複配布（A-NF10-5 / P2-1） | lease延長はしない。定期runのrun_keyはschedule・template版・対象月・scopeで作りcutoffを含めないため、同一論理runのcutoffは最初のrunに固定される。前提はrecipient previewとtemplate版が同一論理runの間で変わらないこと。重複実行・締めを跨ぐ重複実行・8並行投入で配布が1回に収束することを実MySQLで検証する（plan §14.4） |
| metric/alert（P2-8） | `ses_report_schedule_events_total{event}`（14種）と `ses_report_schedule_legacy_leases`（gaugeの古い値に注意）。推奨alert ruleは案のみ（plan §14.5） |
| 機能スイッチ（A-NF10-6） | 既存のNF10スイッチを使い、新しいスイッチは追加しない。OFF前に投入済みの通知outboxは汎用schedulerが送る隙間が残る（plan §14.6） |
| 追加・更新test（r2は未実行） | `ReportScheduleDispatchPolicyMySqlTest`（新規、`@Tag("mysql")`、`scripts/test-suites/mysql-shard-2.txt`、5件）、`ManagementReportSchedulerTest`（r1で1件置換・5件追加、r2で4件追加）、`ReportSnapshotServiceImplTest`（r2で2件追加）、`ReportScheduleMapperH2SqlTest`（2件追加）、`ReportScheduleApiBoundaryTest`（1件追加） |
| 変更しないもの | DDL、`messages*.properties`、`application*.yml`、機能スイッチ実装。新設定はコード既定値（空＝未確認）で動き、`application.yml` への明示は提案のみ |
| 残課題 | schedule一覧画面での停止理由表示（画面自体が未実装）、論理runのskip API（未実装の方針のみ）、引き継ぎ回数の上限（DDLが要るため方針のみ）、alert ruleの設定、並行時の文書artifact重複（UNKNOWN）、P2-3のtimezone変更時の再計算 |
