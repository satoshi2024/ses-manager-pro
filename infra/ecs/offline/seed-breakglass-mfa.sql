-- SIMULATION-ONLY seed for offline ECS-like prod boot (break-glass MFA).
-- Role copied from admin row to avoid client charset truncation of ENUM literals.
INSERT INTO sys_user (username, password, real_name, role, email, status)
SELECT 'breakglass2', 'sim-only-password-not-prod', 'breakglass2', u.role, 'breakglass2@sim.invalid', 1
FROM sys_user u
WHERE u.username = 'admin' AND IFNULL(u.deleted_flag, 0) = 0
  AND NOT EXISTS (SELECT 1 FROM sys_user x WHERE x.username = 'breakglass2' AND IFNULL(x.deleted_flag, 0) = 0);

INSERT INTO t_user_mfa (tenant_id, user_id, encrypted_totp_secret, secret_key_version, enabled_at)
SELECT 'default', u.id, 'v1:sim-iv:sim-ciphertext-not-prod', 'v1', CURRENT_TIMESTAMP
FROM sys_user u
WHERE u.username = 'admin' AND IFNULL(u.deleted_flag, 0) = 0
  AND NOT EXISTS (SELECT 1 FROM t_user_mfa m WHERE m.tenant_id = 'default' AND m.user_id = u.id);

INSERT INTO t_user_mfa (tenant_id, user_id, encrypted_totp_secret, secret_key_version, enabled_at)
SELECT 'default', u.id, 'v1:sim-iv:sim-ciphertext-not-prod', 'v1', CURRENT_TIMESTAMP
FROM sys_user u
WHERE u.username = 'breakglass2' AND IFNULL(u.deleted_flag, 0) = 0
  AND NOT EXISTS (SELECT 1 FROM t_user_mfa m WHERE m.tenant_id = 'default' AND m.user_id = u.id);
