-- V115: 要員PWAのoffline command冪等ledger。
-- 外部連携job・承認ledgerとは目的と保持境界が異なるため専用テーブルとする。
CREATE TABLE IF NOT EXISTS t_pwa_client_mutation (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  client_request_id VARCHAR(80) NOT NULL,
  user_id BIGINT NOT NULL,
  user_scope_hash CHAR(64) NOT NULL,
  operation VARCHAR(160) NULL,
  screen VARCHAR(40) NOT NULL,
  work_month CHAR(7),
  payload_hash CHAR(64) NOT NULL,
  base_version INT,
  status VARCHAR(20) NOT NULL,
  response_json LONGTEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  completed_at DATETIME,
  CONSTRAINT uk_pwa_client_mutation_user_request UNIQUE (user_id, client_request_id),
  INDEX idx_pwa_client_mutation_created (created_at),
  INDEX idx_pwa_client_mutation_scope (user_id, user_scope_hash)
);
