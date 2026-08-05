# Implementation Plan — 承認ワークフロー・内部統制

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T041〜T046はL0〜L3の定向test・直接回帰、T047でL4全量を実行する。
> 共通approval adapter/state machine合流時はL3、昇格条件該当時だけ中間L4とする。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §6「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
> 本specは「状態機械 × 期間 × 金額 × 権限」の四重交差であり、S02と同じ事故構造を持つ。
> **design.md §6.2の金額帯境界を実装前に確定すること。実装中に決めない。**
>
> **Migration**: S07の正式migrationは **V75/V76/V77/V78/V79**。R1.2/R1.3のroute decision sourceは追加のpatch migration **V79.1**が担当する。V75は承認DDL、V76は承認menu、V77はSLA開始時刻、V78はround/participant/version、V79はB1 notification outboxを担当する。V75〜V79は変更せず、V79.1はV79適用後かつV80より前に適用する。
> S09〜S17は **V80〜V88** を予約し、過去migrationの編集・削除・out-of-order適用は行わない。適用済みDBの`flyway_schema_history`はReview Packetで別途照会結果を記録する。

- [x] 0. G7と対象操作inventory
  - **状態**: 完了。成果物は[`operation-inventory.md`](operation-inventory.md)。production codeは変更していない。
  - **実測**: 対象5業務・9操作（見積提出/受注、契約稼動化/単価改定、請求送付/取消、BP支払確定、月次締め/reopen）
    の現endpoint/service/申請field/route/SLA/職務分離を特定し、全行にrequirements ID（R1.1〜R4.1）を付けた。
    `git diff --check` exit 0。
  - **Demo**: 財務/管理者レビュー用に表を提示。G7は`decision-log.md`推奨既定（組織上長→財務/管理者、
    閾値は設定画面で管理）を採用する旨を明記（決定済みではなく既定採用として記録）。
  - **申し送り（F1着手前に決定表へ反映が必要な観測事実）**: (1) 請求送付/取消・BP支払確定は既存
    `assertOpenForUpdate`（月次締めロック）を呼ぶが、見積・契約は呼ばない非対称性がある。
    (2) 単価改定(`revisePrice`)はcontract statusを検証しない唯一の操作。
    (3) BP支払確定のみ行レベルpessimistic lockが無く状態CASのみ。
    (4) 月次締め/reopenは金額を持たず「金額なし」routeに倒す。詳細は`operation-inventory.md` §3。
  - **Objective**: 対象5業務（見積提出/受注、契約稼動化/単価改定、請求送付/取消、BP支払確定、月次締め/reopen）の
    現endpoint・現service・申請field・route・SLA・職務分離が表として確定する。
    以降のadapter実装が「どのmethodを1回だけ呼ぶか」を推測せずに決められる状態にする。
  - **成果物**: 操作、現endpoint/service、申請field、route、SLA、職務分離表。
  - **実装ガイダンス**: production codeを変更しない。対象5業務の既存単件methodを特定し、
    `applyApproved`が委譲する先を1対1で対応付ける。既存の状態機械/金額検証/監査を**再実装しない**前提を確認する。
    G7は`blocking=no`だが、閾値を変える場合は推奨既定を採るか発注者決定を得たことを明記する。
  - **テスト要件**: L0。対象5業務の全endpointが表に存在すること、
    各操作に対応するrequirements IDが付いていること、`git diff --check` exit 0。

- [x] F1. route/request/action/delegation DDL
  - **状態**: 完了。V75で5テーブル(`m_approval_route`/`m_approval_route_step`/`t_approval_request`/
    `t_approval_action`/`t_approval_delegation`)を新設。V1へは追記していない（新規テーブルはCRM V73と
    同じ方針、既存対象5業務テーブルも無変更）。H2は`sql/schema-approval-h2.sql`を追加し
    `application-test.yml`のschema-locationsへ登録。engine core(`ApprovalEngineServiceImpl`)と
    route resolver(`RouteResolverServiceImpl`)を実装。`ActionPermissionResolver.RESOURCE_NAMES`へ
    `approval`を登録し、V75と`permission-group-seed-h2.sql`へ`approval.*`のgroup権限seedを追加
    （営業/HR/マネージャー。CRM-R2-P1-01と同じ罠を踏まないための対応、design §8補足には含めず
    実装必須事項として扱った）。
  - **実測**: `RouteResolverServiceTest` 8/8、`ApprovalEngineServiceTest` 12/12、
    `MigrationScriptIntegrityTest`・`ActionPermissionMatrixTest`・`ActionPermissionResolverTest`・
    `MessageBundleConsistencyTest`（4クラス計51件）全green（回帰、L1〜L3+共有基盤の直接影響範囲）。
    `git diff --check` exit 0。MySQL fresh smokeの assert block は`FlywayMigrationSmokeTest`へ追加済みだが
    Docker未導入のため本環境では未実行（既存の全spec共通の制約、release gateとして継続管理）。
  - **未実装・F2/B1への申し送り**: `target_version`の実値取得・対象entityの`@Version`追加、
    5対象adapterの登録(`ApprovalTargetAdapter`実装)はF2。`escalate()`と`sla_hours`監視はB1。R1.2/R1.3の
    route decision source不足はR4-P1-01でV79.1・A2管理経路・resolver・H2/testまで実装したが、
    実MySQL migrationの適用確認は未達として別gateに残す。
  - **Demo**: `RouteResolverServiceTest`の境界value test群（min-1/min/min+1/max-1/max/max+1）で
    金額帯ちょうどの申請が意図したrouteへ解決されることを自動テストで確認（本番相当の実ブラウザ/curl Demoは
    A1のUI実装後、Mで実施）。route未設定の場合の拒否+管理者通知は`request()`実装内で
    `notifyAdminsOfConfigGap`を呼ぶことをコードレビューで確認済み（管理者宛通知の実送信テストはB1の
    通知重複防止実装と合わせてB1で行う）。
  - **Objective**: 対象操作が直接確定されず申請draftと差分snapshotになる。
    routeが対象種別・組織・金額帯・申請者roleから1件に決まり、決まらない場合は申請が受け付けられず管理者へ通知される。
    申請者自身は自分の申請を承認できない。
  - **実装ガイダンス**: **V78**/V1/H2(`sql/schema-approval-h2.sql`)/MySQL smoke、engine core/CAS。
    **route snapshotは申請時に確定し以後不変**（design §6.1）。
    金額帯はmin/max ともに**inclusive**、判定に`amount_snapshot`（税込）を使う。
    `amount_snapshot IS NULL`を0円として金額帯へ当てない。負の金額は**絶対値**で判定（design §6.2）。
    複数route該当時の決定順は「組織の具体性→金額帯の狭さ→version_noの新しさ」。
  - **テスト要件**: L1〜L3。route解決の**境界fixture `min-1/min/max/max+1`**、
    該当routeなしで申請拒否（既定routeへfallbackしない）、自己承認の拒否、
    並列groupの全員承認/1人却下、代理期間の内外、`version`+`current_step`の複合CAS競合。
  - **Demo**: 金額帯の境界値ちょうどの申請が意図したrouteへ流れることをcurlで確認。
    route未設定の金額帯で申請すると拒否され管理者へ通知が飛ぶことを確認。

- [x] F2. 5 target adapters
  - **Objective**: 見積・契約・請求・BP支払・月次締めの5業務が申請経由でのみ確定し、
    最終承認で既存serviceのmethodが**1回だけ**呼ばれる。
    承認中に対象が変更されていたら競合として再申請を求め、古いsnapshotを適用しない。
  - **実装ガイダンス**: 既存service委譲、version snapshot、idempotency、outbox。
    **最終承認transactionの順序を守る**（design §3/§6.4）:
    request lock → target version再検証 → `applyApproved` → request approved → outbox insert。
    外部API/メール送信はDB transaction外（platform-invariants §3.3）。
    対象側に`UNIQUE(approval_request_id)`を置いて二重適用を構造的に防ぐ。
  - **テスト要件**: L2〜L3。adapterごとに正常/version競合/rollback/再送、
    **二重clickとretryで最終業務操作が1回**、承認transaction rollback時に対象が変わらないこと、
    outboxがcommit後にのみ実行されること。
  - **Demo**: curlで各対象申請→承認。同じ承認リクエストを10回送って業務操作が1回だけ起きることを確認。

- [x] A1. inbox/request/diff/history UI
  - **状態**: 完了。承認inbox、自分の申請一覧、詳細(diff/comment/history/対象link)、申請作成、差戻し後の再申請操作を追加した。
  - **実装**: `ApprovalViewService`でdesign §6.3の applicant/承認者/代理当事者の可視性を統一し、`diff_json`とpayloadをfield単位でmask。原価は`contract.cost.view`、給与は`payroll.view`、口座は`bp-company.view`で判定し、画面とCSV exportが同じDTOを通る。承認/却下/差戻し/取下げ/再申請は既存F1 engineへ委譲し、F1のquorum/CAS/申請者除外/通知処理は変更していない。
  - **権限・CSRF・i18n**: V76で`approval` menuを管理者/営業/HR/マネージャーへ追加。更新APIは既存`SES.api`のX-XSRF-TOKEN方式を使用し、4言語bundleへ同一キーを追加。対象画面リンクは固定allow-listで生成し、コメント/差分値はJSでescapeする。
  - **自動検証**: `ApprovalViewServiceImplTest`（可視性、mask、差戻し再申請）、`ApprovalPageRenderTest`（inbox/requests/detailのThymeleaf実描画）、`ApprovalUiContractTest`（responsive table/390px用markup）、`MessageBundleConsistencyTest`、`ApprovalEngineServiceTest`、`ActionPermissionResolverTest`、`MigrationScriptIntegrityTest`、Node `--check`を実行し全green。
  - **Demo**: MockMvcで3画面の実描画、`table-responsive`、diff/history/export markup、既存F1の差戻し→再申請/quorum/代理/CAS回帰を確認。実ブラウザ390pxの目視確認とMySQL/Docker fresh smokeは本環境では未実施。
  - **テスト要件**: L2〜L3。requester/approver scope、field masking（画面DTOとCSV共通）、差戻し→修正→再申請表示、mobile markupをカバー。

