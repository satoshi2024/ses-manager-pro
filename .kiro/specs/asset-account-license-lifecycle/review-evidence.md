# NF-09 独立Review提出用証跡

## 1. 対象とHead

- **Feature**: `asset-account-license-lifecycle` / NF-09
- **Approved scope**: 資産台帳・貸与event/履歴・棚卸し・外部account reference・license管理、所有法人・状態・期間重複・秘密非保存・退社blocker、MDM/IdP正本境界、NF-01 link contract。
- **Owner**: `PROJECT_OWNER`（プロジェクト責任者）
- **Worktree**: `C:\work\ses-asset-account-license-lifecycle`
- **Branch**: `codex/asset-account-license-lifecycle`
- **Base branch**: `origin/main`
- **Base**: `b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd` (`origin/main`)
- **実装検証Head**: 第10回Review追加是正の実装・テスト・文書commitをpush済みの最終提出Headとして扱い、Review開始時に `git rev-parse HEAD` と `git ls-remote origin refs/heads/codex/asset-account-license-lifecycle` で再固定する。
- **Remote確認**: 第10回Review追加是正の各commitはpush済み。最終remote Headは自己参照SHA循環を避けるため、Review開始時に同コマンドで再固定する。
- **PR**: 実装対話では未作成。独立ReviewのPLAN/IMPLEMENTATION双方PASS後にのみ作成する。

## 2. DG-09 / NF-01 決定事項

- DG-09 は `DG-09-SCOPE-APPROVAL-20260828-01` で承認済み。リンク契約は未決定ではないため、spec/discovery止まりの条件には該当しない。
- `owner_company_id` は `m_organization_unit.legal_entity_id`。新しい `m_company` は作成していない。
- MDMを端末状態、IdP/SaaSを外部アカウント失効状態の正本とし、DBは参照・要求・確認・再試行の証跡正本とする。
- `RESIGN_ASSET_RETURN` で未返却端末・未失効アカウント・未解放ライセンスをblockし、`LIFECYCLE_EXCEPTION` の承認済み例外だけをWAIVED扱いとする。
- password/token/recovery codeはDB列・Entity/DTO・HTML input・JS payload・ログ・監査payloadへ保存しない。
- Provider timeout/5xx/429は `PENDING_CONFIRMATION`、応答形式を分類できない場合だけ `UNKNOWN` とし、`revoke_confirmed_at` が設定されるまで退社blockerを維持する。
- Provider confirmation APIの例外はaccount単位で `PENDING_CONFIRMATION`、retry count、next retryを永続化し、poll jobは後続accountを継続処理する。
- 営業は現任担当要員への現在貸与のみ（owner法人追加制限なし）、マネージャーは管轄要員への現在貸与かつ `owner_company_id IS NULL` または管轄法人、未貸与資産は両者へ公開しない。
- `RETURNED`/`REVOKED`/`RELEASED` の終端履歴は論理削除せず、貸与履歴の削除APIも拒否する。
- waiverは対象要員・退社case・`RESIGN_ASSET_RETURN` taskの完全一致でのみ有効とし、`approved_by`にはApproval Actionの実操作ユーザーを保存する。

## 3. 検証結果

### 3.1 NF-09専用Fast suite

実行コマンド:

```text
.\apache-maven-3.9.6\bin\mvn '-Dtest=AssetEntityMapperTest,AssetApiControllerTest,AssetApiRoleScopeIntegrationTest,AssetAssignmentConcurrencyTest,AssetAlertServiceTest,DocumentApiControllerTest,MyAssetApiControllerTest,AssetServiceTest,AssetSecretFieldScanTest,AssetOffboardingServiceTest,AssetComprehensiveSecretScanTest,AssetBoundaryAndLifecycleIntegrationTest,ScheduledMethodsHaveSchedulerLockTest,ActionPermissionResolverTest,AssetLifecycleAppendOnlyApiContractTest' test
```

結果: **78/78 PASS, Failures=0, Errors=0, Skipped=0**。R7/R8で追加した回帰に加え、R9で追加した状態遷移・返却日範囲・棚卸し6状態・`AssetService` API境界、紛失インシデント台帳/APIテスト、および第10回Reviewの認可境界・DocumentLink scope・通知再送assertionを同一コマンドへ含む。

専用suite外の実退社gate drill `ResignationGateFailureDrillTest` も **9/9 PASS, Failures=0, Errors=0, Skipped=0**。3 blocker照合と永続waiverのテストを含む。

内訳の重要assertion:

