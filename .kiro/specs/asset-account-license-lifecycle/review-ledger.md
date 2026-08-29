# NF-09 資産・アカウント・ライセンス管理 レビュー引渡し台帳

## 1. 成果物サマリー & コミット情報
- **Feature Key**: `asset-account-license-lifecycle` (NF-09)
- **Approved Scope**: 資産台帳・貸与event/履歴・棚卸し・外部account reference・license管理、所有法人・状態・期間重複・秘密非保存・退社blocker、MDM/IdP正本境界、NF-01 link contract
- **Owner**: `PROJECT_OWNER`（プロジェクト責任者）
- **Worktree**: `c:\work\ses-asset-account-license-lifecycle`
- **Branch**: `codex/asset-account-license-lifecycle`
- **Base Branch**: `origin/main`
- **Base Commit**: `b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd` (`origin/main`)
- **Review Handoff Policy**:
  - 本台帳のコミット自体がリモートプッシュされることで確定するため、自己参照によるSHA循環不一致を防止する目的で、本台帳内には固定Head SHAを直接ハードコードしません。
  - 独立Review側で「検証開始時点の最新 `origin/codex/asset-account-license-lifecycle` Head」を固定Review Headとして採用・検証します。
- **Evidence**: `review-evidence.md` に、実装commit、最終remote Head固定コマンド、実行コマンド、reconciliation、未返却一覧、secret scan、rollback/runbookを記録しています。
- **PR作成ポリシー**: 実装対話ではPRを作成せず、独立ReviewのPLAN/IMPLEMENTATION双方PASS後に作成する。

---

## 2. 実装完了対応表 (Requirements -> Implementation -> Test)

