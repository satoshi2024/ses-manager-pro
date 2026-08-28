# Tasks — 資産・アカウント・ライセンス ライフサイクル管理 (`asset-account-license-lifecycle` / NF-09)

## 0. Discovery & DG-09 決定

- [ ] **Task 0.1: 現行システム・依存関係のインベントリとDG-09合意**
  - **Objective**: 既存の要員（`Engineer`）、ユーザー（`SysUser`）、組織（`Organization`）、文書管理（`Document`）、および退社ワークフロー（`engineer-lifecycle-workflow` / NF-01）との連携境界を整理し、DG-09（資産種別、所有法人、棚卸し頻度、外部IdP/MDM連携範囲、紛失インシデント運用）を決定する。
  - **Guidance**: `inventory.md` を作成し、現行コード・DBスキーマ・認可境界を網羅的にマッピングする。秘密情報を一切保持しない方針を確定する。
  - **Test**: 仕様書間のリンク整合性とDecision項目のレビュー完了。
  - **Demo**: Discovery結果とDG-09決定台帳の確認。

---

## F1. データモデル・DDL・Entity

- [ ] **Task F1.1: 資産・貸与・イベント・アカウント・ライセンスの DDL 作成とスキーマ同期**
  - **Objective**: `m_asset`, `t_asset_assignment`, `t_asset_event`, `t_asset_inventory_run`, `t_asset_inventory_item`, `m_external_account_system`, `t_external_account_reference`, `m_license_plan`, `t_license_assignment` のDDLを作成し、マイグレーション環境へ配置する。
  - **Guidance**: `platform-invariants.md` §4 に従い、V1統合baseline、Flyway増分マイグレーション（着手時 latest+1）、H2スキーマ（`sql/schema-asset-lifecycle-h2.sql`）、および `engineer-schema-h2.sql` を同期する。
  - **Test**: `FlywayMigrationSmokeTest`, `schema-asset-lifecycle-h2.sql` の初期化検証。
  - **Demo**: H2 および MySQL で全9テーブルが正常に生成されることの確認。

- [ ] **Task F1.2: MyBatis-Plus Entity & Mapper 実装**
  - **Objective**: 上記9テーブルに対応する Entity クラスと Mapper インターフェースを実装する。
  - **Guidance**: `@TableName`, `@TableId`, `@TableLogic`, `@Version` アノテーションを付与し、不変イベント台帳は論理削除フラグを持たない設計とする。
  - **Test**: Entity の CRUD 単体テスト。
  - **Demo**: Mapper による SELECT / INSERT 動作確認。

---

## F2. ドメインロジック・期間排他制御・不変イベント台帳

- [ ] **Task F2.1: 資産管理サービス (`AssetService`) & 不変イベント台帳 (`AssetEventService`) 実装**
  - **Objective**: 資産の登録、更新、廃棄、紛失処理と、それに伴う追記型イベント記録を実装する。
  - **Guidance**: 資産ステータス変更時は `version` CAS による楽観ロック制御を行い、変更の都度 `t_asset_event` へ変更前後の状態と操作者を記録する。
  - **Test**: 資産ステータス遷移テスト、CAS競合時の409例外テスト、イベント追記テスト。
  - **Demo**: 資産作成・ステータス変更時にイベント台帳へ正しくレコードが追加されることの確認。

- [ ] **Task F2.2: 貸与管理サービス (`AssetAssignmentService`) & 期間重複排除の並行保護**
  - **Objective**: 資産貸与・返却処理と、同一資産に対する期間重複貸与の絶対拒否ロジックを実装する。
  - **Guidance**: トランザクション内で `SELECT ... FOR UPDATE` による行ロックを行い、重複貸与区間判定を実施する。
  - **Test**: 同一資産への並行貸与マルチスレッドテスト（1件のみ成功し他方が拒否されることの検証）、過去・未来・未定返却日の期間判定テスト。
  - **Demo**: 並行貸与リクエスト発生時の排他動作確認。

- [ ] **Task F2.3: 外部アカウント参照 & 秘密非保存 & ライセンス席数 CAS 統制**
  - **Objective**: 外部アカウント参照の登録・失効確認サービス（`ExternalAccountService`）およびライセンス席数管理サービス（`LicenseService`）を実装する。
  - **Guidance**: 秘密情報（password/token）を一切扱わない設計を遵守。ライセンス割当時は CAS により席数上限超過を原子的に防止する。
  - **Test**: `AssetSecretFieldScanTest`（秘密情報フィールド非存在検証）、ライセンス上限到達時の割当拒否テスト。
  - **Demo**: ライセンス席数満杯時のエラーハンドリング確認。

---

## A1. 管理者画面・棚卸し・外部アカウント管理 UI

- [ ] **Task A1.1: 資産台帳管理画面 (`/asset/list`) & API**
  - **Objective**: 管理者向け資産一覧、検索、新規登録、編集、貸与/返却モーダル、CSVエクスポートを実装する。
  - **Guidance**: Bootstrap 5 + jQuery による標準画面構成。デスクトップおよび 390px モバイル表示に対応。
  - **Test**: `AssetApiControllerTest`, モックMVCテスト, CSVエクスポート往復テスト。
  - **Demo**: ブラウザでの資産登録、貸与、返却操作の確認。

- [ ] **Task A1.2: 棚卸し実施画面 (`/asset/inventory`) & 差異照合 API**
  - **Objective**: 棚卸し計画の開始、実地照合結果の入力、理論在庫との差異集計、是正措置登録、完了確定フローを実装する。
  - **Guidance**: 一致・差異・所在不明・未登録の4区分で集計し、完了時はスナップショットを確定する。
  - **Test**: `AssetInventoryServiceTest`（差異集計・完了確定）。
  - **Demo**: 棚卸し開始から実地照合、差異確認、完了確定までのブラウザデモ。

