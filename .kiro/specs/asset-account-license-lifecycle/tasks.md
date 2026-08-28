# tasks.md: 資産・アカウント・ライセンス管理（NF-09）実装タスク台帳

---

## 0. インベントリ調査 & DG-09 決定台帳の確定

### Task 0.1: 要件・設計・不変条件の策定と DG-09 決定台帳の作成
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1`, `AS-R2`, `AS-R3`, `AS-R4`, `CR-01`〜`CR-06`
- **Objective**: 既存資産（`Engineer`, `SysUser`, `DocumentLink`, `Notification`, `ApprovalEngine`）とNF-09の所有境界を特定し、DG-09（資産識別・秘密非保存・ライセンスCAS・NF-01退社連携・外部プロバイダ境界）を確定する。
- **実装内容**:
  - `.kiro/specs/asset-account-license-lifecycle/requirements.md` 作成
  - `.kiro/specs/asset-account-license-lifecycle/design.md` 作成
  - `.kiro/specs/asset-account-license-lifecycle/inventory.md` 作成
  - `.kiro/specs/asset-account-license-lifecycle/review-ledger.md` 作成
  - `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md` への承認記録追加
- **Test 要件と assertion**: 仕様整合性レビュー（要求ID・境界の相互整合）
- **手動 Demo と証跡**: ドキュメント一式の Git トラッキング確認
- **Rollback**: 仕様ドキュメントの revert
- **未検証事項**: なし

---

## F1. DDL・マイグレーション・Entity・Mapper 実装

### Task F1.1: DDL マイグレーション & スキーマ同期
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1`, `AS-R2`, `AS-R3`, `AS-R4`, `CR-03`, `CR-04`
- **Objective**: 資産・貸与・イベント・棚卸し・アカウント参照・ライセンスの9テーブルを作成し、Flyway V129/V130、V1 baseline、H2テストスキーマ、`application-test.yml` を完全同期する。
- **実装内容**:
  - `src/main/resources/db/migration/V129__asset_account_license_lifecycle.sql`
  - `src/main/resources/db/migration/V130__asset_account_license_menu_permissions.sql`
  - `src/test/resources/sql/schema-asset-account-license-lifecycle-h2.sql`
  - `src/main/resources/db/migration/V1__create_tables.sql` 同期
  - `src/test/resources/application-test.yml` 同期
- **Test 要件と assertion**: H2コンテキスト起動時のDDL適用、テーブル存在確認
- **手動 Demo と証跡**: Spring Boot Test 起動ログでの Flyway/H2 DDL 実行確認
- **Rollback**: `DROP TABLE IF EXISTS ...`、V129/V130削除
- **未検証事項**: なし

### Task F1.2: Entity 9クラス & Mapper 9インターフェース実装
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1`, `AS-R2`, `AS-R3`, `AS-R4`, `CR-03`
- **Objective**: MyBatis-Plus Entity 9クラスおよび Mapper 9インターフェースを実装し、行ロック `selectByIdForUpdate`、CASステータス更新 `updateStatusWithCas`、期間重複判定SQLを整備する。
- **実装内容**:
  - Entity: `Asset`, `AssetAssignment`, `AssetEvent`, `AssetInventoryRun`, `AssetInventoryItem`, `ExternalAccountSystem`, `ExternalAccountReference`, `LicensePlan`, `LicenseAssignment`
  - Mapper: `AssetMapper`, `AssetAssignmentMapper`, `AssetEventMapper`, `AssetInventoryRunMapper`, `AssetInventoryItemMapper`, `ExternalAccountSystemMapper`, `ExternalAccountReferenceMapper`, `LicensePlanMapper`, `LicenseAssignmentMapper`
- **Test 要件と assertion**: `AssetEntityMapperTest` における CRUD、CAS、重複カウントSQL検証
- **手動 Demo と証跡**: 単体テスト実行ログ
- **Rollback**: Entity/Mapper クラスの削除
- **未検証事項**: なし

### Task F1.3: Entity / Mapper / 秘密非保存スキャン単体テスト
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R2.3`, `CR-04`, `CR-06`
- **Objective**: リフレクションを用いて全Entity/DTOに対象外の秘密情報（パスワード、APIトークン、シークレット等）が含まれていないことを自動検知するテストを実装・検証する。
- **実装内容**:
  - `src/test/java/com/ses/mapper/AssetEntityMapperTest.java`
  - `src/test/java/com/ses/service/AssetSecretFieldScanTest.java`
