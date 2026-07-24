-- V46__cashflow_forecast.sql
-- 資金繰り予測用の設定キー初期データ
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
('cashflow.opening-balance', '0', '資金繰り予測：期首残高'),
('cashflow.fixed-cost', '0', '資金繰り予測：固定費（月額）'),
('cashflow.alert-threshold', '0', '資金繰り予測：警告閾値（残高）');
