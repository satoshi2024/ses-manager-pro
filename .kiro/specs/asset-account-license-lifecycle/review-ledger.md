# NF-09 資産・アカウント・ライセンス管理 レビュー引渡し台帳

## 1. 成果物サマリー & コミット情報
- **Feature Key**: `asset-account-license-lifecycle` (NF-09)
- **Worktree**: `c:\work\ses-asset-account-license-lifecycle`
- **Branch**: `codex/asset-account-license-lifecycle`
- **Base Commit**: `b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd` (`origin/main`)
- **Review Handoff Policy**: 
  - 本台帳のコミット自体がリモートプッシュされることで確定するため、自己参照によるSHA循環不一致を防止する目的で、本台帳内には固定Head SHAを直接ハードコードしません。
  - 独立Review側で「検証開始時点の最新 `origin/codex/asset-account-license-lifecycle` Head」を固定Review Headとして採用・検証します。
- **PR作成ポリシー**: 実装対話ではPRを作成せず、独立ReviewのPLAN/IMPLEMENTATION双方PASS後に作成する。

---

## 2. 実装完了対応表 (Requirements -> Implementation -> Test)

| 要件番号 (Requirements ID) | 要件概要 | 実装主要ファイル | 自動テストクラス & メソッド | 結果 |
|---|---|---|---|---|
| **AS-R1** | 資産管理（状態6区分）・貸与返却・期間重複代数排他・返却直後再貸与・不変イベント台帳 | `m_asset`, `t_asset_assignment`, `t_asset_event`, `AssetService`, `AssetAssignmentService`, `AssetEventService` | `AssetEntityMapperTest`<br>`AssetAssignmentConcurrencyTest.testConcurrentAssignmentOnSameAsset`<br>`AssetServiceTest.testAssetLifecycleAndEventHistory`<br>`AssetBoundaryAndLifecycleIntegrationTest.testReassignImmediatelyAfterReturn` | **PASS** |
| **AS-R2** | 外部アカウント参照・包括的秘密非保存・失効確認分離・Recovery/Idempotency・ライセンス席数CAS（`-1 / = / +1` & 並行CAS） | `m_external_account_system`, `t_external_account_reference`, `m_license_plan`, `t_license_assignment`, `ExternalAccountService`, `LicenseService` | `AssetComprehensiveSecretScanTest` (4メソッド)<br>`AssetSecretFieldScanTest`<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseSeatLimitBoundaryMinusOneEqualPlusOneAndReassign`<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseConcurrentAllocationWithCas`<br>`AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency`<br>`AssetOffboardingServiceTest.testProviderRevokeConfirmationTimeout` | **PASS** |
| **AS-R3** | 実地棚卸し差異照合（確定後更新拒否）・紛失インシデント追跡・NF-01退社 3大blocker連携 | `t_asset_inventory_run`, `t_asset_inventory_item`, `AssetInventoryService`, `AssetOffboardingService`, `AssetAlertService` | `AssetServiceTest.testInventoryRunFlow`<br>`AssetBoundaryAndLifecycleIntegrationTest.testInventoryDisallowUpdateAndDoubleComplete`<br>`AssetAlertServiceTest.testLostAssetIncidentAlert`<br>`AssetBoundaryAndLifecycleIntegrationTest.testOffboardingThreeBlockers`<br>`AssetOffboardingServiceTest` | **PASS** |
| **AS-R4** | 認可スコープ（全方位一致）・要員マイポータル・期限通知（7/3/当日/週次/リース満了）・監査ログ | `AssetScopeService`, `MyAssetApiController`, `AssetAlertService`, `AssetLifecycleScheduler`, `templates/my/assets.html` | `MyAssetApiControllerTest.testMyAssetPortalFlow`<br>`AssetAlertServiceTest.testCheckOverdueAssignments`<br>`AssetAlertServiceTest.testCheckExpiringLeases`<br>`AssetApiControllerTest`<br>`AssetBoundaryAndLifecycleIntegrationTest.testOrganizationScopeAndMultiCorporationIsolation`<br>`AssetBoundaryAndLifecycleIntegrationTest.testDocumentEvidenceScopeRejection` | **PASS** |
| **CR-01** | 認証・認可 (Spring Security / ActionPermissionResolver / DataScope 一貫適用) | `SecurityConfig`, `ActionPermissionResolver`, `AssetScopeService` | `AssetApiControllerTest`, `MyAssetApiControllerTest`, `AssetBoundaryAndLifecycleIntegrationTest` | **PASS** |
| **CR-02** | 状態・競合・冪等性 (CAS / FOR UPDATE 排他 / 二重失効安全 / Provider Recovery) | `AssetAssignmentServiceImpl`, `LicenseServiceImpl`, `ExternalAccountServiceImpl` | `AssetAssignmentConcurrencyTest` (並行4スレッド排他)<br>`AssetBoundaryAndLifecycleIntegrationTest.testLicenseConcurrentAllocationWithCas` (並行4スレッド2席CAS排他)<br>`AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency` | **PASS** |
| **CR-03** | データ・マイグレーション (V129/V130, V1/H2同期, DocumentLink契約準拠) | `V129`, `V130`, `schema-asset-account-license-lifecycle-h2.sql`, `V1__create_tables.sql` | `AssetEntityMapperTest`, 全テストコンテキスト起動 (0 failures) | **PASS** |
| **CR-04** | 監査・セキュリティ・包括的秘密非保存 (Comprehensive No-Secrets Policy) | `AssetComprehensiveSecretScanTest`, `ApiAuditFilter`, `t_asset_event`, `ExternalAccountServiceImpl.maskIdentifier` | `AssetComprehensiveSecretScanTest` (DDL・Entity・DTO・UI・Serviceログ/例外メッセージ 0 secret/PII fields 検証) | **PASS** |
| **CR-05** | UI・390pxレスポンシブ・4言語メッセージ同期 (ja, en, zh-CN, ko) | `templates/asset/*.html`, `templates/my/assets.html`, `static/js/modules/asset*.js`, `messages*.properties` | 4言語プロパティ同期 & MockMvc レンダリング検証 | **PASS** |
| **CR-06** | テスト完全性 (Fast H2 / 並行性 / 退社ゲート / 境界 / スキャン / MySQL Shard スキップ0件) | 全 11 テストクラス (33 メソッド) | `mvn test` (**33/33 tests PASS, 0 failure, 0 error, 0 skipped**)<br>`MySqlTestShardInventoryTest` PASS & `AssetMySqlIntegrationTest` 登録 | **PASS** |

