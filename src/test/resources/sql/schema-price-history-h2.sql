-- テスト用(冪等): V33__contract_price_history.sql 相当を共有インメモリH2へ適用する。
CREATE TABLE IF NOT EXISTS t_contract_price_history (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_id      BIGINT NOT NULL,
  apply_from_month CHAR(7) NOT NULL,
  selling_price    DECIMAL(10,0) NOT NULL,
  cost_price       DECIMAL(10,0) NOT NULL,
  reason           VARCHAR(300),
  created_by       BIGINT,
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_cph (contract_id, apply_from_month)
);
