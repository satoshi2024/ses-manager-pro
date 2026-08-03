-- 本番security設定テスト用の2人目のbreak-glass管理者。
--
-- 本ファイルは2箇所から同じ共有インメモリH2へ投入される:
--   1. ProductionSecurityConfigurationTest — `spring.sql.init.data-locations` （contextごとの初期化）
--   2. ProductionSecurityEnrollmentFixtureTest — `@Sql`（テストメソッドごと）
-- どちらが先に走るか、その間にcontext初期化で sys_user が作り直されるかは
-- Spring TestContextのcache順序次第なので、素のINSERTだと
-- `Unique index violation: SYS_USER(USERNAME) 'breakglass2'` で落ちる順序が存在する。
-- テストクラスを1つ足しただけで順序が変わって顕在化するため、投入自体を冪等にする。
INSERT INTO sys_user (username, password, real_name, role, email, status)
SELECT 'breakglass2', 'test-only-password', '非常用管理者2', '管理者', 'breakglass2@example.invalid', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'breakglass2');

-- MFA登録も同様に冪等にする。二重登録すると countEnrolled が 2 を返し、
-- 「1件登録済み」を期待するassertが壊れる。
INSERT INTO t_user_mfa (tenant_id, user_id, encrypted_totp_secret, secret_key_version, enabled_at)
SELECT 'default', u.id, 'v1:test-iv:test-ciphertext', 'v1', CURRENT_TIMESTAMP
FROM sys_user u
WHERE u.username = 'admin'
  AND NOT EXISTS (
    SELECT 1 FROM t_user_mfa m WHERE m.tenant_id = 'default' AND m.user_id = u.id
  );

INSERT INTO t_user_mfa (tenant_id, user_id, encrypted_totp_secret, secret_key_version, enabled_at)
SELECT 'default', u.id, 'v1:test-iv:test-ciphertext', 'v1', CURRENT_TIMESTAMP
FROM sys_user u
WHERE u.username = 'breakglass2'
  AND NOT EXISTS (
    SELECT 1 FROM t_user_mfa m WHERE m.tenant_id = 'default' AND m.user_id = u.id
  );
