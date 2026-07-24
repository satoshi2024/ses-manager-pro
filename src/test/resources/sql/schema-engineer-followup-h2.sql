-- テスト用(冪等): V50__engineer_followup.sql 相当を共有インメモリH2へ適用する。
CREATE TABLE IF NOT EXISTS t_engineer_followup (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id   BIGINT NOT NULL,
  followup_type VARCHAR(20) NOT NULL,
  followup_date DATE NOT NULL,
  satisfaction  TINYINT,
  topic         VARCHAR(200),
  content       TEXT,
  next_date     DATE,
  created_by    BIGINT,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT NOT NULL DEFAULT 0
);

INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('retention.risk.bench-warn-days', '30', '定着リスク: 長期Bench加点の基準日数'),
  ('retention.risk.followup-interval-days', '30', '定着リスク: フォロー間隔超過とみなす基準日数'),
  ('retention.risk.threshold', '60', '定着リスク: 高リスクと判定するスコア閾値(0-100)');
