-- ============================================================
-- SES Manager Pro - 将来稼働率・Bench予測 設定シード (FR-07)
-- ファイル: V51__add_forecast_assume_renew_config.sql
-- 説明: 将来稼働率予測の自動更新継続判定フラグを m_system_config にシードする
-- ============================================================

INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('forecast.assume-renew', 'true', '将来稼働率予測で自動更新(autoRenew=1)の契約を更新継続とみなすか(true/false)');
