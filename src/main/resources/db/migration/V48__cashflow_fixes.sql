INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
('cashflow.bp-payment-site-months', '1', 'BP支払の支払いサイト（対象月からの遅れ月数）。デフォルト1=翌月払い。'),
('cashflow.payroll-estimate', '0', 'freee給与連携未接続時の、当月給与実績の推定値フォールバック。');
