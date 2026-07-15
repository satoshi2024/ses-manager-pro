CREATE TABLE t_work_record (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  contract_id    BIGINT NOT NULL COMMENT '契約ID',
  work_month     CHAR(7) NOT NULL COMMENT '対象月(YYYY-MM)',
  actual_hours   DECIMAL(5,1) NOT NULL COMMENT '実績時間(h)',
  billing_amount DECIMAL(12,0) COMMENT '請求金額(円・精算計算済)',
  payment_amount DECIMAL(12,0) COMMENT '支払金額(円・BP原価側)',
  status         ENUM('入力中','確定') DEFAULT '入力中' COMMENT 'ステータス',
  remarks        VARCHAR(500) COMMENT '備考',
  created_by     BIGINT COMMENT '登録者ID',
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_work_record (contract_id, work_month),
  INDEX idx_work_record_month (work_month),
  CONSTRAINT fk_workrecord_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='月次実績工数';

CREATE TABLE t_invoice (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  invoice_no    VARCHAR(30) NOT NULL UNIQUE COMMENT '請求書番号(INV-YYYYMM-NNNN)',
  customer_id   BIGINT NOT NULL COMMENT '顧客ID',
  billing_month CHAR(7) NOT NULL COMMENT '対象月(YYYY-MM)',
  subtotal      DECIMAL(12,0) NOT NULL COMMENT '小計(円)',
  tax           DECIMAL(12,0) NOT NULL COMMENT '消費税(円)',
  total         DECIMAL(12,0) NOT NULL COMMENT '合計(円)',
  status        ENUM('未送付','送付済','入金済') DEFAULT '未送付' COMMENT 'ステータス',
  issued_date   DATE COMMENT '発行日',
  paid_date     DATE COMMENT '入金日',
  remarks       VARCHAR(500),
  created_by    BIGINT,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT DEFAULT 0,
  INDEX idx_invoice_customer (customer_id),
  INDEX idx_invoice_month (billing_month),
  CONSTRAINT fk_invoice_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='請求書';

CREATE TABLE t_invoice_item (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_id     BIGINT NOT NULL,
  work_record_id BIGINT NOT NULL UNIQUE COMMENT '二重請求防止(1実績=1明細)',
  description    VARCHAR(300) COMMENT '摘要(要員名・案件名など)',
  amount         DECIMAL(12,0) NOT NULL COMMENT '金額(円)',
  FOREIGN KEY (invoice_id) REFERENCES t_invoice(id) ON DELETE CASCADE,
  FOREIGN KEY (work_record_id) REFERENCES t_work_record(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='請求書明細';

CREATE TABLE t_bp_payment (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_record_id     BIGINT NOT NULL COMMENT '対象実績',
  layer_order        INT NOT NULL DEFAULT 1 COMMENT '階層番号(1=技術者に最も近い一次請)',
  payee_company_name VARCHAR(200) COMMENT '支払先協力会社名',
  parent_payment_id  BIGINT COMMENT '上位階層への自己参照(同一work_record_id内)',
  amount             DECIMAL(12,0) NOT NULL COMMENT '支払金額(円)',
  status             VARCHAR(20) NOT NULL DEFAULT '未払',
  paid_date          DATE COMMENT '支払日',
  remarks            VARCHAR(500),
  deleted_flag       TINYINT NOT NULL DEFAULT 0,
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_work_record_layer (work_record_id, layer_order),
  CONSTRAINT fk_bp_payment_parent FOREIGN KEY (parent_payment_id) REFERENCES t_bp_payment(id),
  FOREIGN KEY (work_record_id) REFERENCES t_work_record(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='BP支払';

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
 ('work-record', '勤怠・実績', '/work-record', '/api/work-records', 55),
 ('invoice',     '請求・支払', '/invoice',     '/api/invoices',     56);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key IN ('work-record','invoice')
UNION ALL SELECT '営業', id FROM m_menu WHERE menu_key IN ('work-record','invoice')
UNION ALL SELECT 'マネージャー', id FROM m_menu WHERE menu_key IN ('work-record','invoice')
UNION ALL SELECT 'HR', id FROM m_menu WHERE menu_key = 'work-record';
