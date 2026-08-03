# Implementation Plan — 承認ワークフロー・内部統制

> **分層test方針**（正本: `test-execution-policy-s03-s17.md`）: T041〜T046はL0〜L3の定向test・直接回帰、T047でL4全量を実行する。
> 共通approval adapter/state machine合流時はL3、昇格条件該当時だけ中間L4とする。
>
> **既定解**: `customer-product-expansion-2026/platform-invariants.md` を実装前に読む。
> 時間/scope/状態の判断は `design.md` §6「決定表」を正とし、そこに無い論点はplatform-invariantsの既定解に従う。
> 本specは「状態機械 × 期間 × 金額 × 権限」の四重交差であり、S02と同じ事故構造を持つ。
> **design.md §6.2の金額帯境界を実装前に確定すること。実装中に決めない。**
>
> **Migration**: 本specの予約番号は **V78**。BP(V70/V71)とCRM(V73/V74)のmerge後に着手する。V72は永久欠番。
> 着手時にmerge済み`db/migration`の最新を再確認し、衝突していれば後発を上へ繰り上げる。V59は永久欠番。

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
  - **未実装・F2/A2/B1への申し送り**: `target_version`の実値取得・対象entityの`@Version`追加、
    5対象adapterの登録(`ApprovalTargetAdapter`実装)はF2。`permission group`/`組織責任者`/`財務責任者`の
    個別解決方式、routeのversion編集UIはA2。`escalate()`と`sla_hours`監視はB1。詳細はdesign.md §8。
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
  - **実装**: 既存routeは更新せず新行へversionを採番し、申請時route snapshotを固定する。代理は承認操作時点の期間とrequest typeで判定し、本人/代理のslot重複は既存の一意制約で先着1件に抑制する。固定USERと申請者上長は有効ユーザーだけを候補にし、解決不能時は受付を拒否する。
  - **権限・CSRF・i18n**: route/代理管理APIとページを管理者限定にし、更新操作は既存`SES.api`のCSRFヘッダー方式を維持した。4言語bundleと管理者向けsidebarリンクを追加した。
  - **自動検証**: `ApprovalAdministrationServiceTest`（version/snapshot、preview、代理期間開始/終了、監査項目、逆期間、不正USER値）、`RouteResolverServiceTest`（金額境界、未設定、自己承認、組織/帯幅/version優先、無効USER）各全件PASS。関連`ApprovalEngineServiceTest`、`ApprovalPageRenderTest`、`ApprovalUiContractTest`、`MessageBundleConsistencyTest`もPASS。Node `--check`と`git diff --check`もPASS。
  - **Demo/未検証事項**: MockMvc/Thymeleafと定向テストで管理画面・snapshot・代理期間・監査表示を確認した。実ブラウザのdesktop/390px目視、MySQL/Docker fresh migration smoke、mvn全量は未実施。
  - **テスト要件**: L2〜L3。進行中申請のroute snapshot不変、申請後の代理期間開始/終了、解決不能拒否、本人/代理のslot二重承認防止をカバー。
- [ ] B1. 通知/SLA/escalation
  - **状態**: 実装中（V79 outboxとround/step/slot対応dedupe keyを追加済み。Demo/release gate未達のため完了扱いにしない）。
  - **実装済み**: `ApprovalNotificationKeys`へ申請/承認/差戻し/却下/conflict/SLAの共通key生成を集約し、`requestId + round + step (+ slot)`でラウンド再利用を防止。`ApprovalSlaService`もroundを含むkeyへ統一した。V78は変更せず、V79で`t_notification_outbox`を追加し、通知保存と外部Webhook配信をcommit後worker・再送経路へ分離した。
  - **定向検証**: `ApprovalNotificationSlaTest` 6件、`NotificationServiceImplTest` 9件、`NotificationOutboxDispatcherTest` 5件、`NotificationOutboxServiceTest` 5件の計25件を failures 0 / errors 0 / skipped 0で確認。期限境界、同一超過の重複抑止、宛先限定、NULL SLA、round 1→2のRETURNED/REQUESTED/SLA key分離、outboxのclaim/成功/RETRY/FAILED/重複を含む。
  - **scheduler Demo相当**: `NotificationOutboxSchedulerIntegrationTest` 1件を追加実行し、Webhook未設定のSYSTEM通知をoutboxへ1件投入後、`NotificationOutboxScheduler.dispatchPending()`を2回起動。同一dedupe keyの行が1件のみ、`SENT`、`attempt_count=1`となることを確認（1回目のみdue行を処理し、2回目はdue対象なし）。
  - **広いB1回帰**: `NotificationOutboxDispatcherTest`、`NotificationOutboxServiceTest`、`NotificationServiceImplTest`、`WebhookNotifierTest`、`ApprovalNotificationSlaTest`、`NotificationOutboxSchedulerIntegrationTest`、`ApprovalEngineServiceTest`、`ApprovalEngineConflictTest`の8クラスを再実行し、計47件を failures 0 / errors 0 / skipped 0で確認した。
  - **未検証**: 実MySQLのV79適用・rollback/lock、複数JVMのShedLock/claim競合、実Webhook endpoint、commit前失敗時の実DBrollbackはDocker/接続情報不足のため未確認。CI相当全量も`MobileResponsiveLayoutTest` 1件とDocker依存skip 12件が残るため、B1はrelease gate未達としてPASS扱いにしない。
  - **Objective**: 申請・差戻し・承認・却下・期限超過が**対象本人だけ**に届く。
    stepごとのSLA期限を超えると上位責任者へescalateされ、同じ超過で二重に通知されない。
  - **実装ガイダンス**: recipient限定、冪等scheduler、`NotificationLinks`定数を使う。
    `sla_hours IS NULL`は**期限なし**でescalation対象外（design §6.1）。
  - **テスト要件**: L2〜L3。期限境界（超過直前/ちょうど/直後）、
    **同一超過で通知が重複しないこと**、宛先が対象本人に限定されること、`sla_hours IS NULL`が対象外であること。
  - **Demo**: overdueを上位責任者へ通知。schedulerを2回起動して通知が1件のみを確認。

