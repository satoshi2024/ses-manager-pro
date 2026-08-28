# タスク一覧: 資産・アカウント・ライセンス管理（NF-09）

---

## 0. インベントリ調査 & DG-09 決定台帳の確定
- [x] **Task 0.1**: 要件・設計・不変条件の策定と DG-09 決定台帳の作成
  - `.kiro/specs/asset-account-license-lifecycle/requirements.md` 作成
  - `.kiro/specs/asset-account-license-lifecycle/design.md` 作成
  - `.kiro/specs/asset-account-license-lifecycle/inventory.md` 作成
  - `.kiro/specs/asset-account-license-lifecycle/review-ledger.md` 作成

---

## F1. DDL・マイグレーション・Entity・Mapper 実装
- [x] **Task F1.1**: DDL マイグレーション & スキーマ同期
  - `src/main/resources/db/migration/V129__asset_account_license_lifecycle.sql`
  - `src/main/resources/db/migration/V130__asset_account_license_menu_permissions.sql`
  - `src/test/resources/sql/schema-asset-account-license-lifecycle-h2.sql`
  - `src/main/resources/db/migration/V1__create_tables.sql` 同期
  - `src/test/resources/application-test.yml` 同期
- [x] **Task F1.2**: Entity 9クラス & Mapper 9インターフェース実装
  - `Asset`, `AssetAssignment`, `AssetEvent`, `AssetInventoryRun`, `AssetInventoryItem`, `ExternalAccountSystem`, `ExternalAccountReference`, `LicensePlan`, `LicenseAssignment`
  - `AssetMapper`, `AssetAssignmentMapper`, `AssetEventMapper`, `AssetInventoryRunMapper`, `AssetInventoryItemMapper`, `ExternalAccountSystemMapper`, `ExternalAccountReferenceMapper`, `LicensePlanMapper`, `LicenseAssignmentMapper`
- [x] **Task F1.3**: Entity / Mapper / 秘密非保存スキャン単体テスト
  - `src/test/java/com/ses/mapper/AssetEntityMapperTest.java` (PASS)
  - `src/test/java/com/ses/service/AssetSecretFieldScanTest.java` (PASS)

---

## F2. ドメインサービス & 期間排他 & 席数CAS & イベント台帳
- [x] **Task F2.1**: 資産管理サービス & 不変イベント台帳
  - `AssetService`, `AssetServiceImpl`
  - `AssetEventService`, `AssetEventServiceImpl`
- [x] **Task F2.2**: 貸与管理サービス & 期間重複排除の並行保護
  - `AssetAssignmentService`, `AssetAssignmentServiceImpl`
  - `AssetAssignmentConcurrencyTest` (マルチスレッド並行貸与排他検証 PASS)
- [x] **Task F2.3**: 外部アカウント参照 & 秘密非保存 & ライセンス席数 CAS 統制
  - `ExternalAccountService`, `ExternalAccountServiceImpl`
  - `LicenseService`, `LicenseServiceImpl`
  - `AssetInventoryService`, `AssetInventoryServiceImpl`
  - `AssetScopeService`, `AssetScopeServiceImpl`
  - `AssetServiceTest` (PASS)

---

## A1. 管理画面・棚卸し・外部アカウント UI & API
- [x] **Task A1.1**: 資産台帳管理画面 & API
  - `AssetApiController`, `AssetAssignmentApiController`, `AssetPageController`
  - `templates/asset/list.html`, `static/js/modules/asset.js`
- [x] **Task A1.2**: 棚卸し実施画面 & 差異照合 API
  - `AssetInventoryApiController`
  - `templates/asset/inventory.html`, `static/js/modules/asset-inventory.js`
- [x] **Task A1.3**: 外部アカウント・ライセンス管理画面 & API
  - `ExternalAccountApiController`, `LicenseApiController`
  - `templates/asset/accounts.html`, `static/js/modules/asset-accounts.js`
  - `ActionPermissionResolver` 権限解決登録
  - `AssetApiControllerTest` (PASS)

---

## B1. 期限監視・紛失時初動・通知スケジューラ・要員マイポータル
- [x] **Task B1.1**: 期限監視 & 紛失時初動通知スケジューラ
  - `AssetAlertService`, `AssetAlertServiceImpl`
  - `AssetLifecycleScheduler`
- [x] **Task B1.2**: 要員マイポータル画面 & API
  - `MyAssetPageController`, `MyAssetApiController`
  - `templates/my/assets.html`, `static/js/modules/my-assets.js`
  - `AssetAlertServiceTest`, `MyAssetApiControllerTest` (PASS)

---

## B2. NF-01 退社ゲート連携 & 外部プロバイダ連携
- [x] **Task B2.1**: 退社時ハードウェア未返却 / 外部アカウント未失効の退社ブロック・例外承認連携
  - `AssetOffboardingService`, `AssetOffboardingServiceImpl`
  - `OffboardingClearanceResultDto`
- [x] **Task B2.2**: 外部プロバイダ連携クライアント & 非同期失効要求・確認分離
  - `ExternalAccountProviderClient`, `MockExternalAccountProviderClientImpl`
- [x] **Task B2.3**: 退社連携 & 外部連携の自動テスト
  - `AssetOffboardingServiceTest` (PASS)

---

## M. 全量検証・Runbook・決定台帳更新・独立Review引渡し
- [x] **Task M.1**: テストスイート全量実行・Reconciliation検証 (20/20 tests PASS)
- [x] **Task M.2**: Runbook & 移行手順書・ロールバック手順の整備 (`runbook.md`)
- [x] **Task M.3**: レビュー台帳・決定台帳・完了対応表の更新 (`review-ledger.md`)
