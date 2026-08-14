-- freee給与連携のH2テスト用スキーマ（給与明細自体は保存しない）
-- HFP-01-002: connection_status / freee_company_id / company+employee複合UNIQUE（V102_2と同期）
CREATE TABLE IF NOT EXISTS t_freee_connection (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_id BIGINT,
  company_name VARCHAR(200),
  access_token_encrypted TEXT NOT NULL,
  refresh_token_encrypted TEXT,
  token_expires_at TIMESTAMP,
  connected_by BIGINT,
  connection_status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED',
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS t_freee_employee_link (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  freee_employee_id VARCHAR(100) NOT NULL,
  freee_company_id BIGINT,
  confirmed_at TIMESTAMP,
  confirmed_by BIGINT,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE (engineer_id),
  UNIQUE (freee_company_id, freee_employee_id)
);
