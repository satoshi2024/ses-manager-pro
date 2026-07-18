-- V35__monthly_closing_json_seed.sql
INSERT INTO m_system_config (config_key, config_value, description)
VALUES ('closing.confirmed-months', '[]', '月次締め済み月の記録(JSON)')
ON DUPLICATE KEY UPDATE config_key = config_key;