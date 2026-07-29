-- 本番security設定テスト用の2人目のbreak-glass管理者。
INSERT INTO sys_user (username, password, real_name, role, email, status)
VALUES ('breakglass2', 'test-only-password', '非常用管理者2', '管理者', 'breakglass2@example.invalid', 1);

INSERT INTO t_user_mfa (tenant_id, user_id, encrypted_totp_secret, secret_key_version, enabled_at)
VALUES ('default', (SELECT id FROM sys_user WHERE username = 'admin'),
        'v1:test-iv:test-ciphertext', 'v1', CURRENT_TIMESTAMP);

INSERT INTO t_user_mfa (tenant_id, user_id, encrypted_totp_secret, secret_key_version, enabled_at)
VALUES ('default', (SELECT id FROM sys_user WHERE username = 'breakglass2'),
        'v1:test-iv:test-ciphertext', 'v1', CURRENT_TIMESTAMP);
