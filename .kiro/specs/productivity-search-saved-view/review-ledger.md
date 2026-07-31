# 横断検索・実ToDo・保存ビュー・一括操作（productivity-search-saved-view）要件・設計整合性レビュー記録

## レビュー概要

- **対象モジュール**: 横断検索・実ToDo・保存ビュー・一括操作（productivity-search-saved-view）
- **担当AI**: SES Manager Pro 主実装AI
- **Decision Gate**: G0 独立DB確定（2026-07-26）
- **評価基準**: 独立レビュー仕様および本リポジトリ開発規約

---

## 変更ファイル記録

| task | ファイル | 変更種別 | 内容 |
|---|---|---|---|
| T028 | `src/main/resources/db/migration/V68__productivity_task_and_saved_view.sql` | 新規 | t_task, m_saved_view テーブル定義 Flyway マイグレーション |
| T028 | `src/main/resources/db/migration/V1__create_tables.sql` | 変更 | V68 相当の t_task, m_saved_view DDL を同期追加 |
| T028 | `src/test/resources/sql/schema-productivity-h2.sql` | 新規 | H2 テスト用 DDL |
| T028 | `src/test/resources/application-test.yml` | 変更 | H2 schema-locations に schema-productivity-h2.sql を追加 |
| T028 | `src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java` | 変更 | V68 の MySQL smoke assert (t_task, m_saved_view) 追加 |
| T028 | `src/main/java/com/ses/entity/Task.java` | 新規 | Task エンティティ |
| T028 | `src/main/java/com/ses/entity/SavedView.java` | 新規 | SavedView エンティティ（owner_user_id NULL = 共有ビュー） |
| T028 | `src/main/java/com/ses/mapper/TaskMapper.java` | 新規 | TaskMapper インターフェース |
| T028 | `src/main/java/com/ses/mapper/SavedViewMapper.java` | 新規 | SavedViewMapper インターフェース |
| T028 | `src/main/java/com/ses/service/SavedViewSchemaRegistry.java` | 新規 | 保存ビュー JSON allowlist スキーマ検証コンポーネント |
| T028 | `src/main/java/com/ses/service/TaskService.java` | 新規 | TaskService インターフェース |
| T028 | `src/main/java/com/ses/service/impl/TaskServiceImpl.java` | 新規 | TaskServiceImpl 実装（状態機械、終端状態からの再オープン不可、due_date NULL 除外） |
| T028 | `src/main/java/com/ses/service/SavedViewService.java` | 新規 | SavedViewService インターフェース |
| T028 | `src/main/java/com/ses/service/impl/SavedViewServiceImpl.java` | 新規 | SavedViewServiceImpl 実装（管理者共有ビュー権限、他人の個人ビュー変更ガード） |
| T028 | `src/main/resources/messages*.properties` | 変更 | 4言語メッセージファイルに生産性機能キーを追加 |
| T028 | `src/test/java/com/ses/service/impl/TaskServiceH2Test.java` | 新規 | TaskService 統合テスト（状態機械・期限除外検証） |
| T028 | `src/test/java/com/ses/service/impl/SavedViewServiceH2Test.java` | 新規 | SavedViewService 統合テスト（allowlist・個人/共有権限検証） |
| T029 | `src/main/java/com/ses/dto/search/GlobalSearchResultDTO.java` | 新規 | 横断検索結果 DTO (PII/原価非含) |
| T029 | `src/main/java/com/ses/service/search/GlobalSearchProvider.java` | 新規 | エンティティ種別検索プロバイダ インターフェース |
| T029 | `src/main/java/com/ses/service/search/GlobalSearchService.java` | 新規 | 横断検索統合サービス インターフェース |
| T029 | `src/main/java/com/ses/service/search/GlobalSearchServiceImpl.java` | 新規 | 横断検索統合サービス 実装 (2文字未満拒否、順次実行) |
| T029 | `src/main/java/com/ses/service/search/provider/*SearchProvider.java` | 新規 | Engineer, Customer, Project, Contract, Invoice, Proposal, Quotation, BpAvailability 8種プロバイダ (DataScope 準拠) |
| T029 | `src/main/java/com/ses/controller/api/SearchApiController.java` | 新規 | 横断検索 REST Controller (`GET /api/search`) |
| T029 | `src/main/resources/templates/layout/header.html` | 変更 | ヘッダーに横断検索ボタンおよび検索モーダルを追加 |
| T029 | `src/main/resources/static/js/common.js` | 変更 | SES.globalSearch (Ctrl+K キーボードショートカット・API連動・UI描画) を追加 |
| T029 | `src/test/java/com/ses/service/search/GlobalSearchServiceH2Test.java` | 新規 | 横断検索統合テスト (スコープ隔離・0件・2文字未満拒否検証) |
| T030 | `src/main/resources/db/migration/V68__productivity_task_and_saved_view.sql` | 変更 | t_task_notification_log テーブル定義追加 |
| T030 | `src/main/resources/db/migration/V1__create_tables.sql` | 変更 | t_task_notification_log DDL 同期 |
| T030 | `src/test/resources/sql/schema-productivity-h2.sql` | 変更 | t_task_notification_log H2 DDL 同期 |
| T030 | `src/test/java/com/ses/migration/FlywayMigrationSmokeTest.java` | 変更 | t_task_notification_log マイグレーション assert 追加 |
| T030 | `src/main/java/com/ses/entity/TaskNotificationLog.java` | 新規 | TaskNotificationLog エンティティ |
| T030 | `src/main/java/com/ses/mapper/TaskNotificationLogMapper.java` | 新規 | TaskNotificationLogMapper インターフェース |
| T030 | `src/main/java/com/ses/service/scheduler/TaskDueDateNotificationScheduler.java` | 新規 | タスク期限超過通知日次バッチ（重複防止ログによる1日1回冪等送信） |
| T030 | `src/main/java/com/ses/controller/api/TaskApiController.java` | 新規 | Task REST Controller (`/api/tasks`, `/api/tasks/from-notification/{id}`) |
| T030 | `src/main/resources/templates/todo/list.html` | 変更 | ToDo画面をタスク/通知の2タブ構造へ改修、新規タスクmodal追加 |
| T030 | `src/main/resources/static/js/modules/todo.js` | 変更 | タスクCRUD・ステータス更新・通知からのタスク化・既読とタスク完了分離UI処理 |
| T030 | `src/test/java/com/ses/service/impl/TaskNotificationSchedulerH2Test.java` | 新規 | 期限通知スケジューラ冪等性・既読/完了分離統合テスト |
| T031 | `src/main/java/com/ses/controller/api/SavedViewApiController.java` | 新規 | 保存ビュー REST Controller (`/api/saved-views`) |
| T031 | `src/main/resources/static/js/modules/saved-view.js` | 新規 | 保存ビュードロップダウン・登録モーダル共通フロントエンド JS |
| T031 | `src/main/resources/templates/engineer/list.html` | 変更 | 要員一覧画面に saved-view コンポーネントおよび JS スクリプトを追加 |
| T031 | `src/main/resources/static/js/modules/engineer.js` | 変更 | SES.savedView の初期化とフィルター適用・保存バインディングを実装 |
| T032 | `src/main/java/com/ses/dto/batch/BatchOperationResultDTO.java` | 新規 | 一括操作結果レスポンス DTO |
| T032 | `src/main/java/com/ses/dto/batch/BatchStatusUpdateRequestDTO.java` | 新規 | 一括操作リクエスト DTO |
| T032 | `src/main/java/com/ses/service/BatchOperationService.java` | 新規 | 安全な一括操作サービス インターフェース |
| T032 | `src/main/java/com/ses/service/impl/BatchOperationServiceImpl.java` | 新規 | 一括操作サービス 実装 (100件上限、一部失敗許容、スコープ分離) |
| T032 | `src/main/java/com/ses/controller/api/BatchOperationApiController.java` | 新規 | 一括操作 REST Controller (`/api/batch-operations`) |
| T032 | `src/test/java/com/ses/service/impl/BatchOperationServiceH2Test.java` | 新規 | 一括操作上限・部分成功分離・スコープ隔離統合テスト |
| T033 | `src/test/java/com/ses/migration/SpecDispatchConsistencyTest.java` | 変更 | S05 spec 完了に伴う spec 整合性メタテストの更新 |