- 同一assetの4並行貸与は成功1・拒否3、返却日当日の再貸与を含む返却直後の再貸与は成功。
- 資産ステータスは6値に限定し、`RESERVED` の検索・表示・棚卸し入力を確認。不正な状態変更と設計表にない遷移（廃棄済み資産の保管中/予約復活、貸与サービスを経由しない `ASSIGNED` 化）を保存前に拒否する。
- 返却処理はロック取得後に `start_date <= actual_return_date <= 今日` を検証し、開始日前・未来日の返却を拒否する。同日返却・同日再貸与は許可する。
- NF-01の資産/license blockerはstatusまたは未返却/未解放日付のOR条件で不整合行も検出する。
- 法人A/B、営業担当範囲（別法人所有でも担当要員への現在貸与は許可、未貸与は拒否）、マネージャー組織・法人範囲（共有または管轄法人のみ）、要員本人、空集合fail-closedを確認。
- 実在 `t_document` の `ASSET_ASSIGNMENT` linkについて、無関係要員のdetail/downloadを貸与中・返却後とも403。旧assignmentの本人だけは履歴文書へアクセス可能。
- `ASSET_LOST_INCIDENT` linkだけを持つ証跡について、managerのdetail認可を確認。`DocumentServiceImpl.assertDocumentAccessAllowed` の同じguardがdetail/download双方へ適用され、営業/要員の紛失インシデントAPI GETは403。
- managerが別法人/別組織の既存assignment文書を紛失インシデントへリンクする操作は、対象資産scopeと既存文書link scopeの不一致として拒否し、拒否後のincident/linkを独立読取で確認。
- `AssetComprehensiveSecretScanTest`: **4/4 PASS**。全 `src/main/java` を対象に、multilineのログ・例外・監査payloadを含む未マスク値を検査。
- provider実装のidentifierログはマスク済みで、scan getter patternも`getAccountIdentifier`を含む。`AssetBoundaryAndLifecycleIntegrationTest` では同一key再送なし・別key上書き拒否を確認。
- 退社3大blocker、承認済み例外、棚卸し確定後更新拒否・二重確定拒否、資産・アカウント・ライセンスのsoft-delete安全条件を確認し、`RETURNED`/`REVOKED`/`RELEASED` 終端履歴の削除拒否をassert。
- `ResignationGateFailureDrillTest.testResignationGateUsesAssetOffboardingBlockersAndPersistedWaiver` で、実gateが3 blockerを照合し、対象一致・承認済みの永続waiverだけをWAIVEDとして採用することを確認。
- `AssetBoundaryAndLifecycleIntegrationTest.testConcurrentReturnAndWaiveSingleTerminalEvent`、`testLicenseConcurrentReleaseDecrementsOnce`、`testInventoryConcurrentCompletionSingleWinner` で各競合の勝者1件、終端event 1件、席数/集計整合を確認。
- `ScheduledMethodsHaveSchedulerLockTest`: **1/1 PASS**。

### 3.2 MySQL 8 gate

実行コマンド:

```text
.\apache-maven-3.9.6\bin\mvn '-Dtest=AssetMySqlIntegrationTest' test -Pmysql-tests
```

結果: **10/10 PASS, Failures=0, Errors=0, Skipped=0**。MySQL 8 TestcontainersでFlyway V129/V130/V131/V132/V133、`FOR UPDATE`、CAS、貸与履歴、およびclaim・異なるaccountの同一key・返却/免除・license解放・棚卸し確定・紛失報告の6並行シナリオを実行。`MySqlTestShardInventoryTest`のshard登録整合も確認済み。

追加した並行assertionは、同一keyのprovider call count=1、異なるaccountの同一keyは409かつprovider call count=1、返却/免除の勝者1件と終端event 1件、license席数の1回減算、棚卸し二重確定の勝者1件、同一assetの紛失報告でincident 1行・recipientごとの通知1件です。H2再送では通知件数とdedupe keyの不変性も確認しました。V132 smokeではwaiverのscope列・unique/FKと`t_asset_event` UPDATE/DELETE triggerを、同じMySQL schema testでV133の紛失インシデントtable・列・unique/FKを確認しました。

V133追加による既存migration smokeの追随確認として、`FlywaySelfServiceSchemaSmokeTest`（3メソッド）と`FlywayCertificationLearningSkillGapSchemaSmokeTest`（1メソッド）も同時実行し、**4/4 PASS**。Flyway latest versionは133で、旧来のlatest version assertionもV133へ同期した。

