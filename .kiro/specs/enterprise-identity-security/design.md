# Design — 企業認証・セキュリティ

## 1. DDL（予約V63）

- `m_identity_provider(tenant_id, type, issuer_uri/metadata_uri, client_id, encrypted_secret_ref, enabled)`。
- `t_user_external_identity(user_id, provider_id, subject, email_snapshot, linked_at)`。
- `t_user_mfa(user_id, encrypted_totp_secret, enabled_at, last_used_step)`。
- `t_mfa_recovery_code(user_id, code_hash, used_at)`。
- `t_user_session(session_id_hash, user_id, issued_at, last_seen_at, expires_at, ip_hash, user_agent, revoked_at)`。
- `m_permission_group`, `t_user_permission_group`, `t_permission_group_action`。
- upload metadataへ`scan_status/scanned_at/scanner_version`。既存fileが表を持たない場合はarchive specのdocument versionへ集約。

## 2. SecurityConfig

- `oauth2Login` + custom `OidcUserService`でsubject紐付け。email auto-link禁止。
- local form loginは`app.security.local-login-enabled`、break-glass userは別flagとMFA必須。
- `SessionRegistry`または永続session metadataで失効を実現。Spring Session導入は複数instance要件がある場合だけ。
- OIDC logoutはRP initiated logout対応可否をproviderごとに設定。
- SAMLは別configuration class/feature flagで依存を隔離する。

## 3. Permission

- action key例: `invoice.view`, `invoice.update`, `invoice.void`, `invoice.export`, `contract.cost.view`。
- 既存`role`→default groupをseedし、`MenuPermissionFilter`はgroup actionを参照。移行期間は両者の和ではなく、
  group未設定時だけlegacy role fallback。
- controller/serviceで`AuthorizationService.assertAllowed(action, resource)`を呼び、sidebarだけに依存しない。

## 4. File scan

- `FileScanner` interface（ClamAV adapter + test fake）。store後はquarantine、CLEAN後に公開領域へmove。
- scanner unavailable時はfail-closed、再scan job、管理画面で状態/理由を表示。
- `FileScopeValidationService`末尾の未知file許可を拒否へ変更し、既存参照種別を全登録する。

## 5. テスト

`spring-security-test`のOIDC principal、WireMock issuer、TOTP時刻固定、session invalidation、permission matrix、
scanner clean/infected/unavailable、secret非ログ出力capture。

