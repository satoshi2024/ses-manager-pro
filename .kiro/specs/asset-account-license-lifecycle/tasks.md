# tasks.md: 資産・アカウント・ライセンス管理（NF-09）実装タスク台帳

---

## Review follow-up（第6回Review P1是正）

### Task R6.1: NF-01 3大blocker台帳・承認適用の接続
- **Status**: [x] COMPLETED
- **Objective**: `ResignationGateChecker` が `AssetOffboardingService.checkOffboardingClearance` の未返却資産・未失効account・未解放licenseを実照合し、例外は承認済み `LIFECYCLE_EXCEPTION` と対象一致を検証した永続台帳だけを採用する。
- **Implementation**: `AssetOffboardingWaiver` / `t_asset_offboarding_waiver`（V131）を追加し、`ApprovalEngine` の承認適用から台帳へ追記する。プロセスメモリMapや任意IDの受容は行わない。
- **Test requirements**: `ResignationGateFailureDrillTest.testResignationGateUsesAssetOffboardingBlockersAndPersistedWaiver` で3 blockerの実照合、承認対象一致、WAIVED後の再起動可能な永続判定を確認する。
- **Demo / rollback**: 承認申請・waiver台帳・退社gateのDB行を照合する。失敗時はR6.1の実装とV131を個別revertする。

### Task R6.2: 返却・免除・license解放・棚卸しの競合保護
- **Status**: [x] COMPLETED
- **Objective**: 返却と免除を資産→貸与の固定lock orderに統一し、assignment/assetのCAS結果を検証する。licenseは割当行→plan行をロックして終端CAS後に席数を減算し、棚卸しはrun→itemのlock orderで確定と明細更新を直列化する。
- **Test requirements**: `AssetBoundaryAndLifecycleIntegrationTest` の返却/免除、license二重解放、棚卸し二重確定の並行テストで成功者1件・終端event1件・席数整合をassertする。
- **Demo / rollback**: 競合時の409/業務例外と最終DB状態を確認する。失敗時はR6.2の実装とテストを個別revertする。

### Task R6.3: Provider request冪等性・identifierログ保護
- **Status**: [x] COMPLETED
- **Objective**: unique `idempotency_key` とatomic claimで同一keyのprovider再送および別key上書きを拒否し、account identifierはログ・blockerメッセージでマスクする。secret scanのgetter検出も拡張する。
- **Test requirements**: `AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency` と `AssetComprehensiveSecretScanTest` で同一key再送なし、別key拒否、timeout/UNKNOWN分類、未マスクidentifier 0件を確認する。
- **Demo / rollback**: provider request counterとログ出力を照合する。失敗時はR6.3の実装・scan変更を個別revertする。

### Task R6.4: Review再検証・台帳更新
- **Status**: [x] COMPLETED
- **Objective**: NF-09専用Fast、MySQL 8、関連承認/退社gate回帰を再実行し、P1対応、V131、reconciliation、未返却一覧、secret scan、runbookを独立Reviewへ引き渡す。
- **Result**: NF-09専用Fast `69/69 PASS`、MySQL `3/3 PASS`、関連回帰 `0 failure / 0 error / 0 skipped`。リポジトリ全体Fast gateはBaseにも存在する対象外/環境依存失敗を含むため未PASSのまま記録する。
- **Demo / rollback**: 最終remote Headを `git rev-parse HEAD` と `git ls-remote origin refs/heads/codex/asset-account-license-lifecycle` で固定し、PRは作成しない。

---

## Review follow-up（第4回Review P1是正）