- **Test 要件と assertion**:
  - `AssetSecretFieldScanTest.scanAllAssetEntitiesForSecretFields`: secretフィールド 0件をアサート (`assertThat(secretFieldViolations).isEmpty()`)
  - `AssetEntityMapperTest`: 各種CRUDおよびカスタムクエリ 5/5 件 PASS
- **手動 Demo と証跡**: `mvn test -Dtest=AssetSecretFieldScanTest,AssetEntityMapperTest` 実行結果
- **Rollback**: テストクラスの revert
- **未検証事項**: なし

---

## F2. ドメインサービス & 期間排他 & 席数CAS & イベント台帳

### Task F2.1: 資産管理サービス & 不変イベント台帳
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1.1`, `AS-R1.4`, `AS-R4.3`
- **Objective**: 資産CRUD、ステータス変更CAS、および改ざん不能な追記専用イベント台帳（`t_asset_event`）を実装する。
- **実装内容**:
  - `AssetService`, `AssetServiceImpl`
  - `AssetEventService`, `AssetEventServiceImpl`
- **Test 要件と assertion**:
  - `AssetServiceTest.testAssetLifecycleAndEventHistory`: 資産作成・ステータス更新に伴い `t_asset_event` に追記ログが確実に生成されることのアサート
- **手動 Demo と証跡**: テスト実行ログ (`Asset event recorded: assetId=...`)
- **Rollback**: サービス実装の revert
- **未検証事項**: なし

### Task F2.2: 貸与管理サービス & 期間重複排除の並行保護
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1.2`, `AS-R1.3`, `CR-02`
- **Objective**: 資産貸与・返却サービスを実装し、行ロック `FOR UPDATE` + 期間重複チェック `[start_date, expected_return_date]` により並行リクエストでの二重貸与を完全に排他する。
- **実装内容**:
  - `AssetAssignmentService`, `AssetAssignmentServiceImpl`
  - `AssetAssignmentConcurrencyTest`
- **Test 要件と assertion**:
  - `AssetAssignmentConcurrencyTest.testConcurrentAssignmentOnSameAsset`: 4並行スレッドで同一資産へ同時貸与を実行し、成功件数 == 1、失敗（409/業務例外）件数 == 3 をアサート
- **手動 Demo と証跡**: 並行性テスト実行ログ
- **Rollback**: サービス実装の revert
- **未検証事項**: なし

### Task F2.3: 外部アカウント参照 & 秘密非保存 & ライセンス席数 CAS 統制
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R2.1`, `AS-R2.2`, `AS-R2.4`, `AS-R2.5`, `AS-R3.1`, `AS-R3.2`
- **Objective**: 外部アカウント参照CRUD・失効確認、有償ライセンス席数CAS（`allocated_count < seat_limit`）、実地棚卸し計画・照合（MATCH/DISCREPANCY/MISSING）、データスコープ解決を実装する。
- **実装内容**:
  - `ExternalAccountService`, `ExternalAccountServiceImpl`
  - `LicenseService`, `LicenseServiceImpl`
  - `AssetInventoryService`, `AssetInventoryServiceImpl`
  - `AssetScopeService`, `AssetScopeServiceImpl`
- **Test 要件と assertion**:
  - `AssetServiceTest.testLicenseAllocationLimitCas`: 席数上限（1席）超過時の割当拒否アサート
  - `AssetServiceTest.testInventoryRunFlow`: 棚卸しスナップショット作成、差異記録、確定固定のアサート
- **手動 Demo と証跡**: `AssetServiceTest` 実行ログ
- **Rollback**: サービス実装の revert
- **未検証事項**: なし

---

## A1. 管理画面・棚卸し・外部アカウント UI & API

### Task A1.1: 資産台帳管理画面 & API
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1.1`, `AS-R1.2`, `AS-R4.1`
- **Objective**: 資産台帳一覧・検索・新規登録・編集・貸与モーダル・返却モーダル・イベント履歴モーダルUIおよび `/api/assets`, `/api/asset-assignments` を実装する。
- **実装内容**:
  - `AssetApiController`, `AssetAssignmentApiController`, `AssetPageController`
  - `templates/asset/list.html`, `static/js/modules/asset.js`
