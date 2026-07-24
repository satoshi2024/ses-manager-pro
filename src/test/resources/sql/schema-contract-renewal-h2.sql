-- テスト用(冪等): V50__contract_renewal_calendar.sql 相当を共有インメモリH2へ適用する。
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS renewal_decision VARCHAR(20);

INSERT INTO m_system_config (config_key, config_value, description) VALUES
  ('renewal.escalation-days', '30:営業,14:上長', '契約更新エスカレーション段階設定')
ON DUPLICATE KEY UPDATE description = VALUES(description);