### Task R5.1: 第5回Review P1契約差分の再確定
- **Status**: [x] COMPLETED
- **Objective**: Provider timeout/分類不能応答、営業/マネージャーの資産scope、終端履歴保持の要求・設計・実装・テスト契約を一つに確定する。
- **Decision**: timeout/5xx/429は `PENDING_CONFIRMATION`、応答形式を分類できない場合だけ `UNKNOWN` とし、いずれも `revoke_confirmed_at` が設定されるまで退社blockerを維持する。営業は現任担当要員への現在貸与のみ（owner法人追加制限なし）、マネージャーは管轄要員への現在貸与かつ共有/管轄法人、未貸与は不可とする。`RETURNED`/`REVOKED`/`RELEASED` の終端履歴は論理削除を拒否する。
- **Demo / rollback**: `requirements.md` / `design.md` / `tasks.md` / `review-ledger.md` / `review-evidence.md` とprovider実装・境界テストを同一commit系列で確認する。失敗時はR5変更を個別revertする。

### Task R5.2: P1実装・境界assertion
- **Status**: [x] COMPLETED
- **Objective**: 営業/マネージャーscopeの未貸与・法人境界をSQLでfail-closedにし、provider `UNKNOWN` を永続化・ポーリング対象に加え、貸与/アカウント/ライセンス終端履歴のsoft-deleteを拒否する。
- **Test requirements**: `AssetBoundaryAndLifecycleIntegrationTest` で timeout=`PENDING_CONFIRMATION`、分類不能=`UNKNOWN`、復旧後のみ`REVOKED`、営業の別法人担当資産許可、未貸与/担当外拒否、マネージャー法人交差拒否、`RETURNED`/`REVOKED`/`RELEASED` の削除拒否をassertする。
- **Demo / rollback**: NF-09対象Fast suite 69/69 PASS。失敗時は実装・テスト変更を個別revertする。

### Task R5.3: Base/Head全体Fast比較
- **Status**: [x] COMPLETED（比較済み。全体Fast gateは未PASS）
- **Objective**: Base commitと最終Headでリポジトリ全体 `mvn test` を同一条件で実行し、CR-06の既存失敗クラスがBase既存か、今回変更起因かを判定する。
- **Test requirements**: Base worktreeとfeature worktreeのSurefire結果をクラス・失敗数・環境依存エラー単位で比較する。NF-09対象suiteの69/69とMySQL 3/3は別gateとして維持する。
- **Result**: Base `b9a3a77f0dd44640ea4850e6ee93b822dc5af0fd` は `tests=3060, failures=2, errors=16, skipped=0`。R5時点Head `e6659c90` は固定seed `27838638095700` で `tests=3118, failures=2, errors=11, skipped=0`、R6実装Head `f37501fc79ebfed006887a8c920f3c7c2c5bc709` は `tests=3123, failures=2, errors=11, skipped=0`。R6実装Headの残存8クラス（ControllerTransactionalBan / PinningHttpsTransport / ProductionSecurityConfiguration / PrometheusScraperLabE2E / CapacityBaselineScript / ProjectSkillServiceImpl / WebhookNotifierLoopback / I18nJsController）はBaseにも存在し、今回のfeature対象外またはloopback・固定ID・production設定環境に起因する。Head初回比較で検出した `TransactionalRollbackForAuditTest` と `MyAssetApiControllerTest` は今回変更起因として修正し、関連12/12、NF-09対象69/69、MySQL 3/3、migration smoke 4/4を再実行してPASSした。Base固有の `MyLifecycleApiControllerTest` はHeadのテスト集合差による順序差であり、feature失敗とは判定しない。
- **Demo / rollback**: 比較結果を本Task・`review-ledger.md`・`review-evidence.md`へ記録し、全体gateをPASSとは記録しない。実装起因の修正はR6.1〜R6.3のcommitへ個別反映する。

### Task R4.1: scope / DocumentLink / soft-delete 契約の再確定
- **Status**: [x] COMPLETED
- **Objective**: `owner_company_id` の実体（`m_organization_unit.legal_entity_id`）を確定し、法人A/B・営業・マネージャー・要員の許可/拒否を同一スコープで定義する。存在しないDocument IDやリンク有無だけでDocumentアクセスを許可しない。論理削除は未返却貸与・未失効アカウント・未解放ライセンスを回避できず、返却・失効済み・解放済みの終端履歴を削除できない。
- **Test requirements**: 実在 `t_document` と `ASSET_ASSIGNMENT` link を作り、detail/download/list の無関係要員403、返却/移管後の旧assignment文書再評価、法人A/B・管理組織・営業担当外・空集合のfail-closedを検証する。営業は担当要員への現在貸与のみ（法人追加制限なし）、マネージャーは現在貸与と法人条件の積集合、未貸与資産は拒否する。`RETURNED`/`REVOKED`/`RELEASED` 終端行の論理削除拒否も検証する。
- **Demo / rollback**: NF-09対象suiteの実行で 69/69 PASS。失敗時は本Taskの実装・テスト・spec変更のみrevertする。

### Task R4.2: 全Java secret scan とCR-01 consumer coverage
- **Status**: [x] COMPLETED
- **Objective**: `src/main/java` 全Javaを対象に、ログ文言のキーワードではなく未マスクのsecret/PII値の式・multiline呼出し・監査payloadを検出する。資産/アカウント/ライセンスの一覧・詳細・event/history・CSV・通知・portalで同一scopeを適用する。
- **Test requirements**: 既存の正当な状態ラベルを誤検知せず、`accountIdentifier` 等の未マスク値・例外連結・audit payloadを検出する静的テストを実行する。sales/manager/engineer/adminの肯定・否定系を各consumerで確認する。
- **Demo / rollback**: `AssetComprehensiveSecretScanTest` 4/4、role/document scope を含む対象suite 69/69 PASS を台帳へ記録した。失敗時は対象変更をrevertする。

### Task R4.3: independent evidence / M handoff
- **Status**: [x] COMPLETED（証跡パッケージ準備済み。独立Reviewは未実施）
- **Objective**: fast/MySQL実測を同一remote Headで再実行し、reconciliation・未返却一覧・secret scan結果・rollback/runbookをReviewへ引き渡す。証跡の未実測をPASSと記録しない。
- **Test requirements**: 対象Fast suite 69/69、MySQL asset 3/3、migration smoke 4/4、scheduler lock 1/1 は skip=0 で記録した。migration適用、並行貸与、返却/免除、license CAS、provider timeout/UNKNOWN/idempotency、offboarding blocker/exception、inventory discrepancy、営業/マネージャーの現在貸与scope、終端履歴の論理削除拒否も証跡化した。リポジトリ全体の `mvn test` はBaseとの比較結果を添えて記録し、全体PASSとは記録しない。
- **Demo / rollback**: `git ls-remote` と検証ログのHead一致を示す。runbookに手順・バックアップ復旧・ロールバック境界を残す。
- **Rollback**: Review handoffのみ取り消す場合は台帳修正、実装を戻す場合はTask R4.1/R4.2のコミットを個別revertする。

---

## 0. インベントリ調査 & DG-09 決定台帳の確定

