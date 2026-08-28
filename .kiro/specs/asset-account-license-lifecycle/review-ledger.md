# NF-09 資産・アカウント・ライセンス管理 レビュー引渡し台帳

## 1. 成果物サマリー & Head Commit
- **Feature Key**: `asset-account-license-lifecycle` (NF-09)
- **Worktree**: `c:\work\ses-asset-account-license-lifecycle`
- **Branch**: `codex/asset-account-license-lifecycle`
- **Base Commit**: `b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd` (`origin/main`)
- **Implementation Code Head**: `f2e25c08003f5db9976378c3b9b4f0b2f6ef1e97`
- **Review / Handoff Head**: `9458e9253459c55b6aeaf2e1f2fca9b1959bcce5`
- **PR作成ポリシー**: 実装対話ではPRを作成せず、独立ReviewのPLAN/IMPLEMENTATION双方PASS後に作成する。

---

## 2. 実装完了対応表 (Requirements -> Implementation -> Test)

| 要件番号 (Requirements ID) | 要件概要 | 実装主要ファイル | 自動テストクラス & メソッド | 結果 |
|---|---|---|---|---|
| **AS-R1** | 資産管理・貸与返却・期間重複代数排他・不変イベント台帳 | `m_asset`, `t_asset_assignment`, `t_asset_event`, `AssetService`, `AssetAssignmentService`, `AssetEventService` | `AssetEntityMapperTest`<br>`AssetAssignmentConcurrencyTest.testConcurrentAssignmentOnSameAsset`<br>`AssetServiceTest.testAssetLifecycleAndEventHistory` | **PASS** |
| **AS-R2** | 外部アカウント参照・秘密非保存原則・失効確認分離・ライセンス席数CAS | `m_external_account_system`, `t_external_account_reference`, `m_license_plan`, `t_license_assignment`, `ExternalAccountService`, `LicenseService` | `AssetSecretFieldScanTest.scanAllAssetEntitiesForSecretFields`<br>`AssetServiceTest.testLicenseAllocationLimitCas`<br>`AssetOffboardingServiceTest.testProviderRevokeConfirmationTimeout` | **PASS** |
| **AS-R3** | 実地棚卸し差異照合・紛失インシデント追跡・NF-01退社ゲート連携 | `t_asset_inventory_run`, `t_asset_inventory_item`, `AssetInventoryService`, `AssetOffboardingService`, `AssetAlertService` | `AssetServiceTest.testInventoryRunFlow`<br>`AssetAlertServiceTest.testLostAssetIncidentAlert`<br>`AssetOffboardingServiceTest.testOffboardingClearanceBlocking` | **PASS** |
| **AS-R4** | 認可スコープ・要員マイポータル・期限通知・監査ログ | `AssetScopeService`, `MyAssetApiController`, `AssetAlertService`, `AssetLifecycleScheduler`, `templates/my/assets.html` | `MyAssetApiControllerTest.testMyAssetPortalFlow`<br>`AssetAlertServiceTest.testCheckOverdueAssignments`<br>`AssetApiControllerTest` | **PASS** |
| **CR-01** | 認証・認可 (Spring Security / ActionPermissionResolver / DataScope) | `SecurityConfig`, `ActionPermissionResolver`, `AssetScopeService` | `AssetApiControllerTest`, `MyAssetApiControllerTest` | **PASS** |
| **CR-02** | 状態・競合・冪等性 (CAS / FOR UPDATE 排他) | `AssetAssignmentServiceImpl`, `LicenseServiceImpl` | `AssetAssignmentConcurrencyTest` (並行4スレッド排他実証) | **PASS** |
| **CR-03** | データ・マイグレーション (V129/V130, V1/H2同期) | `V129`, `V130`, `schema-asset-account-license-lifecycle-h2.sql`, `V1__create_tables.sql` | `AssetEntityMapperTest`, 全テストコンテキスト起動 | **PASS** |
| **CR-04** | 監査・セキュリティ・秘密非保存 (No Secrets Policy) | `AssetSecretFieldScanTest`, `ApiAuditFilter`, `t_asset_event` | `AssetSecretFieldScanTest` (0 secret fields 検証) | **PASS** |
| **CR-05** | UI・レスポンシブ・国際化 | `templates/asset/*.html`, `templates/my/assets.html`, `static/js/modules/asset*.js` | MockMvc HTML 取得 & レスポンス検証 | **PASS** |
| **CR-06** | テスト完全性 (Fast H2 / 並行性 / ゲート連携 スキップ0件) | 全 8 テストクラス (20 メソッド) | `mvn test` (20/20 tests PASS, 0 failure, 0 error) | **PASS** |

---

## 3. 6大必須条件の検証エビデンス

1. **同一assetの期間重複貸与を並行testで拒否する (AS-R1.3 / CR-02)**:
   - `AssetAssignmentConcurrencyTest.testConcurrentAssignmentOnSameAsset`: 4スレッド同時貸与で確実に1件のみ成功、3件拒否（409/業務例外）。
2. **password/token/recovery code用column/DTO/logを作らない (AS-R2.3 / CR-04)**:
   - `AssetSecretFieldScanTest.scanAllAssetEntitiesForSecretFields`: 全Entity/DTOフィールドをリフレクションスキャンし秘密情報フィールド 0 件を確認。
3. **external revoke requestとconfirmed resultを区別し、timeoutを成功扱いにしない (AS-R2.4)**:
   - `AssetOffboardingServiceTest.testProviderRevokeConfirmationTimeout`: FAILED_OR_TIMEOUT 時に CONFIRMED と判定されないことを検証。
4. **退社case未返却/未失効block、例外承認、scope、棚卸し差異をtestする (AS-R3.4 / AS-R3.2)**:
   - `AssetOffboardingServiceTest.testOffboardingClearanceBlocking`: 未返却端末存在時の退社ブロック（`clearancePassed=false`）と、`LIFECYCLE_EXCEPTION` 特例承認によるパス（`clearancePassed=true`, `waived=true`）を検証。
   - `AssetServiceTest.testInventoryRunFlow`: 実地棚卸しにおける差異（`DISCREPANCY`）集計とスナップショット確定を検証。
5. **移管/返却/紛失/廃棄履歴を上書きしない (AS-R1.4)**:
   - `AssetEventServiceImpl`: 追記のみ（INSERT-only）で `t_asset_event` に全履歴を蓄積。
6. **資産件数reconciliation、未返却一覧、secret scan、rollback/runbookをReviewへ渡す (CR-03 / CR-04)**:
   - `runbook.md` に運用・初期移行・緊急ロールバック手順を完備。
