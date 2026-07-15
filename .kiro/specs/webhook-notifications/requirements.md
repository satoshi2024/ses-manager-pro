# Requirements Document — Slack/Webhook通知連携

## Introduction

現状の通知は`t_notification`(社内画面のベル通知)と`MailServiceImpl`(SMTP、`spring.mail.host`未設定時はdry-run/ログ出力のみ)の2経路に限られる。実運用では営業/管理者がSlack等のチャットツールに常駐しているため、`CONTRACT_END`/`PROJECT_URGENT`等の重要通知をチャットへも即時転送できると見落としが減る。本specは既存の`notification-center`の通知生成ロジックに影響を与えず、生成済み通知をWebhook経由で外部へ転送する仕組みを追加する。

### 確定済みの設計判断
- `NotificationServiceImpl`/`NotificationGenerateService`/`NotificationScheduler`の既存ロジック(通知の生成条件・重複排除)は一切変更しない。本specは「生成された通知をどこにも追加で転送するか」のみを扱う。
- Webhook送信は非同期・失敗時サイレント(通知自体の生成・画面表示は失敗の影響を受けない)とする。
- 対応するのは汎用のIncoming Webhook形式(Slack互換のJSON POST)のみとし、Slack SDK等の追加依存は導入しない(`RestTemplate`/`WebClient`等、Spring Boot標準機能で完結させる)。

## Requirements

### Requirement 1: Webhook送信先の設定

#### Acceptance Criteria
1. THE システム SHALL `m_system_config`に`notification.webhook-url`キーを追加し、管理者が既存の`/system-config`画面から設定できる。
2. IF `notification.webhook-url`が未設定の場合、THE システム SHALL Webhook送信をスキップする(既存のメールdry-run方式と同様、設定なしでもアプリ全体は正常動作する)。
3. THE システム SHALL 通知種別ごとにWebhook転送のON/OFFを設定できる(全種別を無条件転送するとチャットが埋まりすぎる問題を避けるため)。

### Requirement 2: 通知のWebhook転送

#### Acceptance Criteria
1. WHEN `NotificationScheduler`のバッチ実行、または`AI_MATCHING`のような即時発行で新規通知が作成された場合、THE システム SHALL 対象種別が有効化されていればWebhookへ非同期でPOSTする。
2. THE Webhookペイロード SHALL 通知タイトル・本文・(あれば)対象エンティティへのアプリ内リンクを含む。
3. IF Webhook送信が失敗した場合(タイムアウト・4xx/5xx)、THE システム SHALL エラーをログ出力するのみとし、通知自体の生成・画面表示・再試行ループへは影響させない(無限リトライで通知バッチが遅延することを避けるため)。

## 注意点（実装時の注意）
- `notification.webhook-url`はSlack Webhook URLという性質上、機微情報に近い（漏洩すると第三者が任意のメッセージをチャンネルに投稿できる）。`system-config`画面での表示時にマスキングする等、既存のパスワード表示方針（`UserApiController`がパスワードをレスポンスから除去する既存パターン）を参考に扱うこと。
- 同期的にHTTP POSTを行うと、Webhook先が遅い/落ちている場合に`NotificationScheduler`の日次バッチ全体が遅延するリスクがある。**必ず非同期(`@Async`または別スレッドプール)で送信し、バッチの本処理をブロックしない**設計にする。
- Slack Incoming Webhookはレート制限があるため、大量の通知が一度に生成された場合(例: `BENCH_LONG`が該当者多数)に一括送信するとレート制限に引っかかる可能性がある。まとめて1通に集約する、または種別ごとに1日1回のダイジェスト送信にするか、設計判断をタスク着手前に確定させる。