### Task 0.1: 要件・設計・不変条件の策定と DG-09 決定台帳の作成
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1`, `AS-R2`, `AS-R3`, `AS-R4`, `CR-01`〜`CR-06`
- **Objective**: 既存資産（`Engineer`, `SysUser`, `DocumentLink`, `Notification`, `ApprovalEngine`）とNF-09の所有境界を特定し、DG-09（状態6区分・秘密非保存・ライセンスCAS・NF-01退社3大blocker連携・外部プロバイダ境界）を確定する。
- **実装内容**:
  - `.kiro/specs/asset-account-license-lifecycle/requirements.md` 作成
  - `.kiro/specs/asset-account-license-lifecycle/design.md` 作成
  - `.kiro/specs/asset-account-license-lifecycle/inventory.md` 作成
  - `.kiro/specs/asset-account-license-lifecycle/review-ledger.md` 作成
  - `.kiro/roadmap/2026-08-27-post-acceptance-traceability.md` への承認記録（`DG-09-SCOPE-APPROVAL-20260828-01`）追加
- **Test 要件と assertion**: 仕様整合性レビュー（要求ID・境界の相互整合）
- **手動 Demo と証跡**: ドキュメント一式の Git トラッキング確認
- **Rollback**: 仕様ドキュメントの revert
- **未検証事項**: なし

---

## F1. DDL・マイグレーション・Entity・Mapper 実装

### Task F1.1: DDL マイグレーション & スキーマ同期 (V129, V130, V1, H2)
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1`, `AS-R2`, `AS-R3`, `AS-R4`, `CR-03`, `CR-04`
- **Objective**: 資産・貸与・イベント・棚卸し・アカウント参照・ライセンスの9テーブルと退社例外免除台帳を作成し、Flyway V129/V130/V131、V1 baseline、H2テストスキーマ、`application-test.yml` を完全同期する。
- **実装内容**:
  - `src/main/resources/db/migration/V129__asset_account_license_lifecycle.sql`
  - `src/main/resources/db/migration/V130__asset_account_license_menu_permissions.sql`
  - `src/test/resources/sql/schema-asset-account-license-lifecycle-h2.sql`
  - `src/main/resources/db/migration/V1__create_tables.sql` 同期
  - `src/test/resources/application-test.yml` 同期
- **Test 要件と assertion**: H2コンテキスト起動時のDDL適用、テーブル存在確認
- **手動 Demo と証跡**: Spring Boot Test 起動ログでの Flyway/H2 DDL 実行確認
- **Rollback**: `DROP TABLE IF EXISTS ...`、V129/V130/V131削除（本番はrunbookのbackup/restore手順に限定）
- **未検証事項**: なし

### Task F1.2: Entity 9クラス & Mapper 9インターフェース実装
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1`, `AS-R2`, `AS-R3`, `AS-R4`, `CR-03`
- **Objective**: MyBatis-Plus Entity 9クラスおよび Mapper 9インターフェースを実装し、行ロック `selectByIdForUpdate`、CASステータス更新 `updateStatusWithCas`、期間重複判定SQLを整備する。
- **実装内容**:
  - Entity: `Asset`, `AssetAssignment`, `AssetEvent`, `AssetInventoryRun`, `AssetInventoryItem`, `ExternalAccountSystem`, `ExternalAccountReference` (`idempotency_key`, `retry_count`, `next_retry_at`, `last_error_message`), `LicensePlan`, `LicenseAssignment`
  - Mapper: `AssetMapper`, `AssetAssignmentMapper`, `AssetEventMapper`, `AssetInventoryRunMapper`, `AssetInventoryItemMapper`, `ExternalAccountSystemMapper`, `ExternalAccountReferenceMapper`, `LicensePlanMapper`, `LicenseAssignmentMapper`
- **Test 要件と assertion**: `AssetEntityMapperTest` における CRUD、CAS、重複カウントSQL検証 (5/5 PASS)
- **手動 Demo と証跡**: 単体テスト実行ログ
- **Rollback**: Entity/Mapper クラスの削除
- **未検証事項**: なし

### Task F1.3: 包括的シークレットスキャン検証 (No Secrets Policy)
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R2.3`, `CR-04`, `CR-06`
- **Objective**: リフレクションおよびファイル走査を用いて、Entity/DTO、DDL列定義、HTML inputタグ、JS payload、サービス・コントローラー内ログ・例外メッセージにパスワード・APIトークン・平文シークレット等が含まれていないことを自動検証する。
- **実装内容**:
  - `src/test/java/com/ses/service/AssetComprehensiveSecretScanTest.java` (4テストメソッド)
  - `src/test/java/com/ses/service/AssetSecretFieldScanTest.java`
