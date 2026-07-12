-- ============================================================
-- SES Manager Pro - ユーザーセキュリティ列追加 (P8 Task7)
-- ファイル: 007_user_security_columns.sql
-- 説明: ログイン失敗回数とアカウントロック解除日時を追加
-- ============================================================

ALTER TABLE sys_user
  ADD COLUMN failed_count INT      DEFAULT 0 COMMENT 'ログイン失敗回数',
  ADD COLUMN locked_until DATETIME NULL      COMMENT 'アカウントロック解除日時';