- **Test 要件と assertion**:
  - `AssetApiControllerTest.testAssetCrudAndAssignmentFlow`: 資産登録・貸与・返却APIの HTTP 200 / code=200 アサート
- **手動 Demo と証跡**: MockMvc API 実行証跡
- **Rollback**: Controller, HTML, JS の revert
- **未検証事項**: なし

### Task A1.2: 棚卸し実施画面 & 差異照合 API
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R3.1`, `AS-R3.2`, `AS-R4.1`
- **Objective**: 実地棚卸し計画開始、明細一覧、実地確認入力、差異照合、確定固定UIおよび `/api/asset-inventory` を実装する。
- **実装内容**:
  - `AssetInventoryApiController`
  - `templates/asset/inventory.html`, `static/js/modules/asset-inventory.js`
- **Test 要件と assertion**: 棚卸しAPI実行テスト
- **手動 Demo と証跡**: API 呼び出し証跡
- **Rollback**: Controller, HTML, JS の revert
- **未検証事項**: なし

### Task A1.3: 外部アカウント・ライセンス管理画面 & API
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R2.1`, `AS-R2.2`, `AS-R2.5`, `CR-01`
- **Objective**: 外部SaaSシステム・アカウント参照・ライセンスプラン・割当UIおよび `/api/external-accounts`, `/api/licenses` を実装し、`ActionPermissionResolver` に登録して認可遮断を解消する。
- **実装内容**:
  - `ExternalAccountApiController`, `LicenseApiController`
  - `templates/asset/accounts.html`, `static/js/modules/asset-accounts.js`
  - `ActionPermissionResolver` リソース登録
- **Test 要件と assertion**:
  - `AssetApiControllerTest.testExternalAccountFlow`: アカウント参照作成・失効確認APIの HTTP 200 アサート
- **手動 Demo と証跡**: API 実行証跡
- **Rollback**: Controller, HTML, JS の revert
- **未検証事項**: なし

---

## B1. 期限監視・紛失時初動・通知スケジューラ・要員マイポータル

### Task B1.1: 期限監視 & 紛失時初動通知スケジューラ
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R3.3`, `AS-R4.2`
- **Objective**: 返却期限超過検知、リース満了接近検知、紛失インシデント起票時の緊急一斉通知、および日次午前9時定期実行スケジューラを実装する。
- **実装内容**:
  - `AssetAlertService`, `AssetAlertServiceImpl`
  - `AssetLifecycleScheduler`
- **Test 要件と assertion**:
  - `AssetAlertServiceTest.testCheckOverdueAssignments`: 期限超過貸与の検知（count > 0）と通知生成アサート
  - `AssetAlertServiceTest.testCheckExpiringLeases`: リース満了接近資産の検知アサート
- **手動 Demo と証跡**: `AssetAlertServiceTest` 実行ログ
- **Rollback**: AlertService / Scheduler の revert
- **未検証事項**: なし

### Task B1.2: 要員マイポータル画面 & API
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R4.1`, `AS-R3.3`
- **Objective**: 要員ポータル（`/my/assets`）において、ログイン要員本人の有効貸与端末・SaaSアカウント・ライセンス確認画面および紛失・盗難自己報告APIを実装する。
- **実装内容**:
  - `MyAssetPageController`, `MyAssetApiController`
  - `templates/my/assets.html`, `static/js/modules/my-assets.js`
- **Test 要件と assertion**:
  - `MyAssetApiControllerTest.testMyAssetPortalFlow`: 要員によるサマリー取得（貸与1件）、紛失報告実行（ステータス `LOST` 遷移 & 通知送信）アサート
- **手動 Demo と証跡**: `MyAssetApiControllerTest` 実行ログ
- **Rollback**: Controller, HTML, JS の revert
- **未検証事項**: なし

---

## B2. NF-01 退社ゲート連携 & 外部プロバイダ連携

