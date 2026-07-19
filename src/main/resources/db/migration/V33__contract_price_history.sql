-- ============================================================
-- SES Manager Pro - 契約単価の改定履歴（contract-price-history / P6）
-- ファイル: V33__contract_price_history.sql
-- 説明: 期間別単価の履歴テーブルを追加する。
-- ============================================================

CREATE TABLE t_contract_price_history (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_id      BIGINT NOT NULL,
  apply_from_month CHAR(7) NOT NULL COMMENT '適用開始月(YYYY-MM)',
  selling_price    DECIMAL(10,0) NOT NULL,
  cost_price       DECIMAL(10,0) NOT NULL,
  reason           VARCHAR(300) COMMENT '改定理由',
  created_by       BIGINT,
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_cph (contract_id, apply_from_month),
  FOREIGN KEY (contract_id) REFERENCES t_contract(id) ON DELETE CASCADE
) COMMENT='契約単価改定履歴';