- **Test 要件と assertion**:
  - `scanEntityAndDtoFields`: secretフィールド 0件アサート
  - `scanDdlMigrationFiles`: DDL秘密列 0件アサート
  - `scanHtmlAndJsFiles`: UI秘密input 0件アサート
  - `scanServiceLogsAndExceptions`: 全Javaファイル走査で平文アカウント識別子直接ログ出力・例外シークレット漏洩 0件アサート
- **手動 Demo と証跡**: `mvn test -Dtest=AssetComprehensiveSecretScanTest` (4/4 PASS)
- **Rollback**: テストクラスの revert
- **未検証事項**: なし

---

## F2. ドメインサービス & 期間排他 & 席数CAS & イベント台帳 & DocumentLink

### Task F2.1: 資産管理サービス & 不変イベント台帳 & DocumentLink 連携
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1.1`, `AS-R1.4`, `AS-R4.3`, `CR-03`
- **Objective**: 資産CRUD、ステータス6区分CAS（`IN_STOCK`, `ASSIGNED`, `UNDER_MAINTENANCE`, `LOST`, `DISPOSED`, `RESERVED`）、改ざん不能な追記専用イベント台帳（`t_asset_event`）、および証跡文書の `DocumentLink` (`t_document_link`) 登録を実装する。
- **実装内容**:
  - `AssetService`, `AssetServiceImpl`
  - `AssetEventService`, `AssetEventServiceImpl`
  - `AssetAssignmentServiceImpl` (受渡・返却証跡の `t_document_link` 登録)
- **Test 要件と assertion**:
  - `AssetServiceTest.testAssetLifecycleAndEventHistory`: 資産作成・ステータス更新に伴い `t_asset_event` に追記ログが確実に生成されることのアサート
  - `AssetBoundaryAndLifecycleIntegrationTest.testDocumentEvidenceScopeRejection`: `t_document_link` 登録と無関係要員からのアクセス拒否アサート
- **手動 Demo と証跡**: テスト実行ログ
- **Rollback**: サービス実装の revert
- **未検証事項**: なし

### Task F2.2: 貸与管理サービス & 期間重複排除の並行保護 & 返却直後再貸与
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1.2`, `AS-R1.3`, `CR-02`
- **Objective**: 資産貸与・返却サービスを実装し、行ロック `FOR UPDATE` + 期間重複チェック `[start_date, expected_return_date]` により並行リクエストでの二重貸与を完全に排他し、返却直後の再貸与を正常に許可する。
- **実装内容**:
  - `AssetAssignmentService`, `AssetAssignmentServiceImpl`
  - `AssetAssignmentConcurrencyTest`
  - `AssetBoundaryAndLifecycleIntegrationTest.testReassignImmediatelyAfterReturn`
- **Test 要件と assertion**:
  - `AssetAssignmentConcurrencyTest.testConcurrentAssignmentOnSameAsset`: 4並行スレッドで同一資産へ同時貸与を実行し、成功件数 == 1、失敗件数 == 3 をアサート
  - `AssetBoundaryAndLifecycleIntegrationTest.testReassignImmediatelyAfterReturn`: 返却直後の別要員への再貸与が正常に成功することのアサート
- **手動 Demo と証跡**: 並行性・境界テスト実行ログ
- **Rollback**: サービス実装の revert
- **未検証事項**: なし

