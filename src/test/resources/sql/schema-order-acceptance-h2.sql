-- テスト用(冪等): V80__order_acceptance_workflow.sql のDDL相当を共有インメモリH2へ適用する。
-- MySQL固有DDL(ENGINE/COLLATE/FK)はH2方言へ読み替える（platform-invariants §4.3）。
-- R09-P1-05: 孤児order_line_idの拒否(FK)をH2でも検証するため、他schema(engineer-schema等)が
-- SET REFERENTIAL_INTEGRITY FALSE にした共有DBの設定を復元する（本schemaのreplay時点でFK強制をON）。
SET REFERENTIAL_INTEGRITY TRUE;

DROP TABLE IF EXISTS t_acceptance CASCADE;
DROP TABLE IF EXISTS t_sales_order_line CASCADE;
DROP TABLE IF EXISTS t_sales_order CASCADE;

-- 共有H2は複数contextでschema-locationsを再実行するため、先行適用の残骸(FK/旧索引)を先に落としてから
-- 正しい形状(UNIQUE + FK)へ再構築する（R09-P1-05）
ALTER TABLE t_contract DROP CONSTRAINT IF EXISTS fk_contract_order_line;
DROP INDEX IF EXISTS uk_contract_order_line;
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS order_line_id BIGINT;
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS acceptance_required TINYINT NOT NULL DEFAULT 1;
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS acceptance_exemption_reason VARCHAR(500);
CREATE UNIQUE INDEX uk_contract_order_line ON t_contract(order_line_id);

DROP TABLE IF EXISTS t_contract_acceptance_backfill CASCADE;
CREATE TABLE IF NOT EXISTS t_contract_acceptance_backfill (
  contract_id   BIGINT PRIMARY KEY,
  backfilled_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_sales_order (
  id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                VARCHAR(100) NOT NULL DEFAULT 'default',
  legal_entity_id          BIGINT,
  order_no                 VARCHAR(30) NOT NULL,
  customer_po_no           VARCHAR(100),
  customer_id              BIGINT NOT NULL,
  contact_id               BIGINT,
  quotation_id             BIGINT,
  order_date               DATE NOT NULL,
  start_date               DATE,
  end_date                 DATE,
  status                   VARCHAR(20) NOT NULL DEFAULT '下書き',
  total_amount_snapshot    DECIMAL(15,0),
  payment_terms_snapshot   VARCHAR(200),
  source_document_id       BIGINT,
  acknowledgement_document_id BIGINT,
  version                  INT NOT NULL DEFAULT 0,
  created_by               BIGINT,
  created_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag             TINYINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sales_order_no ON t_sales_order(order_no);
CREATE INDEX IF NOT EXISTS idx_sales_order_customer ON t_sales_order(customer_id);
CREATE INDEX IF NOT EXISTS idx_sales_order_po ON t_sales_order(customer_id, customer_po_no);
CREATE INDEX IF NOT EXISTS idx_sales_order_date ON t_sales_order(order_date);
CREATE INDEX IF NOT EXISTS idx_sales_order_status ON t_sales_order(status);

CREATE TABLE IF NOT EXISTS t_sales_order_line (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  order_id       BIGINT NOT NULL,
  line_no        INT NOT NULL,
  project_id     BIGINT,
  engineer_id    BIGINT NOT NULL,
  quantity       INT NOT NULL DEFAULT 1,
  unit_price     DECIMAL(12,0) NOT NULL,
  settlement_min DECIMAL(5,1),
  settlement_max DECIMAL(5,1),
  amount         DECIMAL(12,0),
  remarks        VARCHAR(500),
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag   TINYINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_sales_order_line ON t_sales_order_line(order_id, line_no);
CREATE INDEX IF NOT EXISTS idx_sales_order_line_engineer ON t_sales_order_line(engineer_id);
CREATE INDEX IF NOT EXISTS idx_sales_order_line_project ON t_sales_order_line(project_id);

CREATE TABLE IF NOT EXISTS t_acceptance (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_id          BIGINT NOT NULL,
  work_record_id       BIGINT,
  work_month           CHAR(7) NOT NULL,
  status               VARCHAR(20) NOT NULL DEFAULT '未提出',
  submitted_at         DATETIME,
  customer_contact_id  BIGINT,
  customer_contact_name_snapshot VARCHAR(100),
  accepted_at          DATETIME,
  reject_comment       VARCHAR(500),
  document_id          BIGINT,
  hours_snapshot       DECIMAL(6,2),
  amount_snapshot      DECIMAL(12,0),
  work_record_updated_at DATETIME,
  version              INT NOT NULL DEFAULT 0,
  created_by           BIGINT,
  created_at           DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at           DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag         TINYINT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_acceptance_contract_month ON t_acceptance(contract_id, work_month);
CREATE INDEX IF NOT EXISTS idx_acceptance_work_record ON t_acceptance(work_record_id);
CREATE INDEX IF NOT EXISTS idx_acceptance_status_month ON t_acceptance(status, work_month);

-- R09-P1-05: 孤児 order_line_id の拒否（t_sales_order_line 作成後にFKを追加）
ALTER TABLE t_contract ADD CONSTRAINT IF NOT EXISTS fk_contract_order_line
  FOREIGN KEY (order_line_id) REFERENCES t_sales_order_line(id);
