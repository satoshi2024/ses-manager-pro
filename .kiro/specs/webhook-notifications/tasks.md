# Implementation Plan — Slack/Webhook通知連携

既存`notification-center`完了コードへの変更は「1行フック追加」のみに限定。逐次構成。

- [x] 1. 設定基盤(m_system_config追加・system-config画面)
  - **Objective**: `notification.webhook-url`/`notification.webhook-types`の設定項目を追加。
  - **実装ガイダンス**: design.md 1章のmigration、`templates/system-config/list.html`にマスキング表示付きフォーム追加。
  - **テスト要件**: 保存・マスキング表示の確認。
  - **Demo**: 管理者で設定画面からURL保存、再表示時にマスキングされることを確認。

- [x] 2. WebhookNotifier実装(非同期)
  - **Objective**: `WebhookNotifier`と`AsyncConfig`を実装。
  - **実装ガイダンス**: design.md 2章。必ず`@Async`、タイムアウト設定、例外はログのみ。
  - **テスト要件**: `WebhookNotifierTest`でURL未設定時スキップ、送信失敗時に例外非伝播、非同期実行を確認。
  - **Demo**: テスト用Slack Webhook URL(またはモックHTTPサーバー)へ実際に1件POSTされることを確認。

- [x] 3. 既存通知生成箇所へのフック追加
  - **Objective**: `NotificationServiceImpl`の通知保存直後にWebhook転送を1行追加。
  - **実装ガイダンス**: design.md 2.2章。既存の生成条件・重複排除ロジックへの影響がないことを既存テストで確認。
  - **テスト要件**: 既存`NotificationServiceImplTest`が全てグリーンのまま(回帰なし)であることを確認。
  - **Demo**: `NotificationScheduler`のバッチを手動実行し、`CONTRACT_END`通知がWebhookへ転送されることを確認。