- [x] A2. route/代理管理
  - **状態**: 完了。route version登録・適用期間・approver preview、期間/対象付き代理登録・論理削除、代理監査表示を実装した。
  - **実装**: 既存routeは更新せず新行へversionを採番し、申請時route snapshotを固定する。`applicant_role_condition`で申請者role条件を保存し、role条件routeを汎用routeより優先する。代理は承認操作時点の期間とrequest typeで判定し、本人/代理のslot重複は既存の一意制約で先着1件に抑制する。固定USER、permission group、申請者上長、組織責任者、財務責任者は有効期間・有効ユーザーだけを候補にし、解決不能時は受付を拒否する。責任者assignmentは`t_approval_responsibility`で管理する。
  - **権限・CSRF・i18n**: route/代理管理APIとページを管理者限定にし、更新操作は既存`SES.api`のCSRFヘッダー方式を維持した。4言語bundleと管理者向けsidebarリンクを追加した。
  - **自動検証**: `ApprovalAdministrationServiceTest`（version/snapshot、preview、代理期間開始/終了、監査項目、逆期間、不正USER値）、`RouteResolverServiceTest`（金額境界、未設定、自己承認、組織/帯幅/version優先、無効USER）各全件PASS。関連`ApprovalEngineServiceTest`、`ApprovalPageRenderTest`、`ApprovalUiContractTest`、`MessageBundleConsistencyTest`もPASS。Node `--check`と`git diff --check`もPASS。
  - **Demo/未検証事項**: MockMvc/Thymeleafと定向テストで管理画面・snapshot・代理期間・監査表示を確認した。実ブラウザのdesktop/390px目視、MySQL/Docker fresh migration smoke、mvn全量は未実施。
  - **テスト要件**: L2〜L3。進行中申請のroute snapshot不変、申請後の代理期間開始/終了、解決不能拒否、本人/代理のslot二重承認防止をカバー。
