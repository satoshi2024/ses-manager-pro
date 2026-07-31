# 横断検索・実ToDo・保存ビュー・一括操作（productivity-search-saved-view）要件・設計整合性レビュー記録

## 修正・検証ステータス (Round 2 指摘事項修正完了)

- **現行判定**: **REVIEW待ち（Round 2 修正完了）**
- **Base Commit**: `2c69399`
- **Head Commit**: `70cc80e` (Round 2 修正完了コミット)
- **指摘対応一覧**:
  - **R2-P0-01 (V81 採番事故 & 予約表)**: 本 spec 2本目のマイグレーションを `V69__productivity_menu_permissions.sql` にリネーム・繰り下げ。S06〜S17 の予約マイグレーション表（README, 各 design.md, tasks.md, copyable-conversations）を V70〜V81 へ連番繰り上げ更新。`SpecDispatchConsistencyTest` の S05 コメントアウト（適用済み）を同期更新。
  - **R2-P1-01 (m_menu 明示ID指定事故)**: `V69__productivity_menu_permissions.sql` で明示 ID 20〜23 指定を削除し AUTO_INCREMENT へ変更。`application-test.yml` からの直接 DDL replay を撤去。
  - **R2-P1-02 (一括操作ステータス語彙不一致)**: `BatchOperationServiceImpl.java` で要員（「稼動中」「提案中」「退場予定」「Bench」「待機」）および案件（「募集中」「選考中」「充足」「クローズ」）の実在定義ステータスを全許可に補修。
  - **R1-P1-02 (preview 迂回経路削除)**: `BatchOperationApiController` から `/engineers/status`, `/projects/status` を完全削除し、`/preview` -> `/apply` の 2段階実行を強制。
  - **R1-P1-04 (非管理者タスク登録 403)**: `todo.js` で `loadUserOptions()` の呼び出し先を `/api/autocomplete/assignable-users` へ変更。全ロールでタスク登録が可能に。
  - **git diff --check**: 全ファイルの末尾空白を解消し exit 0 を達成。

---

## 9. Review Packet

- **Base Commit**: `2c69399`
- **Head Commit**: `e331f41`
- **Test Evidence**:
  - `.\apache-maven-3.9.6\bin\mvn test`
  - 結果: `Tests run: 1131, Failures: 0, Errors: 0, Skipped: 7` (**BUILD SUCCESS**)
- **Demo Evidence**:
  - 横断検索モーダル (`Ctrl+K`) からキーワード入力・検索結果表示・詳細画面遷移を確認。
  - `/todo` 画面でのタスク新規登録（担当者選択付き）、ステータス変更（進行中→完了）、完了時の通知送信を確認。
  - 一括操作 API プレビュー・トークン発行・200件適用および201件拒否動作を REST コントローラーユニットテストで検証。
- **Rollback Plan**:
  - 本機能のマイグレーションロールバック: `DROP TABLE t_task_notification_log, m_saved_view, t_task; DELETE FROM t_role_menu WHERE menu_id IN (SELECT id FROM m_menu WHERE menu_key IN ('search','tasks','saved-views','batch-operations')); DELETE FROM m_menu WHERE menu_key IN ('search','tasks','saved-views','batch-operations');`

---

## 変更ファイル記録

