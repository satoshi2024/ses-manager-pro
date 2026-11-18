-- V186相当のH2 schema（初回ログイン時のパスワード変更要求フラグ）。
-- V186本体はMySQL専用の DROP PROCEDURE を含むため、H2のreplayには列追加だけを置く。
ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS password_change_required TINYINT NOT NULL DEFAULT 0;
