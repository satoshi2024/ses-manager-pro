-- ============================================================
-- SES Manager Pro - 売上着地予測（revenue-forecast / P5）
-- ファイル: V30__revenue_forecast_config.sql
-- 説明: ステージ別受注確率と予測表示フラグを m_system_config にシードする（DDLなし）。
-- ============================================================

INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('forecast.enabled',                    'true', '売上着地予測の表示(true/false)'),
  ('forecast.win-rate.screening',         '20',   '受注確率: 書類選考中(%)'),
  ('forecast.win-rate.first-interview',   '40',   '受注確率: 一次面接(%)'),
  ('forecast.win-rate.second-interview',  '60',   '受注確率: 二次面接(%)'),
  ('forecast.win-rate.awaiting',          '80',   '受注確率: 結果待ち(%)');