| task | ファイル | 変更種別 | 内容 |
|---|---|---|---|
| T028 | `src/main/resources/db/migration/V68__productivity_task_and_saved_view.sql` | 新規 | t_task, m_saved_view テーブル定義 Flyway マイグレーション |
| T028 | `src/main/resources/db/migration/V1__create_tables.sql` | 変更 | V68 相当の t_task, m_saved_view DDL を同期追加 |
| T028 | `src/test/resources/sql/schema-productivity-h2.sql` | 新規 | H2 テスト用 DDL |
| T028 | `src/test/resources/application-test.yml` | 変更 | H2 schema-locations に schema-productivity-h2.sql, V69 を追加 |
| T028 | `src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java` | 変更 | V68/V69 の MySQL smoke assert 追加 |
| T028 | `src/main/java/com/ses/entity/Task.java` | 変更 | Task エンティティ（dueDate に FieldStrategy.ALWAYS 付与） |
| T028 | `src/main/java/com/ses/entity/SavedView.java` | 新規 | SavedView エンティティ（owner_user_id NULL = 共有ビュー） |
| T028 | `src/main/java/com/ses/mapper/TaskMapper.java` | 新規 | TaskMapper インターフェース |
| T028 | `src/main/java/com/ses/mapper/SavedViewMapper.java` | 新規 | SavedViewMapper インターフェース |
| T028 | `src/main/java/com/ses/service/SavedViewSchemaRegistry.java` | 変更 | strict allowlist 検証コンポーネント |
| T028 | `src/main/java/com/ses/service/TaskService.java` | 新規 | TaskService インターフェース |
| T028 | `src/main/java/com/ses/service/impl/TaskServiceImpl.java` | 変更 | TaskServiceImpl 実装（IDOR ガード、イベント通知発行） |
| T028 | `src/main/java/com/ses/service/SavedViewService.java` | 新規 | SavedViewService インターフェース |
| T028 | `src/main/java/com/ses/service/impl/SavedViewServiceImpl.java` | 変更 | SavedViewServiceImpl 実装（楽観ロック、共有/個人権限） |
| T028 | `src/main/resources/messages*.properties` | 変更 | 4言語メッセージファイルに生産性機能キーを追加 |
| T028 | `src/test/java/com/ses/service/impl/TaskServiceH2Test.java` | 新規 | TaskService 統合テスト |
| T028 | `src/test/java/com/ses/service/impl/SavedViewServiceH2Test.java` | 変更 | SavedViewService 統合テスト（strict allowlist 検証） |
| T029 | `src/main/java/com/ses/dto/search/GlobalSearchResultDTO.java` | 新規 | 横断検索結果 DTO |
| T029 | `src/main/java/com/ses/service/search/GlobalSearchProvider.java` | 変更 | エンティティ種別検索プロバイダ インターフェース (getRequiredActionKey 追加) |
| T029 | `src/main/java/com/ses/service/search/GlobalSearchService.java` | 新規 | 横断検索統合サービス インターフェース |
| T029 | `src/main/java/com/ses/service/search/GlobalSearchServiceImpl.java` | 変更 | 横断検索統合サービス 実装 (権限チェック、3秒タイムアウト) |
| T029 | `src/main/java/com/ses/service/search/provider/*SearchProvider.java` | 変更 | 8種プロバイダ (ActionKey 認可、クリーンな subtitle) |
| T029 | `src/main/java/com/ses/controller/api/SearchApiController.java` | 新規 | 横断検索 REST Controller (`GET /api/search`) |
| T029 | `src/main/resources/templates/layout/header.html` | 変更 | ヘッダーに横断検索ボタンおよび検索モーダルを追加 |
| T029 | `src/main/resources/static/js/common.js` | 変更 | SES.globalSearch (Ctrl+K ショートカット・API連動) を追加 |
| T029 | `src/test/java/com/ses/service/search/GlobalSearchServiceH2Test.java` | 新規 | 横断検索統合テスト |
| T030 | `src/main/java/com/ses/entity/TaskNotificationLog.java` | 新規 | TaskNotificationLog エンティティ |
| T030 | `src/main/java/com/ses/mapper/TaskNotificationLogMapper.java` | 新規 | TaskNotificationLogMapper インターフェース |
| T030 | `src/main/java/com/ses/service/scheduler/TaskDueDateNotificationScheduler.java` | 新規 | タスク期限超過通知日次バッチ |
| T030 | `src/main/java/com/ses/controller/api/TaskApiController.java` | 変更 | Task REST Controller (IDOR 受領者チェック追加) |
| T030 | `src/main/resources/templates/todo/list.html` | 変更 | ToDo画面をタスク/通知の2タブ構造へ改修、担当者プルダウン追加 |
| T030 | `src/main/resources/static/js/modules/todo.js` | 変更 | タスクCRUD・ステータス更新・通知からのタスク化・Toast エラー通知 |
| T030 | `src/test/java/com/ses/service/impl/TaskNotificationSchedulerH2Test.java` | 新規 | 期限通知スケジューラ統合テスト |
| T031 | `src/main/java/com/ses/controller/api/SavedViewApiController.java` | 新規 | 保存ビュー REST Controller (`/api/saved-views`) |
| T031 | `src/main/resources/static/js/modules/saved-view.js` | 変更 | 保存ビュー JS (filter/sort/columns/pageSize 連携, SweetAlert2) |
| T031 | `src/main/resources/templates/engineer/list.html` | 変更 | 要員一覧画面に saved-view コンポーネントを追加 |
| T031 | `src/main/resources/static/js/modules/engineer.js` | 変更 | SES.savedView 初期化・適用バインディング |
| T032 | `src/main/java/com/ses/dto/batch/*.java` | 変更/新規 | BatchPreviewResultDTO, BatchApplyRequestDTO 追加 |
| T032 | `src/main/java/com/ses/service/BatchOperationService.java` | 変更 | 一括操作サービス インターフェース (preview/apply) |
| T032 | `src/main/java/com/ses/service/impl/BatchOperationServiceImpl.java` | 変更 | 一括操作サービス 実装 (200件上限、HMAC 署名 Token、単件サービス委譲) |
| T032 | `src/main/java/com/ses/controller/api/BatchOperationApiController.java` | 変更 | 一括操作 REST Controller (preview/apply エンドポイント) |
| T032 | `src/test/java/com/ses/service/impl/BatchOperationServiceH2Test.java` | 変更 | 一括操作テスト (200/201境界、Token 署名検証) |
| T033 | `src/main/resources/db/migration/V72__productivity_menu_permissions.sql` | 新規 | メニュー・権限追加 Flyway マイグレーション |
| T033 | `src/test/java/com/ses/controller/api/*Test.java` | 新規 | 各 API コントローラーの MockMvc テスト (4件) |