### Task F2.3: 外部アカウント参照 & ライセンス席数 CAS 統制 & 実地棚卸し
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R2.1`, `AS-R2.2`, `AS-R2.5`, `AS-R3.1`, `AS-R3.2`
- **Objective**: 外部アカウント参照CRUD・失効確認、有償ライセンス席数CAS（`allocated_count < seat_limit`）、実地棚卸し計画・照合（MATCH/DISCREPANCY/MISSING）、棚卸し確定後の変更拒否を実装する。
- **実装内容**:
  - `ExternalAccountService`, `ExternalAccountServiceImpl`
  - `LicenseService`, `LicenseServiceImpl`
  - `AssetInventoryService`, `AssetInventoryServiceImpl`
  - `AssetScopeService`, `AssetScopeServiceImpl`
- **Test 要件と assertion**:
  - `AssetBoundaryAndLifecycleIntegrationTest.testLicenseSeatLimitBoundaryMinusOneEqualPlusOneAndReassign`: 上限 `-1 / = / +1` 境界および解放後再割当のアサート
  - `AssetBoundaryAndLifecycleIntegrationTest.testLicenseConcurrentAllocationWithCas`: 4スレッドでの席数並行割当CAS保護実証
  - `AssetBoundaryAndLifecycleIntegrationTest.testInventoryDisallowUpdateAndDoubleComplete`: 棚卸し完了後の更新拒否・二重確定拒否のアサート
- **手動 Demo と証跡**: `AssetBoundaryAndLifecycleIntegrationTest` 実行ログ
- **Rollback**: サービス実装の revert
- **未検証事項**: なし

---

## A1. 管理画面・棚卸し・外部アカウント UI & API & 4言語同期

### Task A1.1: 資産台帳管理画面 & API & 390px レスポンシブ
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R1.1`, `AS-R1.2`, `AS-R4.1`, `CR-05`
- **Objective**: 資産台帳一覧・検索・新規登録・編集・貸与モーダル・返却モーダル・イベント履歴モーダルUI（デスクトップ・390pxレスポンシブ対応）および `/api/assets`, `/api/asset-assignments` を実装する。
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

### Task A1.3: 外部アカウント・ライセンス管理画面 & API & 4言語メッセージ同期
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R2.1`, `AS-R2.2`, `AS-R2.5`, `CR-01`, `CR-05`
- **Objective**: 外部SaaSシステム・アカウント参照・ライセンスプラン・割当UIおよび `/api/external-accounts`, `/api/licenses` を実装し、`ActionPermissionResolver` 登録および 4言語リソース（`messages.properties`, `messages_en.properties`, `messages_zh_CN.properties`, `messages_ko.properties`）を完全同期する。
- **実装内容**:
  - `ExternalAccountApiController`, `LicenseApiController`
  - `templates/asset/accounts.html`, `static/js/modules/asset-accounts.js`
  - `ActionPermissionResolver` リソース登録
  - 4言語 `messages*.properties` 同期
- **Test 要件と assertion**:
  - `AssetApiControllerTest.testExternalAccountFlow`: アカウント参照作成・失効確認APIの HTTP 200 アサート
- **手動 Demo と証跡**: API 実行証跡
- **Rollback**: Controller, HTML, JS の revert
- **未検証事項**: なし

---

## B1. 期限監視・紛失時初動・通知スケジューラ・要員マイポータル

### Task B1.1: 期限監視（7/3/当日/週次/リース満了） & 紛失時初動通知スケジューラ
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R3.3`, `AS-R4.2`
- **Objective**: 返却期限 7日前/3日前/当日接近通知、期限超過当日/週次リマインド、リース満了30日前検知、紛失インシデント起票時の緊急一斉通知、および日次午前9時定期実行スケジューラを実装する。
- **実装内容**:
  - `AssetAlertService`, `AssetAlertServiceImpl`
  - `AssetLifecycleScheduler`
- **Test 要件と assertion**:
  - `AssetAlertServiceTest.testCheckOverdueAssignments`: 期限超過および接近通知の生成アサート
  - `AssetAlertServiceTest.testCheckExpiringLeases`: リース満了接近資産の検知アサート
- **手動 Demo と証跡**: `AssetAlertServiceTest` 実行ログ
- **Rollback**: AlertService / Scheduler の revert
- **未検証事項**: なし

