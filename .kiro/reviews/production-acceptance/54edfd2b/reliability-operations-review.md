# Reliability, Operations & External Integration Review (Commit: 54edfd2b)

---

## 1. 運用・信頼性における重要課題

### [ACC-OPS-P0-001] Spring Boot Actuator (`spring-boot-starter-actuator`) の欠落 (P0)
- `pom.xml` に Actuator が含まれておらず、`/actuator/health` 等のヘルスチェックエンドポイントが存在しない。
- Kubernetes, AWS ECS, ALB 等のオーケストレータ・ロードバランサが、コンテナの Liveness / Readiness プローブを実行できず、障害時の自動切り離しやローリングアップデートが不能となる。

### [ACC-OPS-P1-001] `TaskDueDateNotificationScheduler` における `@SchedulerLock` の欠落 (P1)
- `TaskDueDateNotificationScheduler.java:34` の `runDailyOverdueCheck()` に `@Scheduled` は付与されているが、**`@SchedulerLock` が付与されていない**。
- 複数インスタンス構成（クラスタ運用）時、全インスタンスで毎日午前 2 時にバッチが同時実行され、通知の重複送信や DB 負荷が発生する。

### [ACC-OPS-P1-002] メトリクス / APM エクスポート未設定 (P1)
- Micrometer, Prometheus, OpenTelemetry 等のエクスポータが未設定であり、本番稼働時の JVM メモリ・コネクションプール監視が外部から行えない。

### [ACC-OPS-P1-003] インメモリ HTTP セッション構成 (P1)
- セッションが JVM メモリ内に保持されるため、マルチインスタンス展開時にはロードバランサのスティッキーセッション（Sticky Session）が必須、または Redis/DB セッションストアへの移行が必要。

---

## 2. 外部サービス統合とモック切り替え

| 連携機能 | モード設定 | 本番安全性評価 | 判定 |
|---|---|---|---|
| **デジタルインボイス (JP PINT)** | `digital-invoice.provider=none` | 本番プロファイルでは `none` に固定され、実 Peppol プロバイダ接続まで送信を安全に遮断 (Fail-Closed)。 | **PASS** |
| **CloudSign 電子契約** | `cloudsign.enabled=false` | デフォルト無効化キルスイッチ完備。 | **PASS** |
| **freee 人事労務 API** | `freee.client-id` / `secret` | AES-GCM トークン暗号化・401並行リフレッシュガード完備。 | **PASS** |
| **AI サービス** | `ai.provider=mock` | デフォルトモック。実送信は設定により制限。 | **PASS** |
| **メール送信 (SMTP)** | `spring.mail.host` | 未設定時は DRY_RUN モードで例外を投げずにフォールバック。 | **PASS** |
