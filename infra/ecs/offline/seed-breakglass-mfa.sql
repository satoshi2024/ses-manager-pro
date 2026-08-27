-- SIMULATION-ONLY seed for offline ECS-like prod boot (break-glass MFA).
-- Not production data. No real PII / payroll / customer contracts.
INSERT INTO sys_user (username, password, real_name, role, email, status)
SELECT 'breakglass2', 'sim-only-password-not-prod', '非常用管理者2', '管理者', 'breakglass2@sim.invalid', 1
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'breakglass2' AND deleted_flag = 0);

INSERT INTO t_user_mfa (tenant_id, user_id, encrypted_totp_secret, secret_key_version, enabled_at)
SELECT 'default', u.id, 'v1:sim-iv:sim-ciphertext-not-prod', 'v1', CURRENT_TIMESTAMP
FROM sys_user u
WHERE u.username = 'admin' AND u.deleted_flag = 0
  AND NOT EXISTS (SELECT 1 FROM t_user_mfa m WHERE m.tenant_id = 'default' AND m.user_id = u.id);

INSERT INTO t_user_mfa (tenant_id, user_id, encrypted_totp_secret, secret_key_version, enabled_at)
SELECT 'default', u.id, 'v1:sim-iv:sim-ciphertext-not-prod', 'v1', CURRENT_TIMESTAMP
FROM sys_user u
WHERE u.username = 'breakglass2' AND u.deleted_flag = 0
  AND NOT EXISTS (SELECT 1 FROM t_user_mfa m WHERE m.tenant_id = 'default' AND m.user_id = u.id);
