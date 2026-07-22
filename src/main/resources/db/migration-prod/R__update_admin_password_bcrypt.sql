-- R__update_admin_password_bcrypt.sql
-- Repeatable Migration for Production Environment
-- prodプロファイル有効化時に、初期管理者アカウント(admin)のパスワードが
-- 平文'admin123'のままの場合に、BCryptハッシュ化へ自動更新する。
-- Repeatable migrationのため、DBのFlywayバージョンに関わらず起動時に確実評価・実行される。

UPDATE sys_user
SET password = '$2a$10$b4R.5YvbxFPJ5C39IEFE/ua.G9F82I11lOAyGGNBvWJuLSVFUl41y', -- BCrypt hash of admin123
    failed_count = 0,
    locked_until = NULL
WHERE username = 'admin'
  AND (
    password = 'admin123'
    OR password IS NULL
    OR password = '$2a$10$wT1B/7j/c1oY76N.zT1sC.H9a6n8R7E4m5h6i7j8k9l0m1n2o3p4q' -- known bad hash from previous script
  );
