-- ============================================================
-- prod 環境専用マイグレーション
-- V2 でシードした admin ユーザーのパスワードは平文 'admin123'（dev/test の
-- NoOpPasswordEncoder 前提）。prod は BCryptPasswordEncoder を使うため、
-- 平文のままでは絶対にログインできず、かつログイン失敗が続くとアカウント
-- ロック機能により admin 自身がロックされてしまう。
--
-- そのため prod 起動時のみ、この BCrypt ハッシュ（'admin123' の実際のハッシュ、
-- BCryptPasswordEncoder().encode("admin123") で生成・matches()で検証済み）に
-- 置き換える。WHERE 句で平文のままの場合のみ更新するため、運用開始後に
-- 管理者がパスワードを変更していれば上書きされない。
--
-- 【重要】本番運用開始後は速やかに admin のパスワードを変更すること。
-- ============================================================
UPDATE sys_user
SET password = '$2a$10$b4R.5YvbxFPJ5C39IEFE/ua.G9F82I11lOAyGGNBvWJuLSVFUl41y'
WHERE username = 'admin' AND password = 'admin123';