### 3.3 リポジトリ全体Fast gateの扱い

`mvn test` はBaseと最終Headで固定seed `27838638095700` を使って比較したが、全体PASSではない。NF-09対象テストは通過している一方、Baseにも存在する既存/環境側テストに失敗・エラーが残ったため、CR-06のリポジトリ全体Fast gateは **未PASS** と記録する。

- Base `b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd`: `tests=3060, failures=2, errors=16, skipped=0`。
- R5時点Head `e6659c90`: `tests=3118, failures=2, errors=11, skipped=0`。
- R6実装時点: `tests=3123, failures=2, errors=11, skipped=0`。R7追加修正による対象Fast suiteの失敗はなく、残存はBase/R5から継続する8クラス（loopback接続、固定H2 ID、既存Controller/I18n/production設定）だった。
- Head初回比較では `TransactionalRollbackForAuditTest` と `MyAssetApiControllerTest` が追加で検出されたが、前者は資産系更新@TransactionalのrollbackFor不足、後者はscope fixtureのH2共有DB汚染であり、R5時点で修正済み。R6までにP1競合・承認台帳・identifier mask・終端履歴保全を修正し、R7ではclaim競合・poll継続・service API境界・V132 DB保護を追加で修正した。R8では6状態契約・返却日当日再貸与・NF-01 blocker OR条件・異なるaccount間の同一key競合409を追加で修正した。R9ではサービス状態遷移表、実返却日範囲、棚卸し6状態、`AssetService` 汎用mutation入口を追加で修正し、第9回Review追加是正ではV133紛失インシデント追跡とLOST/DISPOSED専用経路を追加した。第10回Review追加是正ではGET認可、紛失証跡のdetail/download scope、DocumentLink cross-scope拒否、通知再送・並行重複assertionを追加した。現行対象Fast 78/78、文書認可補助23/23、MySQL 10/10、migration smoke 4/4、退社gate drill 9/9、shard inventory 1/1を再実行してPASSした。

- `ControllerTransactionalBanTest`
- `ProductionSecurityConfigurationTest`
- `PinningHttpsTransportTest`
- `PrometheusScraperLabE2ETest`（loopback接続環境エラー）
- `CapacityBaselineScriptTest`
- `ProjectSkillServiceImplTest`（random order下の固定H2 ID競合）
- `WebhookNotifierLoopbackIntegrationTest`
- `I18nJsControllerTest`

最終Headの残存8クラスはBaseにも存在し、今回のfeature対象外またはloopback・固定ID・production設定環境に起因する。この結果をNF-09対象suiteのPASSに混入させず、独立Reviewでベースライン差分と環境復旧後の再実行要否を確認する。

## 4. Reconciliation / 未返却一覧

### 4.1 テストfixtureの照合値

`AssetBoundaryAndLifecycleIntegrationTest` の棚卸し証跡:

```text
totalAssets=3, matched=1, discrepancy=0, missing=2
```

これはH2 test fixtureの結果であり、本番件数ではない。本番リリース前は [runbook.md](runbook.md) の読み取り専用SQLで状態別件数、未返却、失効未確認、未解放ライセンスを再照合する。

### 4.2 Reviewが再実行する未返却一覧

```sql
SELECT a.id, a.asset_tag, a.asset_name, a.status,
       aa.assignee_type, aa.assignee_id, aa.start_date,
       aa.expected_return_date, aa.actual_return_date
FROM m_asset a
JOIN t_asset_assignment aa ON aa.asset_id = a.id
WHERE a.deleted_flag = 0
  AND aa.deleted_flag = 0
  AND (aa.status = 'ACTIVE' OR aa.actual_return_date IS NULL)
ORDER BY aa.expected_return_date, a.asset_tag;
```

件数照合は `m_asset` の有効状態別合計と上記未返却一覧を突合し、差異があれば棚卸し差異として解消するまで廃棄・再貸与・退社完了を止める。

## 5. 外部失効と履歴保全

- `revoke_requested_at` と `revoke_confirmed_at` を別々に保持し、要求送信のみで `REVOKED` にしない。
- `FAILED_OR_TIMEOUT` は `PENDING_CONFIRMATION` のまま、分類不能応答だけを `UNKNOWN` として `next_retry_at` によるポーリングへ送り、確認成功時だけ `REVOKED` とする。同一`idempotency_key`はatomic claimでproviderへ一度だけ送信し、別keyによる上書きは409で拒否する。確認例外はaccount単位でretry状態を永続化してpollを継続する。
- 資産の移管・返却・紛失・廃棄は `t_asset_event` へINSERT-onlyで追記する。貸与・アカウント・ライセンスの履歴も状態を上書きせず、`RETURNED`/`REVOKED`/`RELEASED` の終端行は論理削除を拒否する。
- 外部プロバイダ呼出しはDBトランザクション外。要求記録と確認結果の間で障害が発生しても、未確認を成功として扱わない。