| 要件番号 (Requirements ID) | 要件概要 | 実装主要ファイル | 自動テストクラス & メソッド | 結果 |
|---|---|---|---|---|
| **AS-R1** | 資産管理（状態6区分）・貸与返却・期間重複代数排他・返却直後再貸与・不変イベント台帳・DocumentLink受領書連携・終端履歴保持 | `m_asset`, `t_asset_assignment`, `t_asset_event`, `t_document_link`, `AssetService`, `AssetAssignmentService`, `AssetAssignmentServiceImpl`, `AssetEventService` | `AssetEntityMapperTest`<br>`AssetAssignmentConcurrencyTest.testConcurrentAssignmentOnSameAsset`<br>`AssetServiceTest.testAssetLifecycleAndEventHistory`<br>`AssetBoundaryAndLifecycleIntegrationTest.testReassignImmediatelyAfterReturn`<br>`AssetBoundaryAndLifecycleIntegrationTest.testConcurrentReturnAndWaiveSingleTerminalEvent`<br>`AssetBoundaryAndLifecycleIntegrationTest.testSoftDeleteInvariants`<br>`AssetBoundaryAndLifecycleIntegrationTest.testDocumentEvidenceScopeRejection` | **PASS** |
| **AS-R2** | 外部アカウント参照・包括的秘密非保存・失効確認分離（timeout/5xx/429=`PENDING_CONFIRMATION`、分類不能=`UNKNOWN`）・Recovery/Idempotency（永続化/バックオフ）・ライセンス席数CAS（`-1 / = / +1` & 並行CAS） | `m_external_account_system`, `t_external_account_reference`, `m_license_plan`, `t_license_assignment`, `ExternalAccountProviderClient`, `ExternalAccountService`, `LicenseService`, `AssetLifecycleScheduler` | `AssetComprehensiveSecretScanTest` (4メソッド)<br>`AssetSecretFieldScanTest`<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseSeatLimitBoundaryMinusOneEqualPlusOneAndReassign`<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseConcurrentAllocationWithCas`<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseConcurrentReleaseDecrementsOnce`<br>`AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency`（同一key再送なし・別key拒否）<br>`AssetOffboardingServiceTest.testProviderRevokeConfirmationTimeout`<br>`AssetBoundaryAndLifecycleIntegrationTest.testSoftDeleteInvariants`<br>`AssetMySqlIntegrationTest.testConcurrentRevokeClaimCallsProviderOnceOnMySQL` | **PASS** |
| **AS-R3** | 実地棚卸し差異照合（確定後更新拒否）・紛失インシデント追跡・NF-01退社 3大blocker連携 | `t_asset_inventory_run`, `t_asset_inventory_item`, `t_asset_offboarding_waiver`, `AssetInventoryService`, `AssetOffboardingService`, `ResignationGateChecker`, `AssetAlertService` | `AssetServiceTest.testInventoryRunFlow`<br>`AssetBoundaryAndLifecycleIntegrationTest.testInventoryDisallowUpdateAndDoubleComplete`<br>`AssetBoundaryAndLifecycleIntegrationTest.testInventoryConcurrentCompletionSingleWinner`<br>`AssetAlertServiceTest.testLostAssetIncidentAlert`<br>`ResignationGateFailureDrillTest`（9/9）<br>`AssetBoundaryAndLifecycleIntegrationTest.testOffboardingThreeBlockers`<br>`AssetOffboardingServiceTest` | **PASS** |
| **AS-R4** | 認可スコープ（営業=担当要員の現在貸与のみ・法人追加制限なし、マネージャー=現在貸与∩法人、未貸与拒否）・要員マイポータル・期限通知（7/3/当日/週次/リース満了）・監査ログ | `AssetScopeService`, `AssetScopeServiceImpl`, `AssetMapper`, `MyAssetApiController`, `AssetAlertService`, `AssetLifecycleScheduler`, `templates/my/assets.html` | `MyAssetApiControllerTest.testMyAssetPortalFlow`<br>`AssetAlertServiceTest.testCheckOverdueAssignments`<br>`AssetAlertServiceTest.testCheckExpiringLeases`<br>`AssetApiControllerTest`<br>`AssetBoundaryAndLifecycleIntegrationTest.testOrganizationScopeAndMultiCorporationIsolation`<br>`AssetBoundaryAndLifecycleIntegrationTest.testSalesAndManagerScopeUsesAssignmentAndManagedOrganization`<br>`AssetBoundaryAndLifecycleIntegrationTest.testDocumentEvidenceScopeRejection` | **PASS** |
| **CR-01** | 認証・認可 (Spring Security / ActionPermissionResolver / DataScope / DocumentLink 一貫適用) | `SecurityConfig`, `ActionPermissionResolver`, `AssetScopeService`, `AssetAssignmentServiceImpl`, `DocumentServiceImpl` | `AssetApiControllerTest`, `AssetApiRoleScopeIntegrationTest`, `MyAssetApiControllerTest`, `DocumentApiControllerTest`, `AssetBoundaryAndLifecycleIntegrationTest` | **PASS** |
| **CR-02** | 状態・競合・冪等性 (CAS / FOR UPDATE 排他 / 二重失効安全 / Provider Recovery / UNKNOWN分類) | `AssetAssignmentServiceImpl`, `AssetInventoryServiceImpl`, `LicenseServiceImpl`, `ExternalAccountProviderClient`, `ExternalAccountServiceImpl` | `AssetAssignmentConcurrencyTest` (並行4スレッド排他)<br>`AssetBoundaryAndLifecycleIntegrationTest.testConcurrentReturnAndWaiveSingleTerminalEvent` (返却/免除競合、終端event 1件)<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseConcurrentAllocationWithCas` (並行4スレッド2席CAS排他)<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseConcurrentReleaseDecrementsOnce` (二重解放で席数1回減算)<br>`AssetBoundaryAndLifecycleIntegrationTest.testInventoryConcurrentCompletionSingleWinner` (二重確定1件)<br>`AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency` (PENDING/UNKNOWNから確認、同一key再送なし、別key拒否)<br>`AssetBoundaryAndLifecycleIntegrationTest.testSoftDeleteInvariants` (終端履歴削除拒否)<br>`AssetMySqlIntegrationTest` の4並行テスト（claim、返却/免除、license解放、棚卸し確定） | **PASS** |
| **CR-03** | データ・マイグレーション (V129/V130/V131/V132, V1/H2同期, DocumentLink契約準拠) | `V129`, `V130`, `V131`, `V132`, `schema-asset-account-license-lifecycle-h2.sql`, `V1__create_tables.sql` | `AssetEntityMapperTest`, `AssetMySqlIntegrationTest`、migration smoke 4件、全対象コンテキスト起動 (0 failures) | **PASS** |
| **CR-04** | 監査・セキュリティ・包括的秘密非保存 (Comprehensive No-Secrets Policy) | `AssetComprehensiveSecretScanTest`, `ApiAuditFilter`, `t_asset_event`, `ExternalAccountServiceImpl.maskIdentifier`, `MockExternalAccountProviderClientImpl.maskIdentifier` | `AssetComprehensiveSecretScanTest` (全Javaソースファイル走査・`getAccountIdentifier`を含む0 secret/PII fields検証: 4/4 PASS) | **PASS** |
| **CR-05** | UI・390pxレスポンシブ・4言語メッセージ同期 (ja, en, zh-CN, ko) | `templates/asset/*.html`, `templates/my/assets.html`, `static/js/modules/asset*.js`, `messages*.properties` | 4言語プロパティ同期 & MockMvc レンダリング検証 | **PASS** |
| **CR-06** | NF-09対象テストの完全性（専用Fast 69件 + MySQL実コンテナ 8件） | NF-09対象14テストクラス (69メソッド) + MySQL 1クラス (8メソッド) | NF-09専用Fast: **69/69 tests PASS (0 failure, 0 error, 0 skipped)**<br>MySQL Gate: `mvn test -Pmysql-tests -Dtest=AssetMySqlIntegrationTest` (**8/8 PASS, 0 failure, 0 error, 0 skipped**)。V132追随の既存migration smoke 4件もPASS。`MySqlTestShardInventoryTest` PASS & `AssetMySqlIntegrationTest` 登録。固定seed `27838638095700` の全体比較は Base `3060 tests / 2 failures / 16 errors / 0 skipped`、R6実装時点 `3123 tests / 2 failures / 11 errors / 0 skipped`。残存はBaseにも存在する既存/環境側テスト。全体Fast gateは **未PASS** | **NF-09対象PASS / 全体gate未PASS（Base比較済み）** |

---

## 3. 6大必須条件の検証エビデンス

1. **同一assetの期間重複貸与を並行testで拒否する (AS-R1.3 / CR-02)**:
   - `AssetAssignmentConcurrencyTest.testConcurrentAssignmentOnSameAsset`: 4スレッド同時貸与で確実に1件のみ成功、3件拒否（409/業務例外）。
   - `AssetBoundaryAndLifecycleIntegrationTest.testReassignImmediatelyAfterReturn`: 返却直後の再貸与が正常に成立。
   - `AssetBoundaryAndLifecycleIntegrationTest.testConcurrentReturnAndWaiveSingleTerminalEvent`: 返却と免除を同時実行して1件のみ成功し、終端eventが1件だけ追記される。
2. **password/token/recovery code用column/DTO/logを作らない (AS-R2.3 / CR-04)**:
   - `AssetComprehensiveSecretScanTest` (4/4 PASS): DDL、Entity/DTO、HTML input、JS payload、全Javaファイル（Service/Controller/Filter/Scheduler）のログ出力・例外メッセージ走査で 0 違反を自動検証。
3. **external revoke requestとconfirmed resultを区別し、timeoutを成功扱いにしない (AS-R2.4)**:
   - `AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency`: FAILED_OR_TIMEOUT 時に `PENDING_CONFIRMATION`（`idempotency_key`, `retry_count`, `next_retry_at`）を維持し、ポーリングジョブ `processPendingRevokePollJob()` でプロバイダ復旧後に自動確認完了、二重失効確認は安全に冪等処理。`AssetOffboardingServiceTest.testProviderPollContinuesAfterConfirmationException` は1件の確認例外を永続化して後続accountを処理する。
   - 同テストで同一keyの再送がprovider request countを増やさず、別keyの上書きを409で拒否することを確認。providerログのidentifierは先頭2文字とドメイン以外をマスクする。
4. **退社case未返却/未失効block、例外承認、scope、棚卸し差異をtestする (AS-R3.4 / AS-R3.2 / AS-R4.1)**:
   - `ResignationGateFailureDrillTest.testResignationGateUsesAssetOffboardingBlockersAndPersistedWaiver`: 実退社gateが3 blockerを照合し、承認済みtarget一致の永続waiverだけをWAIVEDとして採用することを検証。
   - `AssetBoundaryAndLifecycleIntegrationTest.testOffboardingThreeBlockers`: 未返却端末・未失効アカウント・未解放ライセンスの 3大残存 blocker 検出と、`LIFECYCLE_EXCEPTION` 特例承認によるパスを検証。
   - `AssetBoundaryAndLifecycleIntegrationTest.testInventoryDisallowUpdateAndDoubleComplete` / `testInventoryConcurrentCompletionSingleWinner`: 棚卸し確定後の明細変更拒否・二重確定拒否を逐次/並行で検証。
   - `AssetBoundaryAndLifecycleIntegrationTest.testOrganizationScopeAndMultiCorporationIsolation` / `testSalesAndManagerScopeUsesAssignmentAndManagedOrganization` / `testDocumentEvidenceScopeRejection`: 営業は担当要員の現在貸与のみで別法人所有資産も許可、未貸与・担当外は拒否。マネージャーは現在貸与かつ共有/管轄法人に限定し、無関係要員の `t_document_link` も拒否することを実証。
5. **移管/返却/紛失/廃棄履歴を上書きしない (AS-R1.4)**:
   - `AssetEventServiceImpl`: 追記のみ（INSERT-only）で `t_asset_event` に全履歴を永続蓄積。
   - `AssetLifecycleAppendOnlyApiContractTest` で貸与・外部account・event serviceから汎用 `IService` mutation入口を除去し、`AssetMySqlIntegrationTest.testV132WaiverShapeAndAppendOnlyGuardsOnMySQL` でV132のevent UPDATE/DELETE trigger、waiver scope列・unique/FKを確認する。終端履歴の論理削除も状態にかかわらず拒否し、`RETURNED`/`REVOKED`/`RELEASED`を保持する。
6. **資産件数reconciliation、未返却一覧、secret scan、rollback/runbookをReviewへ渡す (CR-03 / CR-04)**:
   - `review-evidence.md` にテストfixtureの照合値、Review再実行用の件数・未返却SQL、失効未確認・未解放ライセンス確認、secret scan結果を記録。
   - `runbook.md` に運用・初期移行・緊急ロールバック手順を完備。

## 4. 独立Reviewへの提出状態

### 4.1 CR-06 Base/Head比較

- Base `b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd` の全体 `mvn test`: `tests=3060, failures=2, errors=16, skipped=0`。
- R5時点Head `e6659c90` の全体 `mvn test -Dsurefire.runOrder.random.seed=27838638095700`: `tests=3118, failures=2, errors=11, skipped=0`。
- R6実装時点の同条件: `tests=3123, failures=2, errors=11, skipped=0`。追加されたR6対象テストは全件成功し、残存8クラスはR5/Base比較と同じ既存・環境側クラス。
- R5時点Headの残存クラスはBaseにも存在する。初回Headで追加された `TransactionalRollbackForAuditTest`（資産系更新@TransactionalのrollbackFor不足）と `MyAssetApiControllerTest`（新規scope fixtureによるH2共有DB汚染）はfeature起因として修正した。R5時点の関連12/12、NF-09 66/66、MySQL 3/3に加え、R6ではNF-09 69/69、MySQL 3/3、V131追随migration smoke 4/4をPASSした。今回R7ではH2のpoll/API境界回帰とMySQL 8/8、V132追随migration smoke 4/4をPASSした。
- したがってNF-09対象はPASS、リポジトリ全体Fast gateは未PASSであり、独立Reviewでこの扱いを確認する。

- **検証対象実装remote Head**: R7の実装・テスト・文書commitはpush後に、最終Review開始時点で `git rev-parse HEAD` と `git ls-remote origin refs/heads/codex/asset-account-license-lifecycle` が一致することを確認する。
- **PLAN Review**: PENDING（実装エージェントによる自己判定はしない）。
- **IMPLEMENTATION Review**: PENDING（実装エージェントによる自己判定はしない）。
- **PR**: 未作成。両ReviewがPASSになるまで作成しない。

## 5. 第7回Review指摘への対応

| 指摘 | 対応 | 検証 |
|---|---|---|
| P1-01 同一idempotency keyの並行claim | claim SQLを `idempotency_key IS NULL` のみに限定し、更新0件側は同一keyの確定行を再読してproviderを呼ばない | `AssetMySqlIntegrationTest.testConcurrentRevokeClaimCallsProviderOnceOnMySQL`、provider call count=1 |
| P1-02 汎用IServiceによる履歴迂回 | `AssetEventService`/`AssetAssignmentService`/`ExternalAccountService`を専用API化し、V132でevent UPDATE/DELETEをDB trigger拒否 | `AssetLifecycleAppendOnlyApiContractTest`、MySQL V132 trigger shape、終端履歴拒否回帰 |
| P1-03 confirmation例外によるpoll中断 | account単位で確認例外を捕捉し、`PENDING_CONFIRMATION`・retry/backoffを永続化して継続 | `AssetOffboardingServiceTest.testProviderPollContinuesAfterConfirmationException` |
| P1-04 MySQL競合未検証 | claim、返却/免除、license二重解放、棚卸し二重確定をMySQL Testcontainersへ追加 | `AssetMySqlIntegrationTest` 8/8、skip=0 |
| P2-01 waiver scope | case/task完全一致をmapperとgateで要求し、scope欠落時はblockerを返して免除しない | `ResignationGateFailureDrillTest`、offboarding service回帰 |
| P2-02 approved_by | Approval Engineの最新APPROVE actionの実操作ユーザーを保存 | `LifecycleExceptionApprovalAdapter` 実装、承認台帳回帰 |
| P2-03 V132 shape | waiverのscope列、unique/FK、event triggerをDDLに追加 | `AssetMySqlIntegrationTest.testV132WaiverShapeAndAppendOnlyGuardsOnMySQL` |

R7の対象gateはすべて0 failure / 0 error / 0 skipped。リポジトリ全体Fast gateはBase由来の残存失敗を含むため、引き続き未PASSとして扱い、PRは作成していない。
