# Security, Privacy & AI Review (Commit: 54edfd2b)

---

## 1. 致命的安全欠陥 (P0 / P1 Findings)

### [ACC-SEC-P0-001] HR / マネージャーによる OIDC 外部 ID 紐付けを通じた管理者アカウント乗っ取り (P0)
- **該当箇所**:
  - `src/main/java/com/ses/config/SecurityConfig.java:143-169`
  - `src/main/resources/db/migration/V66_1__close_security_review_boundaries.sql:24`
  - `src/main/java/com/ses/controller/api/ExternalIdentityApiController.java:15-28`
  - `src/main/java/com/ses/service/impl/ExternalIdentityProvisioningServiceImpl.java:34-86`
  - `src/main/java/com/ses/config/OidcLoginUserService.java:88-103`
- **メカニズム**:
  1. `SecurityConfig.java` において `/api/identity-providers/**` が `.hasAnyRole("管理者", "HR", "マネージャー")` に含まれている。
  2. `ExternalIdentityApiController` にメソッドレベルセキュリティ (`@PreAuthorize`) が存在しない。
  3. `ExternalIdentityProvisioningServiceImpl` は、呼出元が管理者であるか、対象 `userId` が管理者であるかを検証せずに任意の `userId` と OIDC `subject` を紐付ける。
  4. HR またはマネージャー権限を持つ攻撃者が `POST /api/identity-providers/1/external-identities` (`userId=1` [admin]) を送信すると、紐付けが成功する。
  5. 攻撃者が Enterprise OIDC でログインすると、`OidcLoginUserService` が `userId=1` を解決し、攻撃者に `ROLE_管理者` が付与され、**システム全体の完全な乗っ取りが成立する**。
- **推奨対策**: エンドポイントを `hasRole("管理者")` に厳格制限し、コントローラに `@PreAuthorize("hasRole('管理者')")` を付与、サービス層で管理者への紐付けガードを実装すること。

---

### [ACC-SEC-P1-001] SecurityConfig における管理者ハードバウンダリの過度な拡張 (P1)
- `SecurityConfig.java:143-169` において、管理者専用であるべき `/user/**`, `/api/users/**`, `/system-config/**`, `/audit-log/**` が `.hasAnyRole("管理者", "HR", "マネージャー")` にまとめられている。二重防御（Defense-in-Depth）の第 1 層が破壊されている。

### [ACC-SEC-P1-002] & [ACC-SEC-P1-003] Webhook および AI URL におけるブラインド SSRF (P1)
- `WebhookNotifier.java:50-69` および `GeminiTextServiceImpl.java:57-68` において、送信先 URL のスキーマ (`https://`) や内部 IP アドレス (127.0.0.1, 169.254.169.254, 10.0.0.0/8 等) の検証が存在せず、内部ネットワークやクラウドメタデータへの不正リクエストが可能。

### [ACC-SEC-P1-004] フロントエンド JS による Gemini API Key の送信 (P1)
- `static/js/modules/ai.js:181-230` がユーザに API Key を入力させ、AJAX リクエストボディに入れてサーバへ送信している（サーバ側 `AiRestController` はこれを無視して環境変数設定を使用）。キーの不要な露出リスクが存在する。

### [ACC-SEC-P1-005] AI 取込処理における PII マスキングおよびカナリア検査のバイパス (P1)
- `AiExecutionGatewayImpl.java:51-59` において、`INGEST_*` ユースケース（履歴書取込等）が外部向け検査から除外されており、外部 LLM 送信が有効化された際に個人情報が平文送信されるリスクがある。

### [ACC-SEC-P1-006] メールサービス DRY_RUN モードにおける招待トークン等の平文ログ出力 (P1)
- `MailServiceImpl.java:128-139` において、SMTP 未設定時に本文全文 (`delivery.getBody()`) を INFO レベルでログ出力しており、ワンタイム招待トークンや給与情報がログに記録される。

### [ACC-SEC-P1-007] デフォルト起動時の dev プロファイルによる平文パスワード運用リスク (P1)
- `application.yml` が `spring.profiles.active: dev` をデフォルトとしており、`--spring.profiles.active=prod` の指定を忘れて起動した場合、`NoOpPasswordEncoder` (平文パスワード) で稼働してしまう。