### 5.1 第7回Review P1/P2対応の実測

- `AssetMySqlIntegrationTest.testConcurrentRevokeClaimCallsProviderOnceOnMySQL`: 同一keyを2 threadから送信し、atomic claimの勝者だけがproviderを呼び、call count=1。
- `AssetMySqlIntegrationTest.testConcurrentReturnAndWaiveOnMySQL`: 返却と免除の競合は勝者1件、失敗1件、終端event 1件。
- `AssetMySqlIntegrationTest.testConcurrentLicenseReleaseDecrementsOnceOnMySQL`: 同一割当の二重解放でも席数は1回だけ減算。
- `AssetMySqlIntegrationTest.testConcurrentInventoryCompletionOnMySQL`: 棚卸し二重確定は勝者1件で、確定後の明細と集計を固定。
- `AssetMySqlIntegrationTest.testV132AndV133SchemaAndAppendOnlyGuardsOnMySQL`: Flyway latest=133、waiver scope列・unique・case/task FK、event UPDATE/DELETE trigger、およびV133紛失インシデントtableの列・unique/FKを確認。
- `AssetLifecycleAppendOnlyApiContractTest`: `AssetEventService`、`AssetAssignmentService`、`ExternalAccountService`、`AssetService` が汎用 `IService` mutation APIを公開しないことを確認。

### 5.2 第8回Review P1/P2対応の実測

- `AssetServiceTest.testAssetFullLifecycle`: `RESERVED` を含む6状態の許可値を状態変更へ適用し、不正状態を業務例外で拒否することを確認。
- `AssetBoundaryAndLifecycleIntegrationTest.testReassignImmediatelyAfterReturn`: 返却日と同日の再貸与を許可する期間境界を確認。
- `AssetBoundaryAndLifecycleIntegrationTest.testOffboardingBlockersUseStatusOrDateContract`: `ACTIVE + actual_return_date`、`RETURNED + actual_return_date=NULL`、`ACTIVE + released_date`、`RELEASED + released_date=NULL` の不整合行を退社blockerとして検出することを確認。
- `AssetMySqlIntegrationTest.testConcurrentRevokeClaimSameKeyAcrossAccountsReturns409OnMySQL`: 異なるaccountが同一keyを並行claimした場合、provider call count=1、成功1件、409が1件であることをMySQL 8 Testcontainersで確認。
- R8時点でNF-09専用Fastコマンドへ `AssetLifecycleAppendOnlyApiContractTest` を追加し、**72/72 PASS**（0 failure / 0 error / 0 skipped）を記録した。R9追加後実績は **77/77**、第10回Review追加是正後の現行実績は上記の **78/78** である。

### 5.3 第9回Review P1/P2対応の実測

- `AssetServiceTest.testAssetStatusTransitionGuard`: `AssetService.changeStatus` が状態遷移表を強制し、直接 `IN_STOCK → ASSIGNED`、`DISPOSED → RESERVED`、`LOST → UNDER_MAINTENANCE`、`UNDER_MAINTENANCE → ASSIGNED/DISPOSED` を拒否し、元状態を保持することを確認。
- `AssetLifecycleAppendOnlyApiContractTest`: `AssetService` も汎用 `IService` mutation入口を公開せず、貸与/返却を専用 `AssetAssignmentService` 経由に限定するAPI境界を確認。
- `AssetBoundaryAndLifecycleIntegrationTest.testActualReturnDateRangeIsEnforced`: 開始日前・未来日の実返却を拒否し、当日返却だけを成功させることを確認。
- `AssetServiceTest.testInventoryObservedStatusVocabulary`: 棚卸し実地確認で `IN_STOCK`、`ASSIGNED`、`UNDER_MAINTENANCE`、`LOST`、`DISPOSED`、`RESERVED` の6値を保存し、空白/小文字入力の正規化と不正値拒否を確認。
- NF-09専用Fast **77/77 PASS**、MySQL asset **9/9 PASS**、V133追随migration smoke **4/4 PASS**、実退社gate drill **9/9 PASS**、`MySqlTestShardInventoryTest` **1/1 PASS**（すべてfailure=0、error=0、skip=0）。