- [x] R4-P1-01. route decision modelとapprover source不足の是正
  - **状態**: 完了。V79.1のfresh/legacy migration、履歴/checksum/FK/CHECK/index assertion、旧checksum validate失敗→forward repair→限定repair→validate、partial再migrate、rollback rehearsalを確認済み。今回`v79_1-fk-actions-forward-repair.sql`を`information_schema`状態判定で再開可能に改修し、DROP後・FK追加後・CHECK追加後の各partial状態から再実行して最終schemaとV79.1限定repair/validateへ収束することを実MySQLで検証した（`FlywayV79_1RepairSmokeTest` **2 / 0 / 0 / 0 / BUILD SUCCESS**）。shared JDBCの複数JVM ShedLock/claim、commit前例外時実DB rollback、Webhook loopbackも実測済み。full application instance cron・外部providerは要件外としてN/A化（§R4確定参照）。
  - **code baseline Head / Review evidence commit / Packet文書provenance**: **code baseline Head**=`68fbbba4dff8255b3a745ce61e73e686a78bef3e`（Base→Head **23 commits / 219 files / +11639/-337**。`68fbbba`時点で`HEAD = origin/main = origin/HEAD`＝当時値）。**初回Review evidence commit**=`2978461be1fd36334a00a97fabe37f5613e374a4`（Base→commit **24 commits / 272 paths**、履歴）と**現行Review evidence/result commit**=`646dbdafb3c6b77ec0e3b7bb581392f50be53491`（Base→commit **27 commits / 274 paths**、seed修正＋browser evidence再生成）はcode baseline Headと分離して管理する。最終確認時worktreeはclean。Packet文書の**独立commitはなく、文書自身のcommitは`git log -1 -- <path>`で解決するprovenance**として記載し、文書commit hashをcurrent Headとして自己参照しない。旧「12ファイルdirty」記述は`68fbbba`でcommit済みとなったため削除した。
  - **実装**: V79.1で`applicant_role_condition`と`t_approval_responsibility`を追加。route管理DTO/API/UI、`PERMISSION_GROUP`、`ORGANIZATION_MANAGER`、`FINANCE_MANAGER`の設定・as-of解決、H2 schemaを同期した。V75〜V79は変更していない。
  - **追加した回帰**: `RouteResolverServiceTest`を13→19件へ拡張し、responsibility `valid_from`/`valid_to` inclusive境界・前後日、組織一致/不一致、FINANCE_MANAGERの組織別/全社assignment、permission groupの無効group・削除membership・disabled/deleted user、3 sourceの候補0件・申請者自身のみ・責任者disabled/deleted userを検証。`ApprovalAdministrationServiceTest`は13件で不正approver type、route/responsibility逆期間、不存在organization、無効user、error code/messageKeyを検証。新規`ApprovalAdministrationApiControllerTest` 5件でHTTP 400/404と`ApiResult.code`を検証した。
  - **追加した回帰**: `RouteResolverServiceTest`を加えてAPPLICANT_MANAGER（`t_user_organization.manager_user_id`）のvalid_from實日inclusive境界、valid_to實日inclusive境界、所属期間外fail-closed、manager_user_id NULL fail-closed、無効manager fail-closed、削除済みmanager fail-closed、FK上直接fixtureできない不存在manager IDについてmapper境界の`null`とNULL assignmentのfail-closed、申請者本人のみmanager候補のfail-closed（職務分離R1.4）、snapshot固定の回帰を追加（19件→28件）。`ApprovalAdministrationApiControllerTest`にresponsibility逆期間（validFrom>validTo）がHTTP 400かつApiResult.code=400になるテストを追加（5件→6件）。
  - **定向/static/direct実測**: R1.3対象計**47 / failures 0 / errors 0 / skipped 0**（`RouteResolverServiceTest` 28、`ApprovalAdministrationServiceTest` 13、`ApprovalAdministrationApiControllerTest` 6）。`MigrationScriptIntegrityTest` 26、`SpecDispatchConsistencyTest` 8、`JsSyntaxCheckTest` 1のstatic計は**35 / 0 / 0 / 0 / BUILD SUCCESS**。R4-P1-01 consumer 7クラス＋B1/M 13クラスの20クラスdirect regressionは**150 / 0 / 0 / 0 / BUILD SUCCESS**。request→DB `route_snapshot_json`再読込→manager変更後のsnapshot/承認者不変も実測済み。
  - **L4相当実測**: Docker `29.6.1`、Node `v24.18.0`を検出したCI相当`verify-like-ci.ps1`が完走し、`mvn -B clean test`は**1471 / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**（01:09 h）。skip確認は**0 test cases / 0 report classes**、script exit **0**。L4 zero-skippedは確認済みで、Maven全量とDocker依存gateは別々に記録する。
  - **V79.1専用実MySQL回帰**: 作業木の`FlywayV79_1RepairSmokeTest`をDocker Server `29.6.1`で実行し、**2 / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**（test execution `1998 s`、Maven total `34:17`）。V79 legacy→V79.1 upgrade、旧checksumによるvalidate失敗、`repair()`単独ではFK/CHECKを変更しないこと、forward DDL→V79.1限定allowlist repair→validate、partial history repair/re-migrate、`clean()`後のV79→V79.1再適用、FK action/CHECK/index/違反拒否を確認した。さらに`v79_1-fk-actions-forward-repair.sql`を`information_schema`状態判定で再開可能に改修し、**DROP後・FK追加後・CHECK追加後の各partial状態からの再実行**で最終schemaとV79.1限定repair/validateへ収束することを実MySQLで検証した（各状態でrunbook2回実行の冪等性も確認）。runbookは`sql/runbook/v79_1-fk-actions-forward-repair.sql`に固定し、V79.1 migration本体は編集していない。
  - **scheduler H2回帰**: production既定の`app.scheduler.lock.use-db-time=true`を維持し、test profileだけ`false`へ切り替えた。`SchedulerLockH2IntegrationTest`のlock取得・同一lock競合・解放後再取得は**1 / 0 / 0 / 0**、既存`TaskNotificationSchedulerH2Test`と`NotificationOutboxSchedulerIntegrationTest`を含む3クラス合計は**3 / 0 / 0 / 0 / BUILD SUCCESS**。`DB time is not supported for unknown database product`例外をtargeted logで再現せず、lock取得失敗を検知するassertを追加した。
  - **operational boundary実MySQL**: `OperationalBoundaryMySqlIntegrationTest`をDocker Server `29.6.1`で実行し、**3 / 0 / 0 / 0 / BUILD SUCCESS**。独立JVMのShedLockは`LOCK_ACQUIRED pid=...`→`LOCK_NOT_ACQUIRED pid=...`→`LOCK_RELEASED`→`LOCK_ACQUIRED pid=...`、outbox claimは`CLAIM_RESULT=1`/`CLAIM_RESULT=0`、DB状態`PROCESSING`、`attempt_count=1`を確認した。adapter後の意図的例外ではapproval action/request/Quotation対象行が実MySQL transactionでrollbackした。
  - **Webhook loopback / browser Demo**: `WebhookNotifierLoopbackIntegrationTest`は**1 / 0 / 0 / 0**で`127.0.0.1`への実JSON POSTを確認した。実Chrome `150.0.7871.187`（Playwright headless）で**5業務×desktop/390pxの10経路**を実測し、申請者単独確定不可・申請→承認→適用・二重click/retryで業務操作1回を確認した（証拠: `evidence/browser-m/`）。外部Webhook providerは要件外N/A（loopback実HTTPで送信経路は実測済み）。
  - **残存gateの判定**: full application instance cron・外部provider相当Webhook endpointは要件外**N/A**（§R4確定参照）。desktop/390pxの5業務browser Demo（10経路）とbrowser管理画面の実測はMで確認済み。B1/Mは`[x]`化済み。残るgateは本Packetの文書整合を対象にした**独立再Review**のみである。

  - **Objective**: R1.2の申請者role条件route、R1.3の5種類のapprover sourceをroute設定からsnapshot解決まで同一契約で実証する。
  - **テスト要件**: role-specific優先/fallback、permission group membership、責任者scope/asOf、APPLICANT_MANAGERの`t_user_organization`期間両端境界/期間外/NULL/無効/削除/0件/self-only/snapshot、候補0件fail-closed、管理APIの不正type/期間をカバーする。実MySQL（V79.1 partial/repair/rollback・runbook再開可能性）、実browser（5業務10経路）、release gateの証拠を確認済みでcheckboxは`[x]`。文書整合の独立再Review待ち。