### Task B1.2: 要員マイポータル画面 & API
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R4.1`, `AS-R3.3`, `CR-05`
- **Objective**: 要員ポータル（`/my/assets`）において、ログイン要員本人の有効貸与端末・SaaSアカウント・ライセンス確認画面（390pxレスポンシブ）および紛失・盗難自己報告APIを実装する。
- **実装内容**:
  - `MyAssetPageController`, `MyAssetApiController`
  - `templates/my/assets.html`, `static/js/modules/my-assets.js`
- **Test 要件と assertion**:
  - `MyAssetApiControllerTest.testMyAssetPortalFlow`: 要員によるサマリー取得、紛失報告実行（ステータス `LOST` 遷移 & 通知送信）アサート
- **手動 Demo と証跡**: `MyAssetApiControllerTest` 実行ログ
- **Rollback**: Controller, HTML, JS の revert
- **未検証事項**: なし

---

## B2. NF-01 退社ゲート連携 & 外部プロバイダ連携 & Recovery

### Task B2.1: 退社時 3大残存アイテム（端末・アカウント・ライセンス）の退社ブロック・例外承認連携
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R3.4`, `CR-01`
- **Objective**: NF-01 退社ワークフロー（`RESIGN_ASSET_RETURN`）と連動し、(a)未返却端末、(b)未失効外部アカウント、(c)未解放有償ライセンスの 3大残存アイテムを blocker として検出し、`LIFECYCLE_EXCEPTION` 承認時に例外免除（WAIVED）バイパスを適用するクリアランスサービスを実装する。
- **実装内容**:
  - `AssetOffboardingService`, `AssetOffboardingServiceImpl`
  - `OffboardingClearanceResultDto`
- **Test 要件と assertion**:
  - `AssetBoundaryAndLifecycleIntegrationTest.testOffboardingThreeBlockers`: 3大項目残存時の `clearancePassed == false` アサート、例外承認後の `clearancePassed == true` & `waived == true` アサート
- **手動 Demo と証跡**: `AssetBoundaryAndLifecycleIntegrationTest` 実行ログ
- **Rollback**: サービス実装の revert
- **未検証事項**: なし

### Task B2.2: 外部プロバイダ連携クライアント & 非同期失効要求・確認分離 & Recovery/Idempotency
- **Status**: [x] COMPLETED
- **Requirements ID**: `AS-R2.4`, `CR-02`
- **Objective**: 外部SaaS/IdPプロバイダに対する失効要求と確証確認を分離し、タイムアウト・5xx・429は `PENDING_CONFIRMATION`、応答形式を分類できない場合だけ `UNKNOWN` として永続化する。`idempotency_key`, `retry_count`, `next_retry_at` による指数バックオフポーリングと、二重確認時の冪等性を実装する。
- **実装内容**:
  - `ExternalAccountProviderClient`, `MockExternalAccountProviderClientImpl`
  - `ExternalAccountServiceImpl.requestRevokeWithIdempotency`
  - `ExternalAccountServiceImpl.processPendingRevokePollJob`
- **Test 要件と assertion**:
  - `AssetBoundaryAndLifecycleIntegrationTest.testProviderRecoveryAndIdempotency`: timeout/失敗時の `PENDING_CONFIRMATION` 永続化、分類不能応答時の `UNKNOWN` blocker、ポーリングジョブによる復旧後確認完了、二重確認時の冪等性アサート
- **手動 Demo と証跡**: `AssetBoundaryAndLifecycleIntegrationTest` 実行ログ
- **Rollback**: アダプタークラスの revert
- **未検証事項**: 実SaaSとのOAuth通信（NF-05開工時に委譲）

### Task B2.3: MySQL 8 実コンテナ統合テスト & Shard Inventory 登録 (CR-06)
- **Status**: [x] COMPLETED
- **Requirements ID**: `CR-06`
- **Objective**: Testcontainers による MySQL 8 実コンテナ上で Flyway V129/V130/V131 DDL、行ロック `FOR UPDATE`、CAS更新を検証する `@Tag("mysql")` テストを実装し、`mysql-shard-1.txt` に登録して `MySqlTestShardInventoryTest` と完全一致させる。
- **実装内容**:
  - `src/test/java/com/ses/migration/AssetMySqlIntegrationTest.java`
  - `scripts/test-suites/mysql-shard-1.txt` 登録