- [ ] **Task A1.3: 外部アカウント・ライセンス管理画面 (`/asset/accounts`) & API**
  - **Objective**: 外部アカウント一覧、手動失効完了確認、ライセンスプラン一覧、席数消費状況の可視化画面を実装する。
  - **Guidance**: 失効要求日と確認日の分離表示、外部同期エラー詳細の表示。
  - **Test**: `ExternalAccountApiControllerTest`。
  - **Demo**: アカウント失効確認操作とライセンス席数消費バーの表示確認。

---

## A2. 要員ポータル画面 (`/my/assets`) & 通知

- [ ] **Task A2.1: 要員ポータル資産確認画面 (`/my/assets`) & API**
  - **Objective**: 要員本人が自身に貸与されている資産情報、返却期日、およびアカウント参照を閲覧できるセルフサービス画面を実装する。
  - **Guidance**: 認可スコープを厳格に適用し、本人の貸与情報のみを表示。390px モバイル完全対応。
  - **Test**: `MyAssetApiControllerTest`（本人データのみ返却、他者データ403/404検証）。
  - **Demo**: 要員ログイン状態での `/my/assets` 閲覧デモ（デスクトップ・390px）。

- [ ] **Task A2.2: 資産返却期日・棚卸し・失効未確認通知の実装**
  - **Objective**: 返却期日接近（7日前/3日前/当日）、期日超過、棚卸し開始、外部アカウント失効未確認の通知を生成する。
  - **Guidance**: `NotificationService` と連携し、Deduplication Key による重複通知抑止を実装。
  - **Test**: 通知生成・重複抑止テスト。
  - **Demo**: 期日接近時の通知トレイ・ベルアイコン表示確認。

---

## B1. バッチ・スケジューラ・紛失インシデント

- [ ] **Task B1.1: 資産ライフサイクル定期バッチ (`AssetScheduler`)**
  - **Objective**: 毎日深夜に稼働し、貸与期限超過検知（ステータス `OVERDUE` への更新）、リマインド通知生成、リース満了警告を行うバッチを実装する。
  - **Guidance**: multi-node 実行に配慮した重複実行防止（lease または状態CAS）。
  - **Test**: `AssetSchedulerTest`（明示呼び出しによる期限超過遷移検証）。
  - **Demo**: バッチ実行による超過ステータス更新ログの確認。

- [ ] **Task B1.2: 紛失インシデント追跡・リモートワイプ記録**
  - **Objective**: 資産紛失（`LOST`）時のインシデント起票、リモートワイプ確認記録、警察届出・保険証跡リンク機能を実装する。
  - **Guidance**: `t_asset_event` へのインシデント詳細追記およびアラート掲出。
  - **Test**: 紛失ステータス遷移およびワイプ記録テスト。
  - **Demo**: 紛失登録およびインシデント追跡画面の確認。

---

## B2. NF-01 退社ゲート連携 & 外部 Revoke Adapter

- [ ] **Task B2.1: `engineer-lifecycle-workflow` (NF-01) 退社ゲート連携実装**
  - **Objective**: 退社ケース完了前チェックにおいて、未返却資産および未失効アカウントを blocker として検出する `AssetLifecycleIntegrationService` を実装する。
  - **Guidance**: 未返却/未失効が存在する場合は退社ケースの `COMPLETED` 遷移を確実に阻止し、例外承認（`ApprovalEngineService` / `RequestType = LIFECYCLE_EXCEPTION`）がある場合のみ免除する。
  - **Test**: 退社 blocker 検出テスト、未返却残存時の退社阻止テスト、例外承認による免除テスト。
  - **Demo**: 退社ワークフロー画面で未返却資産がある場合に退社完了がブロックされることの確認。

- [ ] **Task B2.2: 外部プロバイダー Revoke Adapter (Mock & タイムアウト耐性)**
  - **Objective**: 外部SaaS/IdP/MDMへの失効要求送信アダプタを実装し、タイムアウト・通信失敗時の fail-closed 挙動を保証する。
  - **Guidance**: 外部通信はトランザクション外で実行。タイムアウト時は `SYNC_FAILED` / `TIMEOUT` として残し、自動で失効完了とみなさない。
  - **Test**: 外部プロバイダータイムアウト時の状態維持テスト（未失効のまま残ることの検証）。
  - **Demo**: 外部連携失敗時のエラー表示と手動確認フローの確認。

---

## M. 総合検証・Release Gate・Runbook

- [ ] **Task M.1: Fast / MySQL / Concurrency / Secret Scan 全ゲート実行**
  - **Objective**: 全テストスイートを実行し、スキップ0件、リグレッション0件を実証する。
  - **Guidance**:
    - `mvn test` (Fast Suite / H2)
    - `mvn test -Pmysql-tests` (MySQL Testcontainers)
    - `AssetSecretFieldScanTest` (秘密情報フィールド非存在スキャン)
    - `AssetAssignmentConcurrencyTest` (並行重複貸与排他テスト)
  - **Test**: 全自動テストの PASS ログ。
  - **Demo**: テスト結果レポートの確認。

- [ ] **Task M.2: 資産件数 Reconciliation・未返却一覧・Runbook 作成**
  - **Objective**: 資産件数照合スクリプト、未返却資産一覧出力、運用マニュアル・ロールバック手順書（`runbook.md`）を作成する。
  - **Guidance**: 障害時の切り戻し手順およびデータ補償手順を明文化。
  - **Test**: Reconciliation スクリプトの動作検証。
  - **Demo**: Runbook のレビュー完了。