### Task B2.1: 退社時ハードウェア未返却 / 外部アカウント未失効の退社ブロック・例外承認連携
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R3.4`, `CR-01`
- **Objective**: NF-01 退社ワークフロー（`RESIGN_ASSET_RETURN`）と連動し、未返却端末・未失効アカウント・未解放ライセンスの残存時に退社完了をブロックし、`LIFECYCLE_EXCEPTION` 承認時に例外免除（WAIVED）バイパスを適用するクリアランスサービスを実装する。
- **実装内容**:
  - `AssetOffboardingService`, `AssetOffboardingServiceImpl`
  - `OffboardingClearanceResultDto`
- **Test 要件と assertion**:
  - `AssetOffboardingServiceTest.testOffboardingClearanceBlocking`: 未返却端末存在時の `clearancePassed == false` アサート、例外承認後の `clearancePassed == true` & `waived == true` アサート
- **手動 Demo と証跡**: `AssetOffboardingServiceTest` 実行ログ
- **Rollback**: サービス実装の revert
- **未検証事項**: なし

### Task B2.2: 外部プロバイダ連携クライアント & 非同期失効要求・確認分離
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R2.4`, `CR-02`
- **Objective**: 外部SaaS/IdPプロバイダに対する失効要求（`requestRevoke`）と確証ステータス確認（`checkRevokeConfirmation`）を分離し、タイムアウトや通信失敗を成功扱いとしないアダプターを実装する。
- **実装内容**:
  - `ExternalAccountProviderClient`, `MockExternalAccountProviderClientImpl`
- **Test 要件と assertion**:
  - `AssetOffboardingServiceTest.testProviderRevokeConfirmationTimeout`: タイムアウト/失敗時に `CONFIRMED` と判定されないことのアサート
- **手動 Demo と証跡**: `AssetOffboardingServiceTest` 実行ログ
- **Rollback**: アダプタークラスの revert
- **未検証事項**: 実SaaSとのOAuth通信（NF-05開工時に委譲）

### Task B2.3: 退社連携 & 外部連携の自動テスト
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R3.4`, `AS-R2.4`, `CR-06`
- **Objective**: 退社時の一括失効・ライセンス解放およびプロバイダ連携の振る舞いを網羅検証する統合テストを実装する。
- **実装内容**:
  - `src/test/java/com/ses/service/AssetOffboardingServiceTest.java`
- **Test 要件と assertion**: 全3ケース PASS
- **手動 Demo と証跡**: `mvn test -Dtest=AssetOffboardingServiceTest` 実行結果
- **Rollback**: テストクラスの revert
- **未検証事項**: なし

---

## M. 全量検証・Runbook・決定台帳更新・独立Review引渡し

### Task M.1: テストスイート全量実行・Reconciliation検証
- **Status**: [x] COMPLETED
- **Requirements ID**: `CR-06`
- **Objective**: NF-09 で作成・改修した全テスト（8クラス・20メソッド）を実行し、0 Failure / 0 Error を確認する。
- **Test 要件と assertion**: 20/20 tests PASS
- **手動 Demo と証跡**: Maven Surefire 出力ログ
- **Rollback**: なし
- **未検証事項**: なし

### Task M.2: Runbook & 移行手順書・ロールバック手順の整備
- **Status**: [x] COMPLETED
- **Requirements ID**: `CR-03`, `CR-04`
- **Objective**: 初期移行手順、日常運用フロー、NF-01連携、紛失時緊急初動、障害時ロールバック手順を詳細化した Runbook を作成する。
- **実装内容**:
  - `.kiro/specs/asset-account-license-lifecycle/runbook.md`
- **手動 Demo と証跡**: Runbook ドキュメント確認
- **Rollback**: ドキュメント revert
- **未検証事項**: なし

### Task M.3: レビュー台帳・決定台帳・完了対応表の更新
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1`〜`AS-R4`, `CR-01`〜`CR-06`
- **Objective**: 要求ID（`AS-R1`〜`AS-R4`）、実装ファイル、テストクラス、Implementation Code Head、Review/Handoff Head を明確に記録したレビュー引渡し台帳を作成・更新する。
- **実装内容**:
  - `.kiro/specs/asset-account-license-lifecycle/review-ledger.md`
- **手動 Demo と証跡**: レビュー台帳確認
- **Rollback**: 台帳 revert
- **未検証事項**: なし
