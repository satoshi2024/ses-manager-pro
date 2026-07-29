-- 本番security設定テスト用の2人目のbreak-glass管理者。
INSERT INTO sys_user (username, password, real_name, role, email, status)
VALUES ('breakglass2', 'test-only-password', '非常用管理者2', '管理者', 'breakglass2@example.invalid', 1);