---

## 3. 6大必須条件の検証エビデンス

1. **同一assetの期間重複貸与を並行testで拒否する (AS-R1.3 / CR-02)**:
   - `AssetAssignmentConcurrencyTest.testConcurrentAssignmentOnSameAsset`: 4スレッド同時貸与で確実に1件のみ成功、3件拒否（409/業務例外）。
   - `AssetBoundaryAndLifecycleIntegrationTest.testReassignImmediatelyAfterReturn`: 返却直後の再貸与が正常に成立。
2. **password/token/recovery code用column/DTO/logを作らない (AS-R2.3 / CR-04)**:
   - `AssetComprehensiveSecretScanTest` (4/4 PASS): DDL、Entity/DTO、HTML input、JS payload、Serviceログ/例外メッセージの全方位スキャンで 0 違反を自動検証。
3. **external revoke requestとconfirmed resultを区別し、timeoutを成功扱いにしない (AS-R2.4)**:
   - `AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency`: FAILED_OR_TIMEOUT 時に `PENDING_CONFIRMATION` を維持し、ポーリングジョブ `processPendingRevokePollJob()` でプロバイダ復旧後に自動確認完了、二重失効確認は安全に冪等処理。
4. **退社case未返却/未失効block、例外承認、scope、棚卸し差異をtestする (AS-R3.4 / AS-R3.2 / AS-R4.1)**:
   - `AssetBoundaryAndLifecycleIntegrationTest.testOffboardingThreeBlockers`: 未返却端末・未失効アカウント・未解放ライセンスの 3大残存 blocker 検出と、`LIFECYCLE_EXCEPTION` 特例承認によるパスを検証。
   - `AssetBoundaryAndLifecycleIntegrationTest.testInventoryDisallowUpdateAndDoubleComplete`: 棚卸し確定後の明細変更拒否・二重確定拒否を検証。
   - `AssetBoundaryAndLifecycleIntegrationTest.testOrganizationScopeAndMultiCorporationIsolation` / `testDocumentEvidenceScopeRejection`: 組織スコープと無関係組織からの証跡アクセス拒否を検証。
5. **移管/返却/紛失/廃棄履歴を上書きしない (AS-R1.4)**:
   - `AssetEventServiceImpl`: 追記のみ（INSERT-only）で `t_asset_event` に全履歴を永続蓄積。
6. **資産件数reconciliation、未返却一覧、secret scan、rollback/runbookをReviewへ渡す (CR-03 / CR-04)**:
   - `runbook.md` に運用・初期移行・緊急ロールバック手順を完備。
