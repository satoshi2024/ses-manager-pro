# Implementation Plan — 通知センター・ToDo(P3)

- [ ] 1. DDL とエンティティ・Mapper の作成
  - **Objective**: `t_notification` / `t_notification_read` を作りコードから扱えるようにする。
  - **実装ガイダンス**: `sql/004_create_notification.sql`(design.md 1章、メニューシード込み)+ H2 テストスキーマ追加 + エンティティ2つ + `NotificationMapper` の注釈 SQL(countUnread / selectPageForUser)。
  - **テスト要件**: H2 で insert → countUnread / selectPageForUser の整合を検証。
  - **Demo**: `mvn test` グリーン。MySQL に 004 を適用してテーブルが出来る。

- [ ] 2. `NotificationService` の永続化版への書き換え(テスト駆動)
  - **Objective**: publish(冪等)/ 既読管理 / 未読件数 / ページングを実装。
  - **実装ガイダンス**: design.md 2章。既存 `getRecentNotifications()` はシグネチャを `(Long userId)` に変更。`NotificationDto` へ id/isRead/linkUrl/type を追加。
  - **テスト要件**: 冪等性・既読・全既読・未読優先ソート。
  - **Demo**: `mvn test -Dtest=NotificationServiceImplTest` パス。

- [ ] 3. 通知生成バッチ
  - **Objective**: 4種別の日次生成と手動生成 API。
  - **実装ガイダンス**: design.md 3章。`@EnableScheduling`、`NotificationScheduler`、`NotificationGenerateService`。契約×要員は JOIN 1回で取得(N+1 禁止)。`/api/notifications/generate` は管理者のみ。
  - **テスト要件**: 各ルールの境界値テスト、二重実行で件数が増えないこと。
  - **Demo**: `curl -X POST /api/notifications/generate`(admin)→ 通知が生成され、再実行しても増えない。

- [ ] 4. API 改修とヘッダーベルの接続
  - **Objective**: ドロップダウンを永続化データ + 既読管理付きにする。
  - **実装ガイダンス**: design.md 4章・5章前半。`common.js` の `SES.notification` を改修(バッジ=unread-count、クリック既読化+遷移、すべて既読)。
  - **テスト要件**: `NotificationApiControllerTest` を新エンドポイントに合わせ更新。
  - **Demo**: ベルに未読数バッジ → 通知クリックで該当画面へ遷移しバッジが減る。

- [ ] 5. ToDo センター画面
  - **Objective**: `/todo` の一覧画面(フィルタ+ページネーション)。
  - **実装ガイダンス**: `TodoPageController` + `templates/todo/list.html` + `modules/todo.js`(既存 list 画面パターン)。sidebar に `todo` メニュー追加(`m_menu` シードは Task 1 で投入済み)。
  - **テスト要件**: `@WebMvcTest` でビュー名確認程度。
  - **Demo**: サイドバー「ToDo・通知」→ 種別フィルタ・未読のみ・ページ送りが動く。権限タブで営業から `todo` を外すと 403。

- [ ] 6. 旧実装の削除とテスト整理
  - **Objective**: 即時計算ロジックの残骸を消し、既存テストを新仕様に揃える。
  - **実装ガイダンス**: 旧 `getRecentNotifications` の退場30日/AIログ24hロジックはバッチ側に吸収済みであることを確認して削除。
  - **Demo**: `mvn test` 全件グリーン。