- [x] B1. 通知/SLA/escalation
  - **状態**: 完了。V79 outboxとround/step/slot対応dedupe key、commit後worker・再送経路、scheduler Demo相当、shared JDBC複数JVM ShedLock/claim、commit前例外時実DB rollback、loopback実HTTP Webhookを確認済み。full application instance cron・外部providerは要件外としてN/A化。
  - **code baseline Head / Review evidence commit / Packet文書provenance**: **code baseline Head**=`68fbbba4dff8255b3a745ce61e73e686a78bef3e`（Base→Head **23 commits / 219 files / +11639/-337**、`68fbbba`時点で`HEAD = origin/main = origin/HEAD`＝当時値、worktree clean）。**初回Review evidence commit**=`2978461be1fd36334a00a97fabe37f5613e374a4`（Base→commit **24 commits / 272 paths**、履歴）と**現行Review evidence/result commit**=`646dbdafb3c6b77ec0e3b7bb581392f50be53491`（Base→commit **27 commits / 274 paths**）はcode baseline Headと分離して管理する。Packet文書の独立commitはなく、文書自身のcommitは`git log -1 -- <path>`で解決するprovenanceとして記載する（current Headとして自己参照しない）。
  - **実装済み**: `ApprovalNotificationKeys`へ申請/承認/差戻し/却下/conflict/SLAの共通key生成を集約し、`requestId + round + step (+ slot)`でラウンド再利用を防止。`ApprovalSlaService`もroundを含むkeyへ統一した。V78は変更せず、V79で`t_notification_outbox`を追加し、通知保存と外部Webhook配信をcommit後worker・再送経路へ分離した。
  - **定向検証**: `ApprovalNotificationSlaTest` 6件、`NotificationServiceImplTest` 9件、`NotificationOutboxDispatcherTest` 5件、`NotificationOutboxServiceTest` 5件の計25件を failures 0 / errors 0 / skipped 0で確認。期限境界、同一超過の重複抑止、宛先限定、NULL SLA、round 1→2のRETURNED/REQUESTED/SLA key分離、outboxのclaim/成功/RETRY/FAILED/重複を含む。
  - **scheduler Demo相当**: `NotificationOutboxSchedulerIntegrationTest` 1件を追加実行し、Webhook未設定のSYSTEM通知をoutboxへ1件投入後、`NotificationOutboxScheduler.dispatchPending()`を2回起動。同一dedupe keyの行が1件のみ、`SENT`、`attempt_count=1`となることを確認（1回目のみdue行を処理し、2回目はdue対象なし）。
  - **広いB1回帰**: `NotificationOutboxDispatcherTest`、`NotificationOutboxServiceTest`、`NotificationServiceImplTest`、`WebhookNotifierTest`、`ApprovalNotificationSlaTest`、`NotificationOutboxSchedulerIntegrationTest`、`ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`の8クラスを再実行し、計47件を failures 0 / errors 0 / skipped 0で確認した。
  - **実環境証拠**: V79/V79.1の実MySQL fresh/legacy適用、`flyway_schema_history`、checksum/FK/CHECK/index assertion、V79.1-specific partial/repair/rollbackを確認済み。`OperationalBoundaryMySqlIntegrationTest`でshared JDBCの複数JVM ShedLock/claimとcommit前例外時の実DB rollback、`WebhookNotifierLoopbackIntegrationTest`で`127.0.0.1` JSON POST、`SchedulerLockH2IntegrationTest`でH2 lock warning回帰 **3/0/0/0** を確認済み。CI相当全量はDocker/Node可用環境で**1471 / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**、script exit **0**。full application instance cron（要件がデプロイ構成を要求しないためN/A）と外部Webhook provider到達（R2.3は呼出タイミング制約であり実provider統合を要求しないためN/A）は、DB境界のShedLock/claimとloopback実HTTPで実測した性質が代替する。
  - **Objective**: 申請・差戻し・承認・却下・期限超過が**対象本人だけ**に届く。
    stepごとのSLA期限を超えると上位責任者へescalateされ、同じ超過で二重に通知されない。
  - **実装ガイダンス**: recipient限定、冪等scheduler、`NotificationLinks`定数を使う。
    `sla_hours IS NULL`は**期限なし**でescalation対象外（design §6.1）。
  - **テスト要件**: L2〜L3。期限境界（超過直前/ちょうど/直後）、
    **同一超過で通知が重複しないこと**、宛先が対象本人に限定されること、`sla_hours IS NULL`が対象外であること。
  - **Demo**: overdueを上位責任者へ通知。schedulerを2回起動して通知が1件のみを確認。

