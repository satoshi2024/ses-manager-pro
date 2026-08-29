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
- **Evidence**: `review-evidence.md` に、検証対象Head、実行コマンド、reconciliation、未返却一覧、secret scan、rollback/runbookを記録しています。
- **PR作成ポリシー**: 実装対話ではPRを作成せず、独立ReviewのPLAN/IMPLEMENTATION双方PASS後に作成する。

---

## 2. 実装完了対応表 (Requirements -> Implementation -> Test)

| 要件番号 (Requirements ID) | 要件概要 | 実装主要ファイル | 自動テストクラス & メソッド | 結果 |
|---|---|---|---|---|
| **AS-R1** | 資産管理（状態6区分）・貸与返却・期間重複代数排他・返却直後再貸与・不変イベント台帳・DocumentLink受領書連携・終端履歴保持 | `m_asset`, `t_asset_assignment`, `t_asset_event`, `t_document_link`, `AssetService`, `AssetAssignmentService`, `AssetAssignmentServiceImpl`, `AssetEventService` | `AssetEntityMapperTest`<br>`AssetAssignmentConcurrencyTest.testConcurrentAssignmentOnSameAsset`<br>`AssetServiceTest.testAssetLifecycleAndEventHistory`<br>`AssetBoundaryAndLifecycleIntegrationTest.testReassignImmediatelyAfterReturn`<br>`AssetBoundaryAndLifecycleIntegrationTest.testSoftDeleteInvariants`<br>`AssetBoundaryAndLifecycleIntegrationTest.testDocumentEvidenceScopeRejection` | **PASS** |
| **AS-R2** | 外部アカウント参照・包括的秘密非保存・失効確認分離（timeout/5xx/429=`PENDING_CONFIRMATION`、分類不能=`UNKNOWN`）・Recovery/Idempotency（永続化/バックオフ）・ライセンス席数CAS（`-1 / = / +1` & 並行CAS） | `m_external_account_system`, `t_external_account_reference`, `m_license_plan`, `t_license_assignment`, `ExternalAccountProviderClient`, `ExternalAccountService`, `LicenseService`, `AssetLifecycleScheduler` | `AssetComprehensiveSecretScanTest` (4メソッド)<br>`AssetSecretFieldScanTest`<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseSeatLimitBoundaryMinusOneEqualPlusOneAndReassign`<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseConcurrentAllocationWithCas`<br>`AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency`<br>`AssetOffboardingServiceTest.testProviderRevokeConfirmationTimeout`<br>`AssetBoundaryAndLifecycleIntegrationTest.testSoftDeleteInvariants` | **PASS** |
| **AS-R3** | 実地棚卸し差異照合（確定後更新拒否）・紛失インシデント追跡・NF-01退社 3大blocker連携 | `t_asset_inventory_run`, `t_asset_inventory_item`, `AssetInventoryService`, `AssetOffboardingService`, `AssetAlertService` | `AssetServiceTest.testInventoryRunFlow`<br>`AssetBoundaryAndLifecycleIntegrationTest.testInventoryDisallowUpdateAndDoubleComplete`<br>`AssetAlertServiceTest.testLostAssetIncidentAlert`<br>`AssetBoundaryAndLifecycleIntegrationTest.testOffboardingThreeBlockers`<br>`AssetOffboardingServiceTest` | **PASS** |
| **AS-R4** | 認可スコープ（営業=担当要員の現在貸与のみ・法人追加制限なし、マネージャー=現在貸与∩法人、未貸与拒否）・要員マイポータル・期限通知（7/3/当日/週次/リース満了）・監査ログ | `AssetScopeService`, `AssetScopeServiceImpl`, `AssetMapper`, `MyAssetApiController`, `AssetAlertService`, `AssetLifecycleScheduler`, `templates/my/assets.html` | `MyAssetApiControllerTest.testMyAssetPortalFlow`<br>`AssetAlertServiceTest.testCheckOverdueAssignments`<br>`AssetAlertServiceTest.testCheckExpiringLeases`<br>`AssetApiControllerTest`<br>`AssetBoundaryAndLifecycleIntegrationTest.testOrganizationScopeAndMultiCorporationIsolation`<br>`AssetBoundaryAndLifecycleIntegrationTest.testSalesAndManagerScopeUsesAssignmentAndManagedOrganization`<br>`AssetBoundaryAndLifecycleIntegrationTest.testDocumentEvidenceScopeRejection` | **PASS** |
| **CR-01** | 認証・認可 (Spring Security / ActionPermissionResolver / DataScope / DocumentLink 一貫適用) | `SecurityConfig`, `ActionPermissionResolver`, `AssetScopeService`, `AssetAssignmentServiceImpl`, `DocumentServiceImpl` | `AssetApiControllerTest`, `AssetApiRoleScopeIntegrationTest`, `MyAssetApiControllerTest`, `DocumentApiControllerTest`, `AssetBoundaryAndLifecycleIntegrationTest` | **PASS** |
| **CR-02** | 状態・競合・冪等性 (CAS / FOR UPDATE 排他 / 二重失効安全 / Provider Recovery / UNKNOWN分類) | `AssetAssignmentServiceImpl`, `LicenseServiceImpl`, `ExternalAccountProviderClient`, `ExternalAccountServiceImpl` | `AssetAssignmentConcurrencyTest` (並行4スレッド排他)<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseConcurrentAllocationWithCas` (並行4スレッド2席CAS排他)<br>`AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency` (PENDING/UNKNOWNから確認)<br>`AssetBoundaryAndLifecycleIntegrationTest.testSoftDeleteInvariants` (終端履歴削除拒否) | **PASS** |
| **CR-03** | データ・マイグレーション (V129/V130, V1/H2同期, DocumentLink契約準拠) | `V129`, `V130`, `schema-asset-account-license-lifecycle-h2.sql`, `V1__create_tables.sql` | `AssetEntityMapperTest`, 全テストコンテキスト起動 (0 failures) | **PASS** |
| **CR-04** | 監査・セキュリティ・包括的秘密非保存 (Comprehensive No-Secrets Policy) | `AssetComprehensiveSecretScanTest`, `ApiAuditFilter`, `t_asset_event`, `ExternalAccountServiceImpl.maskIdentifier` | `AssetComprehensiveSecretScanTest` (全Javaソースファイル走査・0 secret/PII fields 検証: 4/4 PASS) | **PASS** |
| **CR-05** | UI・390pxレスポンシブ・4言語メッセージ同期 (ja, en, zh-CN, ko) | `templates/asset/*.html`, `templates/my/assets.html`, `static/js/modules/asset*.js`, `messages*.properties` | 4言語プロパティ同期 & MockMvc レンダリング検証 | **PASS** |
| **CR-06** | NF-09対象テストの完全性（専用Fast 66件 + MySQL実コンテナ 3件） | NF-09対象14テストクラス (66メソッド) + MySQL 1クラス (3メソッド) | NF-09専用Fast: **66/66 tests PASS (0 failure, 0 error, 0 skipped)**<br>MySQL Gate: `mvn test -Pmysql-tests -Dtest=AssetMySqlIntegrationTest` (**3/3 PASS, 0 failure, 0 error, 0 skipped**)<br>`MySqlTestShardInventoryTest` PASS & `AssetMySqlIntegrationTest` 登録。リポジトリ全体 `mvn test` は既存/環境側失敗があり、全体Fast gateは **未PASS** | **NF-09対象PASS / 全体gate未PASS** |

---

## 3. 6大必須条件の検証エビデンス

1. **同一assetの期間重複貸与を並行testで拒否する (AS-R1.3 / CR-02)**:
   - `AssetAssignmentConcurrencyTest.testConcurrentAssignmentOnSameAsset`: 4スレッド同時貸与で確実に1件のみ成功、3件拒否（409/業務例外）。
   - `AssetBoundaryAndLifecycleIntegrationTest.testReassignImmediatelyAfterReturn`: 返却直後の再貸与が正常に成立。
2. **password/token/recovery code用column/DTO/logを作らない (AS-R2.3 / CR-04)**:
   - `AssetComprehensiveSecretScanTest` (4/4 PASS): DDL、Entity/DTO、HTML input、JS payload、全Javaファイル（Service/Controller/Filter/Scheduler）のログ出力・例外メッセージ走査で 0 違反を自動検証。
3. **external revoke requestとconfirmed resultを区別し、timeoutを成功扱いにしない (AS-R2.4)**:
   - `AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency`: FAILED_OR_TIMEOUT 時に `PENDING_CONFIRMATION`（`idempotency_key`, `retry_count`, `next_retry_at`）を維持し、ポーリングジョブ `processPendingRevokePollJob()` でプロバイダ復旧後に自動確認完了、二重失効確認は安全に冪等処理。
4. **退社case未返却/未失効block、例外承認、scope、棚卸し差異をtestする (AS-R3.4 / AS-R3.2 / AS-R4.1)**:
   - `AssetBoundaryAndLifecycleIntegrationTest.testOffboardingThreeBlockers`: 未返却端末・未失効アカウント・未解放ライセンスの 3大残存 blocker 検出と、`LIFECYCLE_EXCEPTION` 特例承認によるパスを検証。
   - `AssetBoundaryAndLifecycleIntegrationTest.testInventoryDisallowUpdateAndDoubleComplete`: 棚卸し確定後の明細変更拒否・二重確定拒否を検証。
   - `AssetBoundaryAndLifecycleIntegrationTest.testOrganizationScopeAndMultiCorporationIsolation` / `testSalesAndManagerScopeUsesAssignmentAndManagedOrganization` / `testDocumentEvidenceScopeRejection`: 営業は担当要員の現在貸与のみで別法人所有資産も許可、未貸与・担当外は拒否。マネージャーは現在貸与かつ共有/管轄法人に限定し、無関係要員の `t_document_link` も拒否することを実証。
5. **移管/返却/紛失/廃棄履歴を上書きしない (AS-R1.4)**:
   - `AssetEventServiceImpl`: 追記のみ（INSERT-only）で `t_asset_event` に全履歴を永続蓄積。
   - `AssetAssignmentServiceImpl.removeById` は貸与履歴の論理削除を常に拒否し、`ExternalAccountService.softDeleteAccount` は `REVOKED`、`LicenseService.softDeleteAssignment` は `RELEASED`/解放済み終端行を拒否する。
6. **資産件数reconciliation、未返却一覧、secret scan、rollback/runbookをReviewへ渡す (CR-03 / CR-04)**:
   - `review-evidence.md` にテストfixtureの照合値、Review再実行用の件数・未返却SQL、失効未確認・未解放ライセンス確認、secret scan結果を記録。
   - `runbook.md` に運用・初期移行・緊急ロールバック手順を完備。

## 4. 独立Reviewへの提出状態

- **検証対象実装remote Head**: `f9dd290ec6dedc8efd46ea77c1bfdd883c5c3255`（実装・テスト修正時点で `git rev-parse HEAD` と `git ls-remote` が一致）。文書commit後の最終Review HeadはReview開始時に同コマンドで再固定する。
- **PLAN Review**: PENDING（実装エージェントによる自己判定はしない）。
- **IMPLEMENTATION Review**: PENDING（実装エージェントによる自己判定はしない）。
- **PR**: 未作成。両ReviewがPASSになるまで作成しない。
