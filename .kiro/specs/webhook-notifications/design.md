# Design Document — Slack/Webhook通知連携

## 1. 設定(m_system_configへのシード追加、新規migration `V17__add_webhook_config.sql`)

```sql
INSERT INTO m_system_config (config_key, config_value, description) VALUES
  ('notification.webhook-url', '', 'Slack互換Incoming Webhook URL(空=無効)'),
  ('notification.webhook-types', 'CONTRACT_END,PROJECT_URGENT', '転送対象の通知種別(カンマ区切り)');
```
(既存の`commission.base-type`等と同じ`m_system_config`のキー追加パターンを踏襲。テーブル構造自体の変更はなし。)

## 2. バックエンド

### 2.1 新規
- `service/notification/WebhookNotifier.java`: `@Async`メソッド`notify(Notification notification)`。`RestTemplate`(Spring Boot標準)でSlack互換JSON(`{"text": "..."}`)をPOST。タイムアウト設定(例: 3秒)、例外はcatchしてログ出力のみ(踩坑点参照)。
- `config/AsyncConfig.java`(未存在の場合новый): `@EnableAsync`+専用`ThreadPoolTaskExecutor`(通知バッチ本処理と分離)。

### 2.2 既存コードへのフック(最小限の変更)
- `NotificationServiceImpl`(または通知作成箇所)の通知保存直後に、`notification.webhook-types`に種別が含まれていれば`webhookNotifier.notify(notification)`を呼び出す1行を追加。既存の通知生成条件・重複排除ロジックには触れない。

### 2.3 UI
- `templates/system-config/list.html`(既存画面)に`notification.webhook-url`/`notification.webhook-types`の設定項目を追加。URL値は保存済みの場合マスキング表示(既存のパスワードマスキング方針を参考)。

## 3. レート制限対応

`notification.webhook-digest-mode`(true/false、デフォルトfalse)を追加検討。trueの場合、`NotificationScheduler`のバッチ実行完了後にまとめて1通のダイジェストとして送信する集約モードを提供(即時送信 or ダイジェストの二択)。

## 4. テスト

- `WebhookNotifierTest`: URL未設定時のスキップ、送信失敗時に例外が上位へ伝播しないこと、非同期実行であること(`@Async`のモック検証)。
- 既存`NotificationServiceImplTest`への影響がないこと(回帰確認、フック追加のみで既存アサーションが変わらないこと)。

## 5. リスク・確定口径(踩坑点)

- **Webhook URLの機微情報扱い**: requirements.md記載の通り、画面表示・ログ出力ともにマスキングする。
- **非同期化必須**: 同期実装するとバッチ全体がブロックされる。`@Async`未設定の見落としがないかコードレビューで必ず確認する。
- **レート制限**: 初期実装は即時送信のみでよいが、実運用で問題が出た場合にダイジェストモードへ切り替えられるよう、設定キーだけは本specの段階で用意しておく。