- **Test 要件と assertion**:
  - `MySqlTestShardInventoryTest.testInventoryMatchesTaggedClasses`: PASS
  - `mvn test -Pmysql-tests -Dtest=AssetMySqlIntegrationTest`: **3/3 PASS (0 failure, 0 error, 0 skipped)**
- **手動 Demo と証跡**:
  - `AssetMySqlIntegrationTest.testAssetCreationAndRowLockOnMySQL`: PASS
  - `AssetMySqlIntegrationTest.testAssetAssignmentLifecycleOnMySQL`: PASS
  - `AssetMySqlIntegrationTest.testExternalAccountAndLicenseCasOnMySQL`: PASS
- **Rollback**: テストクラスの revert
- **未検証事項**: なし

---

## M. 全量検証・Runbook・決定台帳更新・独立Review引渡し

### Task M.1: テストスイート全量実行・スキップ 0 検証 (Fast / 並行 / ゲート / MySQL)
- **Status**: [x] COMPLETED（NF-09/MySQL gate PASS、全体Fast gateはBase既存/環境側未PASS）
- **Requirements ID**: `CR-06`
- **Objective**: NF-09 で作成・改修した対象テストとMySQLゲートを実行し、スキップ 0 件、0 Failure / 0 Error を確認する。リポジトリ全体Fast gateの合否は既存テスト・実行環境の結果と分離して記録する。
- **Test 要件と assertion**:
  - NF-09対象Fast Suite: 69/69 tests PASS (0 skipped, 0 failed, 0 errors)
  - 実退社gate drill: `ResignationGateFailureDrillTest` 9/9 tests PASS (0 skipped, 0 failed, 0 errors)
  - MySQL asset Gate: 3/3 tests PASS (0 skipped, 0 failed, 0 errors)
  - V131追随 migration smoke: 4/4 tests PASS (0 skipped, 0 failed, 0 errors)
- **手動 Demo と証跡**:
  - Maven Surefire 対象suite出力ログ (`Tests run: 69, Failures: 0, Errors: 0, Skipped: 0`)
  - Maven Surefire resignation gate出力ログ (`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`)
  - Maven Surefire MySQL asset出力ログ (`Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`)
  - Maven Surefire V131追随migration smoke出力ログ (`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`)
- **全体gate注記（R5/R6比較）**: R5時点Head `e6659c90` は `tests=3118, failures=2, errors=11, skipped=0`、R6実装Head `f37501fc79ebfed006887a8c920f3c7c2c5bc709` は `tests=3123, failures=2, errors=11, skipped=0`。残存は `ControllerTransactionalBanTest`、`PinningHttpsTransportTest`、`ProductionSecurityConfigurationTest`、`PrometheusScraperLabE2ETest`、`CapacityBaselineScriptTest`、`ProjectSkillServiceImplTest`、`WebhookNotifierLoopbackIntegrationTest`、`I18nJsControllerTest`。Baseにも同じ残存クラスがあり、今回のfeature起因とは判定しない。最新R6実装Headではfeature対象の追加修正を反映し、NF-09専用69/69、MySQL asset 3/3、migration smoke 4/4を再確認した。
- **Rollback**: なし
- **未検証事項**: リポジトリ全体Fast gateのPASS、および独立ReviewのPASS。全体Fast gateは未PASSのままなので、独立ReviewでBase差分と環境復旧後の再実行要否を判断する。

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
- **Objective**: 要求ID（`AS-R1`〜`AS-R4`）、非機能要件（`CR-01`〜`CR-06`）、実装ファイル、テストクラスを明確に記録したレビュー引渡し台帳を作成・更新する。
- **実装内容**:
  - `.kiro/specs/asset-account-license-lifecycle/review-ledger.md`
- **手動 Demo と証跡**: レビュー台帳確認
- **Rollback**: 台帳 revert
- **未検証事項**: なし
