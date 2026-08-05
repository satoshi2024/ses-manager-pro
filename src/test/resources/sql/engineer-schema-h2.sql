-- 削除機能検証用の最小スキーマ（H2 / MySQLモード互換）
-- 本番の 001_create_tables.sql は ENUM / ENGINE=InnoDB / インライン INDEX など
-- H2 が解釈できない構文を含むため、検証に必要な列だけを持つ簡易版を用意する。
SET REFERENTIAL_INTEGRITY FALSE;
DROP TABLE IF EXISTS t_mail_delivery CASCADE;
CREATE TABLE t_mail_delivery (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  recipient VARCHAR(320) NOT NULL,
  subject VARCHAR(500) NOT NULL,
  body TEXT NOT NULL,
  status VARCHAR(20) NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  error_message VARCHAR(1000),
  invoice_id BIGINT,
  contact_id BIGINT,
  opportunity_id BIGINT,
  queued_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  sent_at DATETIME,
  failed_at DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
DROP TABLE IF EXISTS m_customer CASCADE;
CREATE TABLE m_customer (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_name      VARCHAR(200) NOT NULL,
  company_name_kana VARCHAR(200),
  contact_person    VARCHAR(100),
  contact_email     VARCHAR(100),
  contact_phone     VARCHAR(20),
  address           VARCHAR(500),
  commercial_flow   VARCHAR(50),
  trust_level       VARCHAR(10) DEFAULT 'B',
  remarks           TEXT,
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag      TINYINT DEFAULT 0
);

DROP TABLE IF EXISTS t_engineer CASCADE;

CREATE TABLE t_engineer (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  full_name           VARCHAR(100),
  full_name_kana      VARCHAR(100),
  initial_name        VARCHAR(10),
  gender              VARCHAR(10),
  birth_date          DATE,
  nationality         VARCHAR(50),
  nearest_station     VARCHAR(100),
  prefecture          VARCHAR(50),
  railway_company     VARCHAR(150),
  employment_type     VARCHAR(20),
  status              VARCHAR(20),
  expected_unit_price DECIMAL(10,0),
  cost_center_id      BIGINT,
  organization_id     BIGINT,
  available_date      DATE,
  experience_years    INT,
  japanese_level      VARCHAR(20),
  resume_summary      TEXT,
  photo_url           VARCHAR(500),
  remarks             TEXT,
  created_by          BIGINT,
  created_at          DATETIME,
  updated_at          DATETIME,
  deleted_flag        TINYINT DEFAULT 0
);

DROP TABLE IF EXISTS m_skill_tag CASCADE;
CREATE TABLE m_skill_tag (
  id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
  skill_name VARCHAR(100) NOT NULL,
  category   VARCHAR(50),
  created_at DATETIME
);

DROP TABLE IF EXISTS t_engineer_skill CASCADE;
CREATE TABLE t_engineer_skill (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id      BIGINT NOT NULL,
  skill_id         BIGINT NOT NULL,
  proficiency      VARCHAR(20),
  experience_years INT
);

DROP TABLE IF EXISTS t_engineer_sales CASCADE;
CREATE TABLE t_engineer_sales (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id   BIGINT NOT NULL,
  sales_user_id BIGINT NOT NULL,
  primary_flag  TINYINT NOT NULL DEFAULT 0,
  assigned_at   DATE NOT NULL,
  released_at   DATE,
  remarks       VARCHAR(500),
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT NOT NULL DEFAULT 0
);

DROP TABLE IF EXISTS t_engineer_followup CASCADE;
CREATE TABLE t_engineer_followup (
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

DROP TABLE IF EXISTS t_engineer_career CASCADE;
CREATE TABLE t_engineer_career (
  id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
  engineer_id     BIGINT       NOT NULL,
  period_from     DATE,
  period_to       DATE,
  project_name    VARCHAR(200),
  client_industry VARCHAR(100),
  role            VARCHAR(100),
  description     TEXT,
  tech_stack      VARCHAR(500),
  team_size       INT
);

DROP TABLE IF EXISTS t_project_skill CASCADE;
CREATE TABLE t_project_skill (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id     BIGINT NOT NULL,
  skill_id       BIGINT NOT NULL,
  required_level VARCHAR(20),
  is_must        TINYINT DEFAULT 1
);

DROP TABLE IF EXISTS t_notification CASCADE;
CREATE TABLE t_notification (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  type        VARCHAR(30)  NOT NULL,
  title       VARCHAR(200) NOT NULL,
  message     VARCHAR(500),
  link_url    VARCHAR(300),
  menu_key    VARCHAR(50),
  organization_id BIGINT,
  recipient_user_id BIGINT,
  dedupe_key  VARCHAR(200) NOT NULL UNIQUE,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_notification_read CASCADE;
CREATE TABLE t_notification_read (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  user_id         BIGINT NOT NULL,
  read_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_notification_read (notification_id, user_id)
);

DROP TABLE IF EXISTS t_project CASCADE;
CREATE TABLE t_project (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_name      VARCHAR(200) NOT NULL,
  customer_id       BIGINT,
  commercial_flow   VARCHAR(50),
  description       TEXT,
  required_count    INT DEFAULT 1,
  unit_price_min    DECIMAL(10,0),
  unit_price_max    DECIMAL(10,0),
  work_location     VARCHAR(200),
  remote_type       VARCHAR(20),
  status            VARCHAR(30) DEFAULT '募集中',
  priority          VARCHAR(20) DEFAULT '通常',
  start_date        DATE,
  end_date          DATE,
  remarks           TEXT,
  source_opportunity_id BIGINT,
  created_by        BIGINT,
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag      TINYINT DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_source_opportunity ON t_project(source_opportunity_id);

DROP TABLE IF EXISTS t_contract CASCADE;
CREATE TABLE t_contract (
  id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_no             VARCHAR(50),
  proposal_id             BIGINT,
  engineer_id             BIGINT,
  project_id              BIGINT,
  customer_id             BIGINT,
  contract_type           VARCHAR(20),
  start_date              DATE,
  end_date                DATE,
  selling_price           DECIMAL(10,2),
  cost_price              DECIMAL(10,2),
  cost_center_id          BIGINT,
  settlement_hours_min    DECIMAL(5,1),
  settlement_hours_max    DECIMAL(5,1),
  fraction_rule           VARCHAR(50),
  auto_renew              TINYINT DEFAULT 0,
  status                  VARCHAR(20) DEFAULT '稼動中',
  contract_date           DATE,
  job_description         VARCHAR(2000),
  work_location           VARCHAR(500),
  inspection_due_date     DATE,
  payment_due_date        DATE,
  payment_method          VARCHAR(50),
  remarks                 TEXT,
  direct_command_flag     TINYINT NOT NULL DEFAULT 0,
  sales_user_id           BIGINT,
  commission_base_type    VARCHAR(10),
  commission_rate         DECIMAL(5,2),
  renewed_from_contract_id BIGINT,
  quotation_id            BIGINT,
  renewal_decision        VARCHAR(20),
  order_line_id           BIGINT,
  acceptance_required     TINYINT NOT NULL DEFAULT 1,
  created_by              BIGINT,
  created_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at              DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag            TINYINT DEFAULT 0,
  version                 INT NOT NULL DEFAULT 0
);

DROP TABLE IF EXISTS t_quotation CASCADE;

DROP TABLE IF EXISTS t_acceptance CASCADE;
DROP TABLE IF EXISTS t_sales_order_line CASCADE;
DROP TABLE IF EXISTS t_sales_order CASCADE;
CREATE TABLE t_sales_order (
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
CREATE TABLE t_sales_order_line (
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
CREATE TABLE t_acceptance (
  id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_id          BIGINT NOT NULL,
  work_record_id       BIGINT,
  work_month           CHAR(7) NOT NULL,
  status               VARCHAR(20) NOT NULL DEFAULT '未提出',
  submitted_at         DATETIME,
  customer_contact_id  BIGINT,
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

CREATE TABLE t_quotation (
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
  source_opportunity_id BIGINT,
  created_by            BIGINT,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT DEFAULT 0,
  version               INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_quotation_source_opportunity ON t_quotation(source_opportunity_id);

DROP TABLE IF EXISTS t_proposal CASCADE;

CREATE TABLE t_proposal (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id          BIGINT NOT NULL,
  engineer_id         BIGINT NOT NULL,
  status              VARCHAR(30) NOT NULL,
  proposed_unit_price DECIMAL(10,0),
  skill_sheet_path    VARCHAR(500),
  proposal_email_text TEXT,
  ai_match_score      DECIMAL(5,2),
  match_reason        TEXT,
  proposed_by         BIGINT,
  source_opportunity_id BIGINT,
  proposed_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  closed_at           DATETIME,
  remarks             TEXT,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag        TINYINT DEFAULT 0
);

DROP TABLE IF EXISTS t_proposal_history CASCADE;
CREATE TABLE t_proposal_history (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  proposal_id     BIGINT NOT NULL,
  from_status     VARCHAR(30),
  to_status       VARCHAR(30) NOT NULL,
  changed_by      BIGINT,
  changed_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  remarks         TEXT
);

DROP TABLE IF EXISTS t_work_record CASCADE;
CREATE TABLE t_work_record (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_id    BIGINT NOT NULL,
  work_month     CHAR(7) NOT NULL,
  actual_hours   DECIMAL(6,2) NOT NULL,
  billing_amount DECIMAL(12,0),
  payment_amount DECIMAL(12,0),
  organization_id BIGINT,
  cost_center_id BIGINT,
  accounting_dimension_frozen TINYINT DEFAULT 0,
  reject_comment VARCHAR(500),
  status         VARCHAR(20) DEFAULT '入力中',
  remarks        VARCHAR(500),
  created_by     BIGINT,
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_work_record (contract_id, work_month)
);

DROP TABLE IF EXISTS t_invoice CASCADE;
CREATE TABLE t_invoice (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_no    VARCHAR(30) NOT NULL UNIQUE,
  customer_id   BIGINT NOT NULL,
  billing_month CHAR(7) NOT NULL,
  subtotal      DECIMAL(12,0) NOT NULL,
  tax           DECIMAL(12,0) NOT NULL,
  total         DECIMAL(12,0) NOT NULL,
  cost_center_id BIGINT,
  tax_rate      DECIMAL(4,3),
  status        VARCHAR(20) DEFAULT '未送付',
  issued_date   DATE,
  paid_date     DATE,
  due_date      DATE,
  remarks       VARCHAR(500),
  created_by    BIGINT,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT DEFAULT 0,
  version       INT NOT NULL DEFAULT 0
);

DROP TABLE IF EXISTS t_invoice_item CASCADE;
CREATE TABLE t_invoice_item (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_id     BIGINT NOT NULL,
  work_record_id BIGINT NOT NULL UNIQUE,
  description    VARCHAR(300),
  amount         DECIMAL(12,0) NOT NULL
);

DROP TABLE IF EXISTS t_invoice_payment CASCADE;
CREATE TABLE t_invoice_payment (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_id   BIGINT NOT NULL,
  paid_date    DATE NOT NULL,
  amount       DECIMAL(12,0) NOT NULL,
  fee          DECIMAL(12,0) NOT NULL DEFAULT 0,
  remarks      VARCHAR(300),
  created_by   BIGINT,
  created_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at   DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_bp_payment CASCADE;
CREATE TABLE t_bp_payment (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_record_id     BIGINT NOT NULL,
  layer_order        INT NOT NULL DEFAULT 1,
  payee_company_name VARCHAR(200),
  bp_company_id      BIGINT,
  bp_company_name_snapshot VARCHAR(255),
  terms_snapshot_json TEXT,
  parent_payment_id  BIGINT,
  amount             DECIMAL(12,0) NOT NULL,
  cost_center_id     BIGINT,
  status             VARCHAR(20) DEFAULT '未払',
  paid_date          DATE,
  remarks            VARCHAR(500),
  deleted_flag       TINYINT NOT NULL DEFAULT 0,
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  version            INT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_work_record_layer (work_record_id, layer_order),
  CONSTRAINT fk_bp_payment_parent FOREIGN KEY (parent_payment_id) REFERENCES t_bp_payment(id)
);

DROP TABLE IF EXISTS t_sales_activity CASCADE;
CREATE TABLE t_sales_activity (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id      BIGINT NOT NULL,
  contact_id       BIGINT,
  opportunity_id   BIGINT,
  activity_type    VARCHAR(20) NOT NULL,
  activity_date    DATE NOT NULL,
  title            VARCHAR(200) NOT NULL,
  content          TEXT,
  next_action_date DATE,
  completed_flag   TINYINT DEFAULT 0,
  assignee_user_id BIGINT,
  version          INT DEFAULT 1,
  created_by       BIGINT,
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag     TINYINT DEFAULT 0
);

-- ============================================================
-- CRM: 顧客担当者 / リード / 商機 (V73)
-- 本ファイルは他テーブル同様にFKを張らない方針のため、CRMもFK無しで揃える。
-- 主担当一意(uk_customer_contact_active_primary)は業務不変条件なので生成列ごと再現する。
-- ============================================================
DROP TABLE IF EXISTS t_opportunity CASCADE;
DROP TABLE IF EXISTS t_lead CASCADE;
DROP TABLE IF EXISTS t_customer_contact CASCADE;

CREATE TABLE t_customer_contact (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id   BIGINT NOT NULL,
  name          VARCHAR(100) NOT NULL,
  name_kana     VARCHAR(100),
  department    VARCHAR(100),
  position      VARCHAR(100),
  roles_json    CLOB,
  email         VARCHAR(255),
  phone         VARCHAR(50),
  primary_flag  TINYINT DEFAULT 0,
  valid_from    DATE NOT NULL,
  valid_to      DATE,
  status        VARCHAR(20) NOT NULL DEFAULT '有効',
  version       INT NOT NULL DEFAULT 1,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT DEFAULT 0,
  active_primary_customer_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN primary_flag = 1 AND valid_to IS NULL AND status = '有効' AND deleted_flag = 0
         THEN customer_id ELSE NULL END
  ),
  CONSTRAINT uk_customer_contact_active_primary UNIQUE (active_primary_customer_id)
);

CREATE TABLE t_lead (
  id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
  company_name             VARCHAR(200) NOT NULL,
  company_name_normalized  VARCHAR(200),
  contact_name             VARCHAR(100),
  contact_email            VARCHAR(255),
  contact_email_normalized VARCHAR(255),
  contact_phone            VARCHAR(50),
  contact_phone_normalized VARCHAR(50),
  source                   VARCHAR(100),
  source_cost              DECIMAL(14,0),
  owner_user_id            BIGINT,
  status                   VARCHAR(20) NOT NULL DEFAULT '未対応',
  converted_customer_id    BIGINT,
  converted_opportunity_id BIGINT,
  version                  INT NOT NULL DEFAULT 1,
  created_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag             TINYINT DEFAULT 0
);

CREATE TABLE t_opportunity (
  id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id            BIGINT NOT NULL,
  title                  VARCHAR(200) NOT NULL,
  stage                  VARCHAR(30) NOT NULL DEFAULT '見込',
  expected_start_month   VARCHAR(7),
  duration_months        INT,
  required_count         INT DEFAULT 1,
  unit_price             DECIMAL(12,0),
  expected_amount        DECIMAL(14,0),
  probability            INT,
  probability_override_reason VARCHAR(500),
  stage_changed_at       DATETIME,
  owner_user_id          BIGINT,
  next_action_date       DATE,
  competitor             VARCHAR(500),
  lost_reason            VARCHAR(500),
  converted_project_id   BIGINT,
  converted_quotation_id BIGINT,
  version                INT NOT NULL DEFAULT 1,
  created_at             DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag           TINYINT DEFAULT 0
);

DROP TABLE IF EXISTS t_candidate_activity CASCADE;
DROP TABLE IF EXISTS t_candidate CASCADE;
CREATE TABLE t_candidate (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  name                  VARCHAR(100) NOT NULL,
  contact_email         VARCHAR(200),
  contact_phone         VARCHAR(20),
  skill_summary         VARCHAR(1000),
  desired_rate          DECIMAL(10,0),
  source                VARCHAR(50),
  current_stage         VARCHAR(20) NOT NULL DEFAULT '応募受付',
  next_action_date      DATE,
  converted_engineer_id BIGINT,
  remarks               VARCHAR(1000),
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  created_by            BIGINT,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

CREATE TABLE t_candidate_activity (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  candidate_id BIGINT NOT NULL,
  stage        VARCHAR(20) NOT NULL,
  reason       VARCHAR(500),
  changed_by   BIGINT,
  changed_at   DATETIME DEFAULT CURRENT_TIMESTAMP,
  remarks      VARCHAR(500),
  CONSTRAINT fk_candidate_activity_candidate FOREIGN KEY (candidate_id) REFERENCES t_candidate(id)
);

DROP TABLE IF EXISTS m_menu CASCADE;
CREATE TABLE m_menu (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  menu_key    VARCHAR(50) NOT NULL,
  menu_name   VARCHAR(100),
  path_prefix VARCHAR(100),
  api_prefix  VARCHAR(100),
  sort_order  INT DEFAULT 0,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- @Sqlで共有H2 schemaを再構築した後も、画面レンダリングに必要な管理者メニューを保持する。
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
  ('dashboard', 'ダッシュボード', '/dashboard', '/api/dashboard', 1),
  ('engineer',  '要員管理',       '/engineer',  '/api/engineers', 2),
  ('customer',  '顧客管理',       '/customer',  '/api/customers', 3),
  ('project',   '案件管理',       '/project',   '/api/projects', 4),
  ('proposal',  '提案管理',       '/proposal',  '/api/proposals', 5),
  ('contract',  '契約管理',       '/contract',  '/api/contracts', 6),
  ('ai',        'AI機能',         '/ai',        '/api/ai', 7),
  ('email',     'メールテンプレート', '/email/template', '/api/email-templates', 8),
  ('user',      'ユーザー管理',   '/user',       '/api/users', 9);

DROP TABLE IF EXISTS t_role_menu CASCADE;
CREATE TABLE t_role_menu (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  role     VARCHAR(50) NOT NULL,
  menu_id  BIGINT NOT NULL
);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu;
INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM m_menu m
CROSS JOIN (SELECT '営業' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー') r
WHERE m.menu_key <> 'user';

DROP TABLE IF EXISTS sys_user CASCADE;
CREATE TABLE sys_user (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(50) NOT NULL UNIQUE,
  password      VARCHAR(255) NOT NULL,
  real_name     VARCHAR(50),
  role          VARCHAR(50) NOT NULL,
  email         VARCHAR(100),
  status        TINYINT DEFAULT 1,
  failed_count  INT DEFAULT 0,
  locked_until  DATETIME,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT DEFAULT 0
);

INSERT INTO sys_user (username, password, real_name, role, email, status)
VALUES ('admin', 'admin123', 'システム管理者', '管理者', 'admin@ses.local', 1);

DROP TABLE IF EXISTS m_system_config CASCADE;
CREATE TABLE m_system_config (
  config_key   VARCHAR(100) PRIMARY KEY,
  config_value TEXT,
  description  VARCHAR(200)
);

DROP TABLE IF EXISTS t_ai_log CASCADE;
CREATE TABLE t_ai_log (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  request_type   VARCHAR(30),
  request_params TEXT,
  response_text  TEXT,
  tokens_used    INT,
  cost_jpy       DECIMAL(10,4),
  created_by     BIGINT,
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS m_email_template CASCADE;
CREATE TABLE m_email_template (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  template_name    VARCHAR(100) NOT NULL,
  subject_template VARCHAR(500) NOT NULL,
  body_template    TEXT NOT NULL,
  template_type    VARCHAR(30),
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_audit_log CASCADE;
CREATE TABLE t_audit_log (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  username   VARCHAR(50),
  method     VARCHAR(10) NOT NULL,
  uri        VARCHAR(500) NOT NULL,
  status     INT NOT NULL,
  application_code VARCHAR(64),
  success_flag BOOLEAN NOT NULL DEFAULT TRUE,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_freee_connection CASCADE;
CREATE TABLE t_freee_connection (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_id BIGINT, company_name VARCHAR(200),
 access_token_encrypted TEXT NOT NULL, refresh_token_encrypted TEXT, token_expires_at DATETIME,
 connected_by BIGINT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
 deleted_flag TINYINT DEFAULT 0
);
DROP TABLE IF EXISTS t_freee_employee_link CASCADE;
CREATE TABLE t_freee_employee_link (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, engineer_id BIGINT NOT NULL, freee_employee_id VARCHAR(100) NOT NULL,
 confirmed_at DATETIME, confirmed_by BIGINT, created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
 deleted_flag TINYINT DEFAULT 0, UNIQUE(engineer_id), UNIQUE(freee_employee_id)
);

DROP TABLE IF EXISTS t_engineer_account_link CASCADE;
CREATE TABLE t_engineer_account_link (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL UNIQUE,
  sys_user_id BIGINT NOT NULL UNIQUE,
  linked_by   BIGINT,
  linked_at   DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_work_record_daily CASCADE;
CREATE TABLE t_work_record_daily (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_record_id BIGINT NOT NULL,
  work_date      DATE NOT NULL,
  start_time     TIME,
  end_time       TIME,
  break_minutes  INT NOT NULL DEFAULT 0,
  worked_hours   DECIMAL(6,2) NOT NULL,
  remarks        VARCHAR(200),
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_wr_daily (work_record_id, work_date)
);

DROP TABLE IF EXISTS t_contract_price_history CASCADE;
CREATE TABLE t_contract_price_history (
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

DROP TABLE IF EXISTS t_resume_ingestion;
CREATE TABLE t_resume_ingestion (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  original_file_name    VARCHAR(255) NOT NULL,
  stored_file_name      VARCHAR(120) NOT NULL,
  file_ext              VARCHAR(10)  NOT NULL,
  status                VARCHAR(20)  NOT NULL DEFAULT '取込待ち',
  extracted_text        LONGTEXT,
  parsed_json           LONGTEXT,
  ai_provider           VARCHAR(30),
  ai_model              VARCHAR(60),
  error_message         VARCHAR(500),
  converted_engineer_id BIGINT,
  candidate_id          BIGINT,
  review_note           VARCHAR(500),
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  created_by            BIGINT
);

DROP TABLE IF EXISTS t_project_ingestion;
CREATE TABLE t_project_ingestion (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  source_type         VARCHAR(10) NOT NULL,
  original_file_name  VARCHAR(255),
  stored_file_name    VARCHAR(120),
  raw_text            LONGTEXT,
  status              VARCHAR(20) NOT NULL DEFAULT '取込待ち',
  parsed_json         LONGTEXT,
  ai_provider         VARCHAR(30),
  ai_model            VARCHAR(60),
  error_message       VARCHAR(500),
  converted_project_id BIGINT,
  review_note         VARCHAR(500),
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag        TINYINT NOT NULL DEFAULT 0,
  created_by          BIGINT
);

DROP TABLE IF EXISTS t_bp_availability;
CREATE TABLE t_bp_availability (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  initial_name       VARCHAR(50),
  bp_company         VARCHAR(120),
  bp_company_id      BIGINT,
  skills_json        LONGTEXT,
  unit_price         BIGINT,
  available_from     DATE,
  experience_years   INT,
  status             VARCHAR(20) NOT NULL DEFAULT '提案可能',
  promoted_engineer_id BIGINT,
  remarks            VARCHAR(500),
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag       TINYINT NOT NULL DEFAULT 0,
  created_by         BIGINT
);

DROP TABLE IF EXISTS t_bp_availability_ingestion;
CREATE TABLE t_bp_availability_ingestion (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  original_file_name    VARCHAR(255),
  stored_file_name      VARCHAR(120),
  file_ext              VARCHAR(10)  NOT NULL,
  status                VARCHAR(20)  NOT NULL DEFAULT '取込待ち',
  extracted_text        LONGTEXT,
  parsed_json           LONGTEXT,
  ai_provider           VARCHAR(30),
  ai_model              VARCHAR(60),
  error_message         VARCHAR(500),
  converted_availability_id BIGINT,
  review_note           VARCHAR(500),
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  created_by            BIGINT
);

DROP TABLE IF EXISTS m_bp_company CASCADE;
CREATE TABLE m_bp_company (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    legal_name VARCHAR(255) NOT NULL,
    name_kana VARCHAR(255),
    normalized_name VARCHAR(255),
    entity_type VARCHAR(50) NOT NULL,
    corporate_number VARCHAR(13),
    invoice_registration_number VARCHAR(14),
    capital_band VARCHAR(50),
    employee_band VARCHAR(50),
    address VARCHAR(500),
    representative VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    suspension_reason VARCHAR(500),
    suspension_start_date DATE,
    suspension_end_date DATE,
    suspension_approved_by BIGINT,
    rating INT DEFAULT 0,
    primary_sales_user_id BIGINT,
    compliance_applicability VARCHAR(50),
    applicability_checked_by BIGINT,
    applicability_checked_at DATETIME,
    applicability_note TEXT,
    version INT NOT NULL DEFAULT 1,
    created_by BIGINT,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, normalized_name)
);

DROP TABLE IF EXISTS t_bp_contact CASCADE;
CREATE TABLE t_bp_contact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    bp_company_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    department VARCHAR(100),
    role VARCHAR(100),
    email VARCHAR(255),
    phone VARCHAR(50),
    primary_flag INT NOT NULL DEFAULT 0,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_bp_bank_account CASCADE;
CREATE TABLE t_bp_bank_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    bp_company_id BIGINT NOT NULL,
    bank_name VARCHAR(100),
    branch_name VARCHAR(100),
    account_type VARCHAR(20) DEFAULT 'ORDINARY',
    encrypted_account_number VARCHAR(500),
    account_holder VARCHAR(100),
    masked_label VARCHAR(100) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    approval_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    approved_by BIGINT,
    approved_at DATETIME,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_bp_terms CASCADE;
CREATE TABLE t_bp_terms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    bp_company_id BIGINT NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    closing_day INT NOT NULL DEFAULT 31,
    payment_month_offset INT NOT NULL DEFAULT 1,
    payment_day INT NOT NULL DEFAULT 30,
    fee_bearer VARCHAR(20) NOT NULL DEFAULT 'PAYEE',
    payment_method VARCHAR(50) NOT NULL DEFAULT 'BANK_TRANSFER',
    fee_bearer_exception_reason VARCHAR(500),
    fee_bearer_approved_by BIGINT,
    fee_bearer_approved_at DATETIME,
    max_payment_days INT NOT NULL DEFAULT 60,
    version INT NOT NULL DEFAULT 1,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_engineer_bp_affiliation CASCADE;
CREATE TABLE t_engineer_bp_affiliation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    engineer_id BIGINT NOT NULL,
    bp_company_id BIGINT NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_bp_evaluation CASCADE;
CREATE TABLE t_bp_evaluation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    bp_company_id BIGINT NOT NULL,
    period VARCHAR(20) NOT NULL,
    quality_score INT DEFAULT 0,
    response_score INT DEFAULT 0,
    retention_score INT DEFAULT 0,
    compliance_score INT DEFAULT 0,
    billing_accuracy_score INT DEFAULT 0,
    comment TEXT,
    evaluated_by BIGINT,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_bp_price_negotiation CASCADE;
CREATE TABLE t_bp_price_negotiation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    bp_company_id BIGINT NOT NULL,
    requested_at DATE NOT NULL,
    responded_at DATE,
    status VARCHAR(50) NOT NULL DEFAULT 'REQUESTED',
    requested_amount DECIMAL(15, 2),
    agreed_amount DECIMAL(15, 2),
    summary TEXT,
    document_id BIGINT,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_bank_deposit;
CREATE TABLE t_bank_deposit (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  freee_deposit_id    VARCHAR(64) NOT NULL,
  deposit_date        DATE NOT NULL,
  amount              DECIMAL(12,0) NOT NULL,
  payer_name          VARCHAR(200),
  status              VARCHAR(10) NOT NULL DEFAULT '未消込',
  matched_invoice_id  BIGINT,
  matched_payment_id  BIGINT,
  remarks             VARCHAR(300),
  created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (freee_deposit_id)
);

DROP TABLE IF EXISTS shedlock;
CREATE TABLE shedlock (
  name       VARCHAR(64)  NOT NULL,
  lock_until TIMESTAMP(3) NOT NULL,
  locked_at  TIMESTAMP(3) NOT NULL,
  locked_by  VARCHAR(255) NOT NULL,
  PRIMARY KEY (name)
);

DROP TABLE IF EXISTS t_engineer_accounting_history CASCADE;
DROP TABLE IF EXISTS t_organization_relation_history CASCADE;
DROP TABLE IF EXISTS t_monthly_accounting_dimension CASCADE;
DROP TABLE IF EXISTS t_management_budget CASCADE;
DROP TABLE IF EXISTS t_user_organization CASCADE;
DROP TABLE IF EXISTS m_cost_center CASCADE;
DROP TABLE IF EXISTS m_organization_unit CASCADE;

CREATE TABLE t_organization_relation_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  parent_id BIGINT,
  status VARCHAR(20) NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE t_engineer_accounting_history (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  organization_id BIGINT,
  organization_history_status VARCHAR(20) NOT NULL DEFAULT 'KNOWN',
  cost_center_id BIGINT,
  expected_unit_price DECIMAL(12,0),
  valid_from DATE NOT NULL,
  valid_to DATE,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE m_organization_unit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT,
  legal_entity_id BIGINT,
  code VARCHAR(50) NOT NULL,
  name VARCHAR(200) NOT NULL,
  type VARCHAR(20) NOT NULL,
  parent_id BIGINT,
  valid_from DATE NOT NULL,
  valid_to DATE,
  status VARCHAR(20) NOT NULL DEFAULT '有効',
  merged_into BIGINT,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  legal_entity_key BIGINT AS (COALESCE(legal_entity_id, 0)),
  UNIQUE (legal_entity_key, code, valid_from)
);

CREATE TABLE t_user_organization (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  organization_id BIGINT NOT NULL,
  position_name VARCHAR(100),
  manager_user_id BIGINT,
  primary_flag TINYINT NOT NULL DEFAULT 0,
  valid_from DATE NOT NULL,
  valid_to DATE,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  active_primary_user_id BIGINT AS (CASE WHEN primary_flag = 1 AND valid_to IS NULL AND deleted_flag = 0
                                         THEN user_id ELSE NULL END),
  UNIQUE (active_primary_user_id),
  UNIQUE (user_id, organization_id, valid_from)
);

CREATE TABLE m_cost_center (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  legal_entity_id BIGINT,
  code VARCHAR(50) NOT NULL,
  name VARCHAR(200) NOT NULL,
  organization_id BIGINT,
  valid_from DATE NOT NULL,
  valid_to DATE,
  status VARCHAR(20) NOT NULL DEFAULT '有効',
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0
);

CREATE TABLE t_management_budget (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  cost_center_id BIGINT,
  budget_month DATE NOT NULL,
  revenue DECIMAL(15,0) NOT NULL DEFAULT 0,
  gross_profit DECIMAL(15,0) NOT NULL DEFAULT 0,
  utilization_count INT NOT NULL DEFAULT 0,
  hire_count INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  cost_center_key BIGINT AS (COALESCE(cost_center_id, 0)),
  UNIQUE (organization_id, cost_center_key, budget_month)
);

CREATE TABLE t_monthly_accounting_dimension (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_month DATE NOT NULL,
  source_type VARCHAR(50) NOT NULL,
  source_id BIGINT NOT NULL,
  organization_id BIGINT,
  cost_center_id BIGINT,
  sales_user_id BIGINT,
  revenue DECIMAL(15,0) NOT NULL DEFAULT 0,
  cost DECIMAL(15,0) NOT NULL DEFAULT 0,
  snapshot_at DATETIME NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_break_glass_incident CASCADE;
CREATE TABLE t_break_glass_incident (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  incident_key VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  reason VARCHAR(500) NOT NULL,
  idp_outage_confirmed TINYINT NOT NULL DEFAULT 0,
  correlation_id VARCHAR(100) NOT NULL,
  allowed_actions VARCHAR(1000) NOT NULL DEFAULT 'dashboard.view',
  requested_by BIGINT NOT NULL,
  approved_by_1 BIGINT,
  approved_at_1 DATETIME,
  approved_by_2 BIGINT,
  approved_at_2 DATETIME,
  enabled_from DATETIME,
  enabled_until DATETIME NOT NULL,
  closed_by BIGINT,
  closed_at DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE (tenant_id, incident_key)
);

DROP TABLE IF EXISTS t_mfa_attempt_guard CASCADE;
CREATE TABLE t_mfa_attempt_guard (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  user_id BIGINT NOT NULL,
  session_key_hash VARCHAR(128),
  source_hash VARCHAR(128),
  failed_count INT NOT NULL DEFAULT 0,
  window_started_at DATETIME,
  locked_until DATETIME,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE (user_id)
);

DROP TABLE IF EXISTS t_persistent_session CASCADE;
CREATE TABLE t_persistent_session (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  session_id VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  auth_type VARCHAR(20) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  revoked_at DATETIME,
  revoked_by BIGINT,
  revoke_reason VARCHAR(200),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  expires_at DATETIME NOT NULL,
  UNIQUE (session_id)
);

-- 承認責任者entityのMyBatis生成SELECTが孤立schemaでも失敗しないよう最終形を再現する。
CREATE TABLE IF NOT EXISTS t_approval_responsibility (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id BIGINT NOT NULL DEFAULT 1,
  responsibility_type VARCHAR(30) NOT NULL,
  organization_id BIGINT,
  user_id BIGINT NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE,
  active_flag TINYINT NOT NULL DEFAULT 1,
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0
);