- [x] M. 対象画面統合/回帰
  - **状態**: 完了。実装・定向回帰・CI相当L4・実MySQL smoke・5業務desktop/390px browser Demo（10経路）を確認済み。
  - **code baseline Head / Review evidence commit / Packet文書provenance**: **code baseline Head**=`68fbbba4dff8255b3a745ce61e73e686a78bef3e`（Base→Head **23 commits / 219 files / +11639/-337**、`68fbbba`時点で`HEAD = origin/main = origin/HEAD`＝当時値、worktree clean）。**初回Review evidence commit**=`2978461be1fd36334a00a97fabe37f5613e374a4`（Base→commit **24 commits / 272 paths**、履歴）と**現行Review evidence/result commit**=`646dbdafb3c6b77ec0e3b7bb581392f50be53491`（Base→commit **27 commits / 274 paths**）はcode baseline Headと分離して管理する。Packet文書の独立commitはなく、文書自身のcommitは`git log -1 -- <path>`で解決するprovenanceとして記載する（current Headとして自己参照しない）。
  - **Objective**: 対象5業務の画面が「実行」から「申請」へ変わり、申請者単独では確定できない。
    二重click/retryでも業務操作は1回。既存の5業務の機能が壊れていない。
  - **実装済み**: 見積提出/受注、契約稼動化/単価改定、請求送付/取消、BP支払確定、月次締め/reopenを
    `ApprovalTargetAdapterRegistry`経由の申請へ統合。5 adapter、決定的SHA-256 idempotency key、月次締めの最終承認者監査主体、
    既存DI修正、UI文言・4言語bundle・UI契約を反映した。
  - **定向回帰実測**: `QuotationApiControllerTest` 4件、`ContractApiControllerTest` 12件、
    `ContractPaginationTest` 13件、`InvoiceApiControllerTest` 10件、`ApprovalTargetAdapterTest` 7件の計46件を
    failures 0 / errors 0 / skipped 0で確認した。adapterは既存service委譲、月次締め最終承認者、registry idempotencyを確認した。
  - **全量実測（R4-P1-01回帰追加後のcurrent作業木、2026-08-04）**: `pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-like-ci.ps1`が実行した`mvn -B clean test`は**1471 tests / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**（01:09 h）。Docker `29.6.1`、Node `v24.18.0`を検出し、skip確認は0 test cases / 0 report classes、script exit **0**。L4 zero-skippedは確認済みである。
  - **M定向回帰の再測定**: `QuotationApiControllerTest` 4件、`ContractApiControllerTest` 12件、`ContractPaginationTest` 13件、`InvoiceApiControllerTest` 10件、`ApprovalTargetAdapterTest` 7件の計46件を failures 0 / errors 0 / skipped 0で再確認した。
  - **migration/static回帰**: `MigrationScriptIntegrityTest` 26件、`SpecDispatchConsistencyTest` 8件、`JsSyntaxCheckTest` 1件のstatic計35件、および`FlywayMigrationSmokeTest` 2件を failures 0 / errors 0 / skipped 0で確認した。
  - **MySQL smoke**: Docker `29.6.1`で`FlywayMigrationSmokeTest`を実行し、V79.1のfresh/legacy適用、v79.1到達、`flyway_schema_history`、checksum、FK/CHECK/index assertionを確認した。さらに作業木の`FlywayV79_1RepairSmokeTest`でV79.1-specific partial/repair/rollback（旧checksum validate失敗、forward repair、partial再migrate、clean/re-forward rehearsal）を実MySQLで確認済みである。V79.1 migration本体は編集していない。
  - **browser Demo実測（2026-08-05）**: 実Chrome `150.0.7871.187`（Playwright headless）で**5業務×desktop/390pxの10経路全てPASS**。各経路で（a）申請者単独では対象状態が変わらない、（b）申請→承認→適用で対象状態が1回だけ変わる、（c）申請時の二重click/retryでも申請1件のみ（idempotency key一意制約）、（d）承認時の二重clickでもAPPROVE action 1件・retry後も業務操作は再適用されないことを実browserで確認した。証拠は`evidence/browser-m/`（スクリーンショット40枚＋JSON 11ファイル）。full application instance cron・外部Webhook providerは要件外としてN/A化。V79.1-specific partial/repair/rollback（runbook再開可能性含む）とL4 failure 0・zero-skippedも確認済み。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    5業務のbrowser通し（desktop/390px）、既存Contract/Invoice/BpPayment/Closingの回帰、
    Node/JS syntax、`git diff --check`。
  - **Demo**: 申請者単独確定不可と二重実行0を確認。5業務それぞれで申請→承認→適用を通す。
  - **実装ガイダンス**: `design.md`§6決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。


