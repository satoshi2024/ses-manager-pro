-- テスト用(冪等): V29__quotation.sql の t_quotation 追加・t_contract.quotation_id 追加相当を
-- 共有インメモリH2へ適用する。複数コンテキストで再実行されても失敗しないよう IF NOT EXISTS を用いる。
CREATE TABLE IF NOT EXISTS t_quotation (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  quotation_no          VARCHAR(30) NOT NULL UNIQUE,
  customer_id           BIGINT NOT NULL,
  project_id            BIGINT,
  engineer_id           BIGINT,
  proposal_id           BIGINT,
  title                 VARCHAR(200) NOT NULL,
  unit_price            DECIMAL(10,0) NOT NULL,
  settlement_hours_min  DECIMAL(5,1),
  settlement_hours_max  DECIMAL(5,1),
  valid_until           DATE,
  status                VARCHAR(20) NOT NULL DEFAULT '下書き',
  remarks               VARCHAR(500),
  created_by            BIGINT,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT DEFAULT 0
);

ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS quotation_id BIGINT;

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
  SELECT 'quotation', '見積管理', '/quotation', '/api/quotations', 50
  WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'quotation');