- [ ] M. 対象画面統合/回帰
  - **状態**: 継続（Round 2 未解決。PASS扱い禁止。P1-01/03/06/07/08 修正完了後に再測定する）。
  - **Objective**: 対象5業務の画面が「実行」から「申請」へ変わり、申請者単独では確定できない。
    二重click/retryでも業務操作は1回。既存の5業務の機能が壊れていない。
  - **実装済み**: 見積提出/受注、契約稼動化/単価改定、請求送付/取消、BP支払確定、月次締め/reopenを
    `ApprovalTargetAdapterRegistry`経由の申請へ統合。5 adapter、決定的SHA-256 idempotency key、月次締めの最終承認者監査主体、
    既存DI修正、UI文言・4言語bundle・UI契約を反映した。
  - **定向回帰実測**: `QuotationApiControllerTest` 4件、`ContractApiControllerTest` 12件、
    `ContractPaginationTest` 13件、`InvoiceApiControllerTest` 10件、`ApprovalTargetAdapterTest` 7件の計46件を
    failures 0 / errors 0 / skipped 0で確認した。adapterは既存service委譲、月次締め最終承認者、registry idempotencyを確認した。
  - **全量実測（current B1作業木、fixture修正後、2026-08-03）**: `pwsh -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-like-ci.ps1`が実行した`mvn -B clean test`は`1433 tests / failures 0 / errors 0 / skipped 12`でMaven本体はBUILD SUCCESS、scriptはskip検出によりexit 1。Node `v24.18.0`は利用可能でJS構文チェックは実行された。`EngineerFollowupServiceTest`のH2 fixtureへメニュー・role mappingを復元した結果、全量での`MobileResponsiveLayoutTest` `quick-add-label` failureは解消した。skipは12 test cases / 10 report classesで、Docker依存の`CustomerContactPrimaryConcurrencyTest`、`FlywayLegacyV60MigrationSmokeTest`、`FlywayLegacyV71MigrationSmokeTest`、`FlywayMigrationSmokeTest`、`FlywayRepairRunbookTest`、`FlywayV62ClosedHistoryMigrationSmokeTest`、`FlywayV63UpgradeMigrationSmokeTest`、`FlywayV73PartialRepairSmokeTest`、`ConcurrentUpdateTest`、`ConcurrentLoginSessionSmokeTest`。current作業木の最新実測は1433 / 0 / 0 / 12である。
  - **M定向回帰の再測定**: `QuotationApiControllerTest` 4件、`ContractApiControllerTest` 12件、`ContractPaginationTest` 13件、`InvoiceApiControllerTest` 10件、`ApprovalTargetAdapterTest` 7件の計46件を failures 0 / errors 0 / skipped 0で再確認した。
  - **migration/static回帰**: `MigrationScriptIntegrityTest` 26件、`SpecDispatchConsistencyTest` 8件、`FlywayMigrationSmokeTest` 2件の計36件を実行し、failures 0 / errors 0 / skipped 2。後者2件はDocker daemon unavailableによるskipである。
  - **MySQL smoke**: current環境ではDocker daemonへ接続できず、V78/V79を含むfresh/legacy/upgrade/partial-repair/repair/concurrentの実MySQL検証は未実施である。過去のDocker利用可能時のPASS記録は履歴として保持するが、current作業木のrelease evidenceには再利用しない。
  - **未実施・未解決**: 実ブラウザdesktop/390pxの5業務通し、実MySQLでのV79適用・rollback、複数JVMのShedLock/claim競合、実Webhook endpoint、commit前例外時の実DB rollbackは未確認である。全量failure 1、Docker依存skip 12も残るため、B1/Mはrelease gate未達としてPASS扱いにしない。
  - **テスト要件**: L4。`mvn test`全量、fresh/legacy MySQL smoke、
    5業務のbrowser通し（desktop/390px）、既存Contract/Invoice/BpPayment/Closingの回帰、
    Node/JS syntax、`git diff --check`。
  - **Demo**: 申請者単独確定不可と二重実行0を確認。5業務それぞれで申請→承認→適用を通す。
  - **実装ガイダンス**: `design.md`§6決定表とplatform-invariantsの境界、既存資産再利用規約に従い、未決事項を黙って補完しない。
