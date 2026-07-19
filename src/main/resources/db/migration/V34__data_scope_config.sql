-- ============================================================
-- SES Manager Pro - データスコープ権限（data-scope-permission / P7）
-- ファイル: V34__data_scope_config.sql
-- 説明: 営業ロールの閲覧を担当データのみに制限するオプトインフラグをシードする（DDLなし）。
-- ============================================================

INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('scope.sales-own-data-only', 'false', '営業ロールの閲覧を担当データのみに制限(true/false)');