## R4 baseline B1/M検証追記（履歴: 2026-08-03、current Head `76ffcbb`時点の記録）

- **基準（履歴）**: 検証開始時のreview baselineは`10dc316d003d7070b7b232056d2c17a240274bb8`。local commit予定・未pushという記述は当時の状態であり、current Headのmerge状態ではない。current Headの正本は直下の「R4 current Head correction（2026-08-04）」とreview-ledger/manifest/中央台帳である。
- **B1/M checkbox（当時の記録）**: B1/T046とM/T047は当時`[ ]`だった。現行正本（「R4 current code/evidence Head確定」）で`[x]`化済み。
- **B1/M定向回帰**: `NotificationOutboxDispatcherTest`、`NotificationOutboxServiceTest`、`NotificationServiceImplTest`、`WebhookNotifierTest`、`ApprovalNotificationSlaTest`、`NotificationOutboxSchedulerIntegrationTest`、`ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`、`QuotationApiControllerTest`、`ContractApiControllerTest`、`ContractPaginationTest`、`InvoiceApiControllerTest`、`ApprovalTargetAdapterTest`を現作業木で実行し、**93 tests / failures 0 / errors 0 / skipped 0 / BUILD SUCCESS**を確認した。
- **実MySQL gate**: Docker daemonは再確認時に利用可能だったが、`FlywayMigrationSmokeTest`はTestcontainers/FlywayのMySQL named lock取得で約8分停止した。Surefireの今回実行結果は生成されず、プロセスを停止したため、V79 fresh/legacy/rollback/lockのPASS証拠にはしない。
- **未達gate**: 実MySQL smoke、実Webhook endpoint、複数JVMのShedLock/claim競合、commit前例外時の実DB rollback、desktop/390px browser Demo、CI zero-skipped。mysql CLI、DB接続環境変数、Chrome/Edge/Firefox/Playwright executableも未検出だった。
- **判定（当時の記録）**: B1/Mは当時、実装・定向回帰・scheduler Demo相当まで確認済みだったが、release gate未達のため完了・PASSへ変更しなかった。現行正本でB1/Mは`[x]`化済み。


## R4 current production Head / Packet文書分離（履歴: 2026-08-04、Head `6680e7d`時点の記録）

- **Review対象production Head**: `6680e7d81c7842262a2fd07c57fb9942e80573ce`。実Gitは`HEAD = origin/main = origin/HEAD`、branchは`main`。この確認開始時のproduction worktreeはcleanだった。
- **Base→Review対象production Head**: `5d228d2..6680e7d`は**22 commits / 212 files / +10459/-330**。212 pathsはmanifest §2の#001〜#212へ一意に帰属する。
- **Packet文書provenance**: **独立Packet commitなし。4文書はReview対象production Head `6680e7d81c7842262a2fd07c57fb9942e80573ce`に同梱済み**であり、Head自身をPacket文書commitとして自己参照しない。今回の文書同期、V79.1専用回帰追加、scheduler H2設定・回帰追加後は12ファイル（4資料、V79.1専用test/runbook、scheduler設定・H2回帰test、OperationalBoundary実MySQL回帰、Webhook loopback回帰）がdirtyで、production Headのcommit帰属と分離する。commit/pushは行わない。
- **R4-P1-01回帰**: `RouteResolverServiceTest` 28件、`ApprovalAdministrationServiceTest` 13件、`ApprovalAdministrationApiControllerTest` 6件の計**47 / 0 / 0 / 0**。APPLICANT_MANAGERの不存在manager ID mapper境界と、request→DB snapshot→manager変更後の不変性を確認した。V79.1専用`FlywayV79_1RepairSmokeTest`も**1 / 0 / 0 / 0 / BUILD SUCCESS**で、partial/repair/rollbackを実MySQL確認済み。
- **B1/M checkbox（当時の記録）**: B1/T046とM/T047は当時`[ ]`だった。現行正本（「R4 current code/evidence Head確定」）で`[x]`化済み。full application instance cron・外部providerは要件外N/A、desktop/390px 5業務browserは10経路実測済み。
- **判定（当時の記録）**: B1/T046・M/T047は当時未完了、S07は当時`IN PROGRESS`・総合`NOT REVIEWABLE`だった。現行正本（「R4 current code/evidence Head確定」）でS07=`REVIEW`（独立再Review待ち）、B1/M=`[x]`へ更新済み。


## R4 current code/evidence Head確定（2026-08-05、68fbbba）— 正本

本節が現行の正本である。上のR4 baseline/B1-M追記とR4 current production Headの各節は履歴として保持するが、
現行Head・worktree・判定は本節を優先する。