### 5.4 第9回Review追加指摘の実測

- `AssetServiceTest.testLostIncidentLedgerAndEmergencyAlert`: LOST専用報告で `t_asset_lost_incident` の起票日時/報告者、リモートワイプ状態と要求・実施・確認日時、警察届出番号、保険申請状態/日時を保持し、関連 `DocumentLink` を登録することを確認。再送は同一台帳行・同一dedupe通知を再利用する。
- `AssetApiControllerTest.testLostIncidentApiFlow`: 管理者のreport-lost、`GET/PUT /api/assets/{assetId}/lost-incident` を実行し、初期状態と対応更新を再取得できることを確認。要員controllerから個別通知呼出しを除去し、admin/要員を同じAssetService経路へ統合した。
- `AssetServiceTest.testAssetStatusTransitionGuard`: 汎用 `changeStatus` の `ASSIGNED`/`IN_STOCK`/`LOST`/`DISPOSED` 遷移と、POST相当のLOST初期登録を拒否し、専用処理の副作用迂回を防止することを確認。
- `AssetMySqlIntegrationTest.testV132AndV133SchemaAndAppendOnlyGuardsOnMySQL`: Flyway latest=133、`t_asset_lost_incident` の必須列、asset unique key、asset FKをMySQL 8で確認。
- NF-09専用Fast **77/77 PASS**、MySQL asset **9/9 PASS**、V133追随migration smoke **4/4 PASS**、実退社gate drill **9/9 PASS**、`MySqlTestShardInventoryTest` **1/1 PASS**（すべてfailure=0、error=0、skip=0）。

### 5.5 第10回Review指摘の実測

- `AssetApiControllerTest.testSalesCannotReadLostIncidentDetails`: 担当資産を持つ営業でも `GET /api/assets/{assetId}/lost-incident` が403となり、GETを管理者/HR/マネージャー限定にするメソッド認可を確認。
- `DocumentServiceImplTest.assertDocumentAccessAllowed_lostIncidentLinkAllowsScopedManager` と `AssetBoundaryAndLifecycleIntegrationTest.testSalesAndManagerScopeUsesAssignmentAndManagedOrganization`: `ASSET_LOST_INCIDENT` 専用linkの文書detailをmanagerが取得でき、detail/download共通guardのscope判定を確認。cross-scope文書を紛失インシデントへリンクするmanager操作は拒否され、拒否後の状態を独立読取で確認。
- `AssetServiceTest.testLostIncidentLedgerAndEmergencyAlert`: 紛失報告再送前後の通知件数とdedupe keyが不変であることを確認。
- `AssetMySqlIntegrationTest.testConcurrentLostReportPublishesOnceOnMySQL`: MySQL 8で同一assetの並行紛失報告を実行し、incident 1行、recipientごとの通知1件、再送後の件数不変を確認。
- 第10回Review是正後の対象gateはNF-09専用Fast **78/78**、文書認可補助suite **23/23**、MySQL asset **10/10**、V133追随migration smoke **4/4**、実退社gate drill **9/9**、`MySqlTestShardInventoryTest` **1/1**（すべてfailure=0、error=0、skip=0）。

## 6. Secret scan

`AssetComprehensiveSecretScanTest` **4/4 PASS**。検査対象はEntity/DTO、V129/V130/V131/V132/V133/V1のDDL、HTML、JS、`src/main/java` 全Javaのログ・例外・監査payload。アカウント識別子は必要箇所でマスクし、getter検出に`getAccountIdentifier`を含め、password/token/recovery code用のcolumn/DTO/log値と未マスクidentifierは検出なし。

## 7. Rollback / 運用手順

- 運用手順、初期移行、reconciliation、未返却一覧、紛失インシデント対応、外部失効timeout、棚卸し差異、backup/restore、前方互換移行、隔離検証DB限定のDDL削除境界は [runbook.md](runbook.md) に記載。
- 本番通常ロールバックでDROPせず、書き込み停止・バックアップとFlyway履歴保存・別名DBリストア検証・承認済み切替を行う。
- `t_asset_event` 等の監査履歴をロールバックで物理削除しない。

## 8. Review状態

本ファイル、`requirements.md`、`design.md`、`inventory.md`、`tasks.md`、`review-ledger.md`、`runbook.md` を同一branchで提出する。独立Review（PLAN / IMPLEMENTATION）は実装エージェントでは実施しておらず、現在 **PENDING**。PASS判定前のPRは作成していない。