- **code baseline Head**: `68fbbba4dff8255b3a745ce61e73e686a78bef3e`（`68fbbba`時点で`HEAD = origin/main = origin/HEAD`＝当時値、branch=`main`、最終確認時worktreeは**clean**）。**初回Review evidence commit**: `2978461be1fd36334a00a97fabe37f5613e374a4`（Base→commit **24 commits / 272 paths**、履歴）と**現行Review evidence/result commit**: `646dbdafb3c6b77ec0e3b7bb581392f50be53491`（Base→commit **27 commits / 274 paths**、seed修正＋browser evidence再生成）はcode baseline Headと分離して管理する。e88351d時点の基準はBase→**26 commits / 274 paths / +16873/-342**。現在の文書同期commitは`git log -1 -- <path>`で解決され、68fbbbaのHEAD/origin一致（当時値）と矛盾しない。
- **Base→Head**: `5d228d2..68fbbba`は**23 commits / 219 files / +11639/-337**。219 paths（#001〜#212＋今回の7 unique paths #213〜#219）はmanifest §1.1/§2.10へ一意に帰属する。
- **Packet文書provenance**: **独立Packet commitなし。文書自身のcommitは`git log -1 -- <path>`で解決するprovenanceとして記載し、文書commit hashをcurrent Headとして自己参照しない。**旧「12ファイルdirty」記述は`68fbbba`（回帰test commit）で12ファイルが全てcommit済みとなったため削除した。
- **R4-P1-01回帰**: `RouteResolverServiceTest` 28件、`ApprovalAdministrationServiceTest` 13件、`ApprovalAdministrationApiControllerTest` 6件の計**47 / 0 / 0 / 0**。V79.1専用`FlywayV79_1RepairSmokeTest`は**2 / 0 / 0 / 0 / BUILD SUCCESS**（test execution 1998 s）で、既存のpartial/repair/rollbackに加え、`v79_1-fk-actions-forward-repair.sql`の**DROP後・FK追加後・CHECK追加後**の各partial状態からの再実行（information_schema状態判定による再開可能性）を実MySQLで検証した。
- **B1/M checkbox**: B1/T046・M/T047は**`[x]`**へ更新。shared JDBCの複数JVM ShedLock/claim、commit前例外時rollback、Webhook loopback、H2 lock warning回帰、CI相当L4 **1471/0/0/0・zero-skipped**（今回のpartial-state test 1メソッド追加後は1472件、当該クラス2/0/0/0実測済み）に加え、**5業務desktop/390px browser Demo（10経路）**を実測した。
- **browser Demo**: 実Chrome `150.0.7871.187`で見積提出・契約稼動化・請求送付・BP支払確定・月次締めをdesktop（1440x900）と390px（390x844）で通した。各経路: 申請者単独確定不可（対象状態不変）、申請→承認→適用（対象状態が1回だけ変化）、申請時二重click/retryで申請1件のみ、承認時二重clickでAPPROVE action 1件・retry後も業務操作再適用なし。証拠: `evidence/browser-m/`（スクリーンショット40枚＋JSON 11ファイル）。
- **残存gateのN/A化（「必要な場合」のまま放置しない）**:
  1. **2つのfull application instance cron → N/A**: requirements R2.3/R2.4/R4.2は「外部送信をDB transaction内で行わずcommit後outbox/jobで実行し、再送で二重外部連携を作らない」ことを要求しており、デプロイ構成として2 instanceを要求しない。単一writerはDBレベルの`t_shedlock`＋outbox claim（`REQUIRES_NEW`で直列化）で実現され、`OperationalBoundaryMySqlIntegrationTest`（2 JVM・1共有DB）で`LOCK_NOT_ACQUIRED`/`CLAIM_RESULT=0`を実測済み。2つのfull application instanceは同じDBロック/claim経路を通るため追加性質を検証しない。
  2. **外部provider相当のWebhook endpoint到達 → N/A**: R2.3は外部呼出をtransaction外にする**タイミング制約**であり、特定の実provider統合は要求しない。Webhook URLは`m_system_config`の設定値（未設定時は`SENT`扱い、design §1.2）。実HTTP送信経路は`WebhookNotifierLoopbackIntegrationTest`（RestTemplate→実endpoint）で実測済み。
- **判定**: R4-P1-01・B1・Mを`[x]`へ更新し、review-ledger/manifest/中央台帳を同一判定（技術・機能gate確認済み・REVIEW待ち）へ揃えた。**code baseline Head**=`68fbbba`（23 commits/219 paths）、**初回Review evidence commit**=`2978461`（24 commits/272 paths、履歴）、**現行Review evidence/result commit**=`646dbda`（27 commits/274 paths）と分離して管理する（e88351d時点の基準はBase→26 commits/274 paths/+16873/-342）。現行状態はS07=`REVIEW`、S09=`NOT READY`、Wave 2=`未解放`であり、独立再Reviewで文書整合を確認後、S07 PASS・S09 READY・Wave 2解放へ一括遷移する。独立Reviewは219-path inventory、`evidence/browser-m/`、実MySQL証拠、Head分離を対象に最終確認する。
