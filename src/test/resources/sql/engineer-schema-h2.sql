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
  delivery_preference VARCHAR(20) DEFAULT 'PDF',
  remarks           TEXT,
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag      TINYINT DEFAULT 0,
  version           INT NOT NULL DEFAULT 0
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
  phone               VARCHAR(50),
  prefecture          VARCHAR(50),
  railway_company     VARCHAR(150),
  employment_type     VARCHAR(20),
  status              VARCHAR(20),
  expected_unit_price DECIMAL(10,0),
  cost_center_id      BIGINT,
  organization_id     BIGINT,
  overtime_exempt_flag TINYINT,
  available_date      DATE,
  experience_years    INT,
  japanese_level      VARCHAR(20),
  resume_summary      TEXT,
  photo_url           VARCHAR(500),
  remarks             TEXT,
  created_by          BIGINT,
  created_at          DATETIME,
  updated_at          DATETIME,
  deleted_flag        TINYINT DEFAULT 0,
  version             INT NOT NULL DEFAULT 0
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
  acceptance_exemption_reason VARCHAR(500),
  created_by              BIGINT,
  created_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at              DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag            TINYINT DEFAULT 0,
  version                 INT NOT NULL DEFAULT 0,
  CONSTRAINT chk_contract_acceptance_exemption CHECK (acceptance_required = 1 OR (acceptance_exemption_reason IS NOT NULL AND TRIM(acceptance_exemption_reason) != ''))
);
-- R09-P2-04: 本番のuk_contract_order_line（1明細→1契約）をH2統合testでも検証できるようにする
CREATE UNIQUE INDEX IF NOT EXISTS uk_contract_order_line ON t_contract(order_line_id);

CREATE TABLE IF NOT EXISTS t_document_hash_claim (
  tenant_id     VARCHAR(100) NOT NULL,
  document_type VARCHAR(50)  NOT NULL,
  sha256        VARCHAR(64)  NOT NULL,
  document_id   BIGINT       NOT NULL,
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, document_type, sha256)
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_sales_order_quotation ON t_sales_order(quotation_id);
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
  version        INT NOT NULL DEFAULT 0,
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
  received_confirmed_at DATETIME,
  payment_expected_date DATE,
  portal_inquiry VARCHAR(1000),
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
  received_confirmed_at DATETIME,
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

-- m_menu: H2 DDL is not rolled back; keep shared table. Re-seed keys below.
DELETE FROM t_role_menu WHERE menu_id IN (SELECT id FROM m_menu WHERE menu_key IN ('dashboard','engineer','customer','project','proposal','contract','ai','ai-evaluation','email','user','compliance-gate','lifecycle','myLifecycle'));
DELETE FROM m_menu WHERE menu_key IN ('dashboard','engineer','customer','project','proposal','contract','ai','ai-evaluation','email','user','compliance-gate','lifecycle','myLifecycle');
-- @Sqlで共有H2 schemaを再構築した後も、画面レンダリングに必要な管理者メニューを保持する。
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) VALUES
  ('dashboard', 'ダッシュボード', '/dashboard', '/api/dashboard', 1),
  ('engineer',  '要員管理',       '/engineer',  '/api/engineers', 2),
  ('customer',  '顧客管理',       '/customer',  '/api/customers', 3),
  ('project',   '案件管理',       '/project',   '/api/projects', 4),
  ('proposal',  '提案管理',       '/proposal',  '/api/proposals', 5),
  ('contract',  '契約管理',       '/contract',  '/api/contracts', 6),
  ('ai',        'AI機能',         '/ai',        '/api/ai', 7),
  ('ai-evaluation', 'AI評価',     '/ai/evaluation', '/api/ai/evaluations', 73),
  ('email',     'メールテンプレート', '/email/template', '/api/email-templates', 8),
  ('user',      'ユーザー管理',   '/user',       '/api/users', 9),
  ('compliance-gate', '派遣コンプライアンスG2', '/compliance-gate', '/api/compliance-gate', 73),
  ('lifecycle', 'ライフサイクル管理', '/lifecycle', '/api/lifecycle', 25),
  ('myLifecycle', 'マイライフサイクル', '/my/lifecycle', '/api/my/lifecycle', 26);

-- t_role_menu: H2 DDL is not rolled back; keep shared table. Idempotent seed only.
INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', m.id FROM m_menu m
WHERE m.menu_key IN ('dashboard','engineer','customer','project','proposal','contract','ai','ai-evaluation','email','user','compliance-gate','lifecycle','myLifecycle')
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = '管理者' AND rm.menu_id = m.id);
INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM m_menu m
CROSS JOIN (SELECT '営業' AS role UNION ALL SELECT 'HR' UNION ALL SELECT 'マネージャー') r
WHERE m.menu_key IN ('dashboard','engineer','customer','project','proposal','contract','ai','ai-evaluation','email','user','compliance-gate','lifecycle')
  AND m.menu_key <> 'user' AND m.menu_key <> 'ai-evaluation'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = m.id);
INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM m_menu m
CROSS JOIN (SELECT '営業' AS role UNION ALL SELECT 'マネージャー') r
WHERE m.menu_key = 'ai-evaluation'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = m.id);
INSERT INTO t_role_menu (role, menu_id)
SELECT '要員', m.id FROM m_menu m
WHERE m.menu_key = 'myLifecycle'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = '要員' AND rm.menu_id = m.id);

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
  reference_type VARCHAR(64),
  reference_id BIGINT,
  actor_type VARCHAR(32),
  confirmation_source VARCHAR(32),
  human_user_id BIGINT,
  before_state VARCHAR(255),
  after_state VARCHAR(255),
  correlation_id VARCHAR(128),
  idempotency_key VARCHAR(128),
  CONSTRAINT ck_engineer_audit_actor_type CHECK (actor_type IS NULL OR actor_type IN ('HUMAN', 'SYSTEM', 'PROVIDER', 'LEGACY_UNRESOLVED')),
  CONSTRAINT ck_engineer_audit_confirmation_source CHECK (confirmation_source IS NULL OR confirmation_source IN ('MANUAL_API', 'SCHEDULER_POLL', 'PROVIDER_SYNC', 'PROVIDER_CALLBACK', 'LEGACY_UNRESOLVED')),
  CONSTRAINT ck_engineer_audit_actor_pair CHECK (
    actor_type IS NULL AND confirmation_source IS NULL AND human_user_id IS NULL
    OR actor_type IS NOT NULL AND confirmation_source IS NOT NULL AND (
      actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0
      OR actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL
      OR actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL
      OR actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL
    )
  ),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_engineer_audit_reference ON t_audit_log(reference_type, reference_id);

DROP TABLE IF EXISTS t_freee_connection CASCADE;
CREATE TABLE t_freee_connection (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, company_id BIGINT, company_name VARCHAR(200),
 access_token_encrypted TEXT NOT NULL, refresh_token_encrypted TEXT, token_expires_at DATETIME,
 connected_by BIGINT, connection_status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED',
 created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
 deleted_flag TINYINT DEFAULT 0
);
DROP TABLE IF EXISTS t_freee_employee_link CASCADE;
CREATE TABLE t_freee_employee_link (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, engineer_id BIGINT NOT NULL, freee_employee_id VARCHAR(100) NOT NULL,
 freee_company_id BIGINT, confirmed_at DATETIME, confirmed_by BIGINT,
 created_at DATETIME DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
 deleted_flag TINYINT DEFAULT 0, UNIQUE(engineer_id), UNIQUE(freee_company_id, freee_employee_id)
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

DROP TABLE IF EXISTS t_contract_acceptance_backfill CASCADE;
CREATE TABLE t_contract_acceptance_backfill (
  contract_id   BIGINT PRIMARY KEY,
  backfilled_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- R09-P1-05: 孤児 order_line_id の拒否（t_sales_order_line 作成後にFKを追加）
ALTER TABLE t_contract ADD CONSTRAINT IF NOT EXISTS fk_contract_order_line
  FOREIGN KEY (order_line_id) REFERENCES t_sales_order_line(id);

-- S11/T068: engineer-schema-h2でも雇用勤怠entityのSELECT列を再現する。
DROP TABLE IF EXISTS t_leave_ledger CASCADE;
DROP TABLE IF EXISTS t_overtime_followup CASCADE;
DROP TABLE IF EXISTS m_overtime_agreement CASCADE;
DROP TABLE IF EXISTS t_leave_request CASCADE;
DROP TABLE IF EXISTS t_attendance_month CASCADE;
DROP TABLE IF EXISTS t_employee_attendance_break CASCADE;
DROP TABLE IF EXISTS t_employee_attendance CASCADE;
DROP TABLE IF EXISTS m_work_calendar_day CASCADE;
DROP TABLE IF EXISTS m_work_calendar CASCADE;

CREATE TABLE m_work_calendar (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  legal_entity_id BIGINT,
  organization_id BIGINT,
  engineer_id BIGINT,
  name VARCHAR(200) NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE,
  status VARCHAR(20) NOT NULL DEFAULT '有効',
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_work_calendar_period CHECK (valid_to IS NULL OR valid_from <= valid_to)
);
CREATE TABLE m_work_calendar_day (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  calendar_id BIGINT NOT NULL,
  calendar_date DATE NOT NULL,
  day_type VARCHAR(30) NOT NULL,
  scheduled_minutes INT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_work_calendar_day UNIQUE (calendar_id, calendar_date),
  CONSTRAINT chk_work_calendar_day_minutes CHECK (scheduled_minutes IS NULL OR scheduled_minutes >= 0)
);
CREATE TABLE t_employee_attendance (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  legal_entity_id BIGINT,
  organization_id BIGINT,
  work_calendar_id BIGINT,
  work_date DATE NOT NULL,
  clock_in TIME,
  clock_out TIME,
  break_minutes INT NOT NULL DEFAULT 0,
  regular_minutes INT NOT NULL DEFAULT 0,
  overtime_minutes INT NOT NULL DEFAULT 0,
  holiday_minutes INT NOT NULL DEFAULT 0,
  late_night_minutes INT NOT NULL DEFAULT 0,
  work_type VARCHAR(30) NOT NULL DEFAULT '通常',
  workplace_type VARCHAR(30),
  source VARCHAR(20) NOT NULL DEFAULT 'manual',
  source_external_id VARCHAR(200),
  status VARCHAR(20) NOT NULL DEFAULT '入力中',
  remarks VARCHAR(500),
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_employee_attendance_source UNIQUE (source, source_external_id),
  CONSTRAINT chk_employee_attendance_source CHECK (
    (source IN ('manual', 'system') AND source_external_id IS NULL)
    OR (source IN ('freee', 'import') AND source_external_id IS NOT NULL)
  ),
  CONSTRAINT chk_employee_attendance_minutes CHECK (
    break_minutes >= 0 AND regular_minutes >= 0 AND overtime_minutes >= 0
    AND holiday_minutes >= 0 AND late_night_minutes >= 0
  )
);
CREATE TABLE t_employee_attendance_break (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  attendance_id BIGINT NOT NULL,
  sequence_no INT NOT NULL,
  start_offset_minutes INT NOT NULL,
  end_offset_minutes INT NOT NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_employee_attendance_break UNIQUE (attendance_id, sequence_no),
  CONSTRAINT chk_employee_attendance_break_offset CHECK (
    start_offset_minutes >= 0 AND end_offset_minutes > start_offset_minutes
  )
);
CREATE TABLE t_attendance_month (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  legal_entity_id BIGINT,
  organization_id BIGINT,
  work_month DATE NOT NULL,
  scheduled_minutes INT NOT NULL DEFAULT 0,
  worked_minutes INT NOT NULL DEFAULT 0,
  regular_minutes INT NOT NULL DEFAULT 0,
  overtime_minutes INT NOT NULL DEFAULT 0,
  holiday_minutes INT NOT NULL DEFAULT 0,
  late_night_minutes INT NOT NULL DEFAULT 0,
  leave_minutes INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT '入力中',
  submitted_at DATETIME,
  submitted_by BIGINT,
  approved_at DATETIME,
  approved_by BIGINT,
  closed_at DATETIME,
  closed_by BIGINT,
  close_reason VARCHAR(500),
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_attendance_month_engineer UNIQUE (engineer_id, work_month),
  CONSTRAINT chk_attendance_month_month_start CHECK (DAYOFMONTH(work_month) = 1),
  CONSTRAINT chk_attendance_month_minutes CHECK (
    scheduled_minutes >= 0 AND worked_minutes >= 0 AND regular_minutes >= 0
    AND overtime_minutes >= 0 AND holiday_minutes >= 0 AND late_night_minutes >= 0
    AND leave_minutes >= 0
  )
);
CREATE TABLE t_leave_request (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  legal_entity_id BIGINT,
  organization_id BIGINT,
  leave_type VARCHAR(30) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  start_time TIME,
  end_time TIME,
  requested_minutes INT NOT NULL,
  reason VARCHAR(500),
  status VARCHAR(20) NOT NULL DEFAULT '申請中',
  approval_request_id BIGINT,
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_leave_request_period CHECK (start_date <= end_date),
  CONSTRAINT chk_leave_request_minutes CHECK (requested_minutes > 0)
);
CREATE TABLE t_leave_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  legal_entity_id BIGINT,
  leave_type VARCHAR(30) NOT NULL,
  ledger_type VARCHAR(20) NOT NULL,
  amount_minutes INT NOT NULL,
  entry_date DATE NOT NULL,
  leave_request_id BIGINT,
  source VARCHAR(20) NOT NULL DEFAULT 'manual',
  source_external_id VARCHAR(200),
  remarks VARCHAR(500),
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_leave_ledger_source UNIQUE (source, source_external_id),
  CONSTRAINT chk_leave_ledger_type CHECK (ledger_type IN ('GRANT', 'CONSUME')),
  CONSTRAINT chk_leave_ledger_amount CHECK (amount_minutes > 0),
  CONSTRAINT chk_leave_ledger_source CHECK (
    (source IN ('manual', 'system') AND source_external_id IS NULL)
    OR (source = 'import' AND source_external_id IS NOT NULL)
  )
);
CREATE TABLE m_overtime_agreement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  legal_entity_id BIGINT NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE,
  special_clause TINYINT NOT NULL DEFAULT 0,
  normal_month_limit_minutes INT,
  normal_year_limit_minutes INT,
  special_year_limit_minutes INT,
  total_month_limit_minutes INT,
  multi_month_average_limit_minutes INT,
  exceed_month_count_limit INT,
  warning_threshold_percent INT,
  warning_recipients VARCHAR(100),
  config_json TEXT,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_overtime_agreement_period UNIQUE (legal_entity_id, valid_from),
  CONSTRAINT chk_overtime_agreement_month_start CHECK (DAYOFMONTH(valid_from) = 1),
  CONSTRAINT chk_overtime_agreement_period CHECK (valid_to IS NULL OR valid_from <= valid_to),
  CONSTRAINT chk_overtime_agreement_limits CHECK (
    (normal_month_limit_minutes IS NULL OR normal_month_limit_minutes >= 0)
    AND (normal_year_limit_minutes IS NULL OR normal_year_limit_minutes >= 0)
    AND (special_year_limit_minutes IS NULL OR special_year_limit_minutes >= 0)
    AND (total_month_limit_minutes IS NULL OR total_month_limit_minutes >= 0)
    AND (multi_month_average_limit_minutes IS NULL OR multi_month_average_limit_minutes >= 0)
    AND (exceed_month_count_limit IS NULL OR exceed_month_count_limit >= 0)
    AND (warning_threshold_percent IS NULL OR warning_threshold_percent BETWEEN 0 AND 100)
  )
);
CREATE TABLE t_overtime_followup (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  warning_code VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT '未対応',
  notified_at DATETIME,
  health_action_status VARCHAR(30),
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_overtime_followup UNIQUE (engineer_id, period_month, warning_code),
  CONSTRAINT chk_overtime_followup_month_start CHECK (DAYOFMONTH(period_month) = 1)
);
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('overtime.limit.month-normal', '2700', '月時間外上限（分）'),
  ('overtime.limit.year-normal', '21600', '年時間外上限（分）'),
  ('overtime.limit.year-special', '43200', '特別条項年時間外上限（分）'),
  ('overtime.limit.month-total', '6000', '月合計上限（分）'),
  ('overtime.limit.multi-month-average', '4800', '複数月平均上限（分）'),
  ('overtime.limit.exceed-month-count', '6', '45時間超過月数上限（回）'),
  ('overtime.prorate-partial-month', 'false', '月中入社・退職の按分有無'),
  ('overtime.warning.threshold-percent', '80', '予兆警告閾値（%）'),
  ('overtime.warning.recipients', 'self,manager,hr', '時間外警告の通知先');

-- ============================================================
-- 派遣・準委任コンプライアンス台帳（T061 F1）
-- application-test.ymlの専用schemaと同じ列shapeを再現する。
-- ============================================================
-- ============================================================
-- 派遣・準委任コンプライアンス台豌 (T061 F1 R5)
-- application-test.yml no specialized schema (schema-dispatch-compliance-h2.sql) to same column shape.
-- ============================================================
-- ============================================================
-- 派遣・準委任コンプライアンス台豌 (T061 F1 R5)
-- application-test.yml no specialized schema (schema-dispatch-compliance-h2.sql) to same column shape.
-- ============================================================
-- ============================================================
-- 派遣・準委任コンプライアンス台豌 (T061 F1 R5)
-- application-test.yml no specialized schema (schema-dispatch-compliance-h2.sql) to same column shape.
-- ============================================================
DROP TABLE IF EXISTS t_document_delivery CASCADE;
DROP TABLE IF EXISTS t_compliance_finding CASCADE;
DROP TABLE IF EXISTS t_ledger_work_snapshot CASCADE;
DROP TABLE IF EXISTS t_notification_difference_history CASCADE;
DROP TABLE IF EXISTS t_direct_hire_dispute_history CASCADE;
DROP TABLE IF EXISTS t_planned_introduction_history CASCADE;
DROP TABLE IF EXISTS t_planned_introduction_terms CASCADE;
DROP TABLE IF EXISTS t_career_consulting_history CASCADE;
DROP TABLE IF EXISTS t_training_history CASCADE;
DROP TABLE IF EXISTS t_employment_stability_history CASCADE;
DROP TABLE IF EXISTS t_compliance_complaint_history CASCADE;
DROP TABLE IF EXISTS t_compliance_work_calendar CASCADE;
DROP TABLE IF EXISTS t_compliance_snapshot_operation CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_worker_state CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_worker_snapshot CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_profile CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_snapshot CASCADE;
DROP TABLE IF EXISTS m_workplace CASCADE;
-- ============================================================
-- 派遣・準委任コンプライアンス台豌 (T061 F1 R5)
-- application-test.yml no specialized schema (schema-dispatch-compliance-h2.sql) to same column shape.
-- ============================================================
DROP TABLE IF EXISTS t_document_delivery CASCADE;
DROP TABLE IF EXISTS t_compliance_finding CASCADE;
DROP TABLE IF EXISTS t_ledger_work_snapshot CASCADE;
DROP TABLE IF EXISTS t_notification_difference_history CASCADE;
DROP TABLE IF EXISTS t_direct_hire_dispute_history CASCADE;
DROP TABLE IF EXISTS t_planned_introduction_history CASCADE;
DROP TABLE IF EXISTS t_planned_introduction_terms CASCADE;
DROP TABLE IF EXISTS t_career_consulting_history CASCADE;
DROP TABLE IF EXISTS t_training_history CASCADE;
DROP TABLE IF EXISTS t_employment_stability_history CASCADE;
DROP TABLE IF EXISTS t_compliance_complaint_history CASCADE;
DROP TABLE IF EXISTS t_compliance_break_detail CASCADE;
DROP TABLE IF EXISTS t_compliance_work_calendar CASCADE;
DROP TABLE IF EXISTS t_compliance_snapshot_operation CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_worker_state CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_worker_snapshot CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_profile CASCADE;
DROP TABLE IF EXISTS t_contract_compliance_snapshot CASCADE;
DROP TABLE IF EXISTS m_workplace CASCADE;
CREATE TABLE IF NOT EXISTS m_workplace (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         VARCHAR(100) NOT NULL DEFAULT 'default',
  customer_id       BIGINT NOT NULL,
  organization_id   BIGINT,
  name              VARCHAR(200) NOT NULL,
  address           VARCHAR(500),
  organization_unit VARCHAR(200),
  phone             VARCHAR(50),
  valid_from        DATE NOT NULL DEFAULT '1970-01-01',
  valid_to          DATE,
  status            VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  version           INT NOT NULL DEFAULT 0,
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag      TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_workplace_period UNIQUE (customer_id, name, valid_from),
  CONSTRAINT uk_workplace_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_workplace_period CHECK (valid_to IS NULL OR valid_from <= valid_to)
);
CREATE INDEX IF NOT EXISTS idx_workplace_scope ON m_workplace(tenant_id, customer_id, organization_id);
CREATE INDEX IF NOT EXISTS idx_workplace_period ON m_workplace(valid_from, valid_to);

CREATE TABLE IF NOT EXISTS t_contract_compliance_snapshot (
  id                                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                         VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id                       BIGINT NOT NULL,
  snapshot_version                  INT NOT NULL,
  snapshot_hash                     VARCHAR(64) NOT NULL,
  operation_id                      VARCHAR(64),
  snapshot_at                       DATETIME,
  contract_no                       VARCHAR(100),
  contract_date                     DATE,
  party_name                        VARCHAR(200),
  party_address                     VARCHAR(500),
  party_representative              VARCHAR(200),
  dispatch_from                     DATE,
  dispatch_to                       DATE,
  workplace_name                    VARCHAR(200),
  workplace_address                 VARCHAR(500),
  workplace_department              VARCHAR(200),
  workplace_phone                   VARCHAR(50),
  organization_unit                 VARCHAR(200),
  organization_head_title           VARCHAR(100),
  work_description                  CLOB,
  statutory_job_flag                TINYINT,
  statutory_job_reference           VARCHAR(200),
  responsibility_level              VARCHAR(50),
  responsibility_detail             CLOB,
  command_person_department         VARCHAR(100),
  command_person_title              VARCHAR(100),
  command_person_name               VARCHAR(100),
  command_person_phone              VARCHAR(50),
  client_responsible_department     VARCHAR(100),
  client_responsible_title          VARCHAR(100),
  client_responsible_name           VARCHAR(100),
  client_responsible_phone          VARCHAR(50),
  dispatch_responsible_department   VARCHAR(100),
  dispatch_responsible_title        VARCHAR(100),
  dispatch_responsible_name         VARCHAR(100),
  dispatch_responsible_phone        VARCHAR(50),
  work_start_minute                 INT,
  work_end_minute                   INT,
  work_span_next_day_flag           TINYINT,
  break_start_minute                INT,
  break_end_minute                  INT,
  work_day_code                     VARCHAR(30),
  holiday_calendar_code             VARCHAR(30),
  agreement_reference_id            BIGINT,
  overtime_daily_limit              INT,
  overtime_monthly_limit            INT,
  overtime_yearly_limit             INT,
  overtime_period_from              DATE,
  overtime_period_to                DATE,
  workplace_limitation_date         DATE,
  organization_limitation_date      DATE,
  safety_responsibility_detail      CLOB,
  safety_rule_reference             VARCHAR(200),
  benefits_detail                   CLOB,
  benefits_provided_flag            TINYINT,
  dispatch_headcount                INT,
  agreement_target_flag             TINYINT,
  treatment_scheme                  VARCHAR(100),
  source_complaint_contact_department VARCHAR(100),
  source_complaint_contact_title    VARCHAR(100),
  source_complaint_contact_name     VARCHAR(100),
  source_complaint_contact_phone    VARCHAR(50),
  client_complaint_contact_department VARCHAR(100),
  client_complaint_contact_title    VARCHAR(100),
  client_complaint_contact_name     VARCHAR(100),
  client_complaint_contact_phone    VARCHAR(50),
  employment_stability_preference   CLOB,
  limitation_exemption_type         VARCHAR(50),
  limitation_exemption_detail       CLOB,
  limitation_exemption_basis        VARCHAR(200),
  limitation_exemption_from         DATE,
  limitation_exemption_to           DATE,
  dispatch_fee_amount               DECIMAL(14,2),
  dispatch_fee_basis                VARCHAR(20),
  dispatch_fee_currency             VARCHAR(3) DEFAULT 'JPY',
  social_insurance_procedure_incomplete_reason CLOB,
  health_insurance_status           VARCHAR(20),
  health_insurance_missing_reason   VARCHAR(500),
  health_insurance_expected_date    DATE,
  pension_insurance_status          VARCHAR(20),
  pension_insurance_missing_reason  VARCHAR(500),
  pension_insurance_expected_date   DATE,
  employment_insurance_status       VARCHAR(20),
  employment_insurance_missing_reason VARCHAR(500),
  employment_insurance_expected_date DATE,
  instruction_route                 CLOB,
  subcontract_allowed               TINYINT,
  acceptance_method                 VARCHAR(255),
  retention_due_date                DATE,
  legal_hold_flag                   TINYINT,
  version                           INT NOT NULL DEFAULT 0,
  created_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                      TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_snapshot_version UNIQUE (contract_id, snapshot_version),
  CONSTRAINT uk_compliance_snapshot_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT fk_snapshot_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_compliance_snapshot_hash ON t_contract_compliance_snapshot(snapshot_hash);
CREATE INDEX IF NOT EXISTS idx_compliance_snapshot_contract ON t_contract_compliance_snapshot(contract_id);

CREATE TABLE IF NOT EXISTS t_contract_compliance_profile (
  id                                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                         VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id                       BIGINT NOT NULL,
  contract_type_detail              VARCHAR(50),
  workplace_id                      BIGINT,
  work_description                  CLOB,
  statutory_job_flag                TINYINT,
  statutory_job_reference           VARCHAR(200),
  responsibility_level              VARCHAR(50),
  responsibility_detail             CLOB,
  command_person_contact_id         BIGINT,
  command_person_department         VARCHAR(100),
  command_person_title              VARCHAR(100),
  command_person_name               VARCHAR(100),
  command_person_phone              VARCHAR(50),
  client_responsible_contact_id     BIGINT,
  client_responsible_department     VARCHAR(100),
  client_responsible_title          VARCHAR(100),
  client_responsible_name           VARCHAR(100),
  client_responsible_phone          VARCHAR(50),
  dispatch_responsible_user_id      BIGINT,
  dispatch_responsible_department   VARCHAR(100),
  dispatch_responsible_title        VARCHAR(100),
  dispatch_responsible_name         VARCHAR(100),
  dispatch_responsible_phone        VARCHAR(50),
  work_start_minute                 INT,
  work_end_minute                   INT,
  work_span_next_day_flag           TINYINT,
  break_start_minute                INT,
  break_end_minute                  INT,
  work_day_code                     VARCHAR(30),
  holiday_calendar_code             VARCHAR(30),
  agreement_reference_id            BIGINT,
  overtime_daily_limit              INT,
  overtime_monthly_limit            INT,
  overtime_yearly_limit             INT,
  overtime_period_from              DATE,
  overtime_period_to                DATE,
  workplace_limitation_date         DATE,
  organization_limitation_date      DATE,
  safety_responsibility_detail      CLOB,
  safety_rule_reference             VARCHAR(200),
  benefits_detail                   CLOB,
  benefits_provided_flag            TINYINT,
  dispatch_headcount                INT,
  agreement_target_flag             TINYINT,
  treatment_scheme                  VARCHAR(100),
  source_complaint_contact_department VARCHAR(100),
  source_complaint_contact_title    VARCHAR(100),
  source_complaint_contact_name     VARCHAR(100),
  source_complaint_contact_phone    VARCHAR(50),
  client_complaint_contact_department VARCHAR(100),
  client_complaint_contact_title    VARCHAR(100),
  client_complaint_contact_name     VARCHAR(100),
  client_complaint_contact_phone    VARCHAR(50),
  employment_stability_preference   CLOB,
  limitation_exemption_type         VARCHAR(50),
  limitation_exemption_detail       CLOB,
  limitation_exemption_basis        VARCHAR(200),
  limitation_exemption_from         DATE,
  limitation_exemption_to           DATE,
  dispatch_fee_amount               DECIMAL(14,2),
  dispatch_fee_basis                VARCHAR(20),
  dispatch_fee_currency             VARCHAR(3) DEFAULT 'JPY',
  social_insurance_procedure_incomplete_reason CLOB,
  health_insurance_status           VARCHAR(20),
  health_insurance_missing_reason   VARCHAR(500),
  health_insurance_expected_date    DATE,
  pension_insurance_status          VARCHAR(20),
  pension_insurance_missing_reason  VARCHAR(500),
  pension_insurance_expected_date   DATE,
  employment_insurance_status       VARCHAR(20),
  employment_insurance_missing_reason VARCHAR(500),
  employment_insurance_expected_date DATE,
  instruction_route                 CLOB,
  subcontract_allowed               TINYINT,
  acceptance_method                 VARCHAR(255),
  dispatch_period_start             DATE,
  dispatch_period_end               DATE,
  retention_due_date                DATE,
  legal_hold_flag                   TINYINT,
  current_snapshot_id               BIGINT,
  current_snapshot_version          INT,
  version                           INT NOT NULL DEFAULT 0,
  created_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                        DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                      TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_contract_compliance_profile_contract UNIQUE (contract_id),
  CONSTRAINT chk_profile_period CHECK (dispatch_period_end IS NULL OR dispatch_period_start IS NULL OR dispatch_period_start <= dispatch_period_end),
  CONSTRAINT chk_profile_dispatch_count CHECK (dispatch_headcount IS NULL OR dispatch_headcount >= 0),
  CONSTRAINT chk_profile_fee CHECK (dispatch_fee_amount IS NULL OR dispatch_fee_amount >= 0),
  CONSTRAINT fk_profile_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_profile_workplace FOREIGN KEY (workplace_id) REFERENCES m_workplace(id)
    ON DELETE SET NULL,
  CONSTRAINT fk_profile_current_snapshot FOREIGN KEY (current_snapshot_id) REFERENCES t_contract_compliance_snapshot(id)
    ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_profile_workplace ON t_contract_compliance_profile(workplace_id);
CREATE INDEX IF NOT EXISTS idx_profile_limitation ON t_contract_compliance_profile(workplace_limitation_date, organization_limitation_date);
CREATE INDEX IF NOT EXISTS idx_profile_dispatch_period ON t_contract_compliance_profile(dispatch_period_start, dispatch_period_end);

CREATE TABLE IF NOT EXISTS t_contract_compliance_worker_snapshot (
  id                            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                     VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id                   BIGINT NOT NULL,
  worker_id                     BIGINT NOT NULL,
  snapshot_version              INT NOT NULL,
  snapshot_hash                 VARCHAR(64) NOT NULL,
  operation_id                  VARCHAR(64),
  snapshot_at                   DATETIME,
  worker_name                   VARCHAR(100),
  employer_name                 VARCHAR(200),
  employer_address              VARCHAR(500),
  employer_title                VARCHAR(100),
  gender                        VARCHAR(20),
  age_band                      VARCHAR(30),
  age_at_reference_date         DATE,
  employment_term_type          VARCHAR(20),
  employment_from               DATE,
  employment_to                 DATE,
  indefinite_worker_flag        TINYINT,
  age_over_60_flag              TINYINT,
  worker_restriction_type       VARCHAR(30),
  health_insurance_status       VARCHAR(20),
  health_insurance_missing_reason VARCHAR(500),
  health_insurance_expected_date DATE,
  pension_insurance_status      VARCHAR(20),
  pension_insurance_missing_reason VARCHAR(500),
  pension_insurance_expected_date DATE,
  employment_insurance_status   VARCHAR(20),
  employment_insurance_missing_reason VARCHAR(500),
  employment_insurance_expected_date DATE,
  version                       INT NOT NULL DEFAULT 0,
  created_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                  TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_worker_snapshot_version UNIQUE (contract_id, worker_id, snapshot_version),
  CONSTRAINT uk_worker_snapshot_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT fk_worker_snapshot_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_worker_snapshot_hash ON t_contract_compliance_worker_snapshot(snapshot_hash);
CREATE INDEX IF NOT EXISTS idx_worker_snapshot_worker ON t_contract_compliance_worker_snapshot(worker_id);

CREATE TABLE IF NOT EXISTS t_contract_compliance_worker_state (
  id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                   VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id                 BIGINT NOT NULL,
  worker_id                   BIGINT NOT NULL,
  current_snapshot_id         BIGINT,
  current_snapshot_version    INT,
  version                     INT NOT NULL DEFAULT 0,
  created_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                  DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_worker_state_contract_worker UNIQUE (contract_id, worker_id),
  CONSTRAINT fk_worker_state_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_worker_state_snapshot FOREIGN KEY (current_snapshot_id) REFERENCES t_contract_compliance_worker_snapshot(id)
    ON DELETE SET NULL
);

CREATE TABLE IF NOT EXISTS t_compliance_snapshot_operation (
  id                            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                     VARCHAR(100) NOT NULL DEFAULT 'default',
  operation_id                  VARCHAR(64) NOT NULL,
  scope_type                    VARCHAR(20) NOT NULL,
  contract_id                   BIGINT NOT NULL,
  worker_id                     BIGINT,
  expected_version              INT,
  resulting_snapshot_id         BIGINT,
  resulting_worker_snapshot_id  BIGINT,
  request_hash                  VARCHAR(64),
  status                        VARCHAR(20) NOT NULL DEFAULT 'SUCCEEDED',
  version                       INT NOT NULL DEFAULT 0,
  created_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at                    DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag                  TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_snapshot_operation UNIQUE (operation_id),
  CONSTRAINT fk_snapshot_operation_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_snapshot_operation_contract ON t_compliance_snapshot_operation(contract_id, scope_type);

CREATE TABLE IF NOT EXISTS t_compliance_work_calendar (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  work_day_code         VARCHAR(30),
  holiday_calendar_code VARCHAR(30),
  excluded_date         DATE,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_work_calendar_event UNIQUE (event_id),
  CONSTRAINT fk_work_calendar_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_work_calendar_contract ON t_compliance_work_calendar(contract_id, effective_from);

CREATE TABLE IF NOT EXISTS t_compliance_break_detail (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  break_no              INT NOT NULL,
  start_offset_minute   INT NOT NULL,
  end_offset_minute     INT NOT NULL,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_break_detail_event UNIQUE (event_id),
  CONSTRAINT chk_break_detail_offset CHECK (start_offset_minute >= 0 AND end_offset_minute > start_offset_minute),
  CONSTRAINT fk_break_detail_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_break_detail_contract ON t_compliance_break_detail(contract_id, effective_from);

CREATE TABLE IF NOT EXISTS t_compliance_complaint_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  complaint_type        VARCHAR(20),
  received_at           DATE,
  content               CLOB,
  action                CLOB,
  resolution            CLOB,
  notified_at           DATE,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_complaint_event UNIQUE (event_id),
  CONSTRAINT fk_complaint_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_complaint_contract ON t_compliance_complaint_history(contract_id, received_at);

CREATE TABLE IF NOT EXISTS t_employment_stability_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  request_at            DATE,
  request_method        VARCHAR(100),
  response_at           DATE,
  response_content      CLOB,
  action                CLOB,
  outcome               VARCHAR(100),
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_employment_stability_event UNIQUE (event_id),
  CONSTRAINT fk_employment_stability_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_employment_stability_contract ON t_employment_stability_history(contract_id, request_at);

CREATE TABLE IF NOT EXISTS t_training_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  training_date         DATE,
  minutes               INT,
  content               CLOB,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_training_event UNIQUE (event_id),
  CONSTRAINT fk_training_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_training_contract ON t_training_history(contract_id, training_date);

CREATE TABLE IF NOT EXISTS t_career_consulting_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  consulting_date       DATE,
  content               CLOB,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_career_consulting_event UNIQUE (event_id),
  CONSTRAINT fk_career_consulting_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_career_consulting_contract ON t_career_consulting_history(contract_id, consulting_date);

CREATE TABLE IF NOT EXISTS t_planned_introduction_terms (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  contract_period_from  DATE,
  contract_period_to    DATE,
  renewal_rule          VARCHAR(100),
  renewal_limit         INT,
  work_change_scope     VARCHAR(500),
  trial_period          VARCHAR(200),
  wage_detail           VARCHAR(500),
  insurance_detail      VARCHAR(500),
  smoking_measure       VARCHAR(500),
  employer_name         VARCHAR(200),
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_planned_introduction_terms_event UNIQUE (event_id),
  CONSTRAINT fk_planned_terms_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_planned_terms_contract ON t_planned_introduction_terms(contract_id, effective_from);

CREATE TABLE IF NOT EXISTS t_planned_introduction_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  introduction_date     DATE,
  outcome               VARCHAR(30),
  reason                CLOB,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_planned_introduction_event UNIQUE (event_id),
  CONSTRAINT fk_planned_introduction_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_planned_introduction_contract ON t_planned_introduction_history(contract_id, introduction_date);

CREATE TABLE IF NOT EXISTS t_direct_hire_dispute_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  measure               VARCHAR(500),
  fee_detail            VARCHAR(500),
  request_method        VARCHAR(200),
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_direct_hire_dispute_event UNIQUE (event_id),
  CONSTRAINT fk_direct_hire_dispute_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_direct_hire_dispute_contract ON t_direct_hire_dispute_history(contract_id, effective_from);

CREATE TABLE IF NOT EXISTS t_notification_difference_history (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  difference_type       VARCHAR(50),
  contract_snapshot_id  BIGINT,
  notice_snapshot_id    BIGINT,
  difference_detail     CLOB,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_notification_difference_event UNIQUE (event_id),
  CONSTRAINT fk_notification_difference_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_notification_difference_contract ON t_notification_difference_history(contract_id, occurred_at);

CREATE TABLE IF NOT EXISTS t_ledger_work_snapshot (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  worker_id             BIGINT,
  event_id              VARCHAR(64) NOT NULL,
  event_type            VARCHAR(20) NOT NULL DEFAULT 'CREATED',
  supersedes_event_id   VARCHAR(64),
  correction_reason     VARCHAR(500),
  actor_user_id         BIGINT,
  occurred_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  effective_from        DATE,
  effective_to          DATE,
  work_month            DATE,
  work_days             INT,
  work_hours            INT,
  overtime_hours        INT,
  absence_days          INT,
  gross_amount          DECIMAL(14,2),
  closed_at             DATETIME,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_ledger_work_event UNIQUE (event_id),
  CONSTRAINT fk_ledger_work_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_ledger_work_contract ON t_ledger_work_snapshot(contract_id, work_month);

CREATE TABLE IF NOT EXISTS t_compliance_finding (
  id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id             VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id           BIGINT NOT NULL,
  code                  VARCHAR(80) NOT NULL,
  severity              VARCHAR(20) NOT NULL DEFAULT 'WARNING',
  status                VARCHAR(30) NOT NULL DEFAULT 'OPEN',
  condition_fingerprint VARCHAR(128) NOT NULL,
  detected_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  due_date              DATE,
  acknowledged_by      BIGINT,
  acknowledged_at       DATETIME,
  resolution_note       VARCHAR(2000),
  evidence_document_id  BIGINT,
  exception_expires_at  DATETIME,
  version               INT NOT NULL DEFAULT 0,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_compliance_finding UNIQUE (contract_id, code, condition_fingerprint),
  CONSTRAINT fk_finding_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS idx_finding_status_due ON t_compliance_finding(status, due_date);
CREATE INDEX IF NOT EXISTS idx_finding_contract ON t_compliance_finding(contract_id, detected_at);

CREATE TABLE IF NOT EXISTS t_document_delivery (
  id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                VARCHAR(100) NOT NULL DEFAULT 'default',
  contract_id              BIGINT,
  document_id              BIGINT NOT NULL,
  document_type            VARCHAR(50),
  template_version         VARCHAR(50),
  effective_from           DATE,
  effective_to             DATE,
  snapshot_hash            VARCHAR(64),
  recipient_contact_id     BIGINT,
  recipient_name_snapshot  VARCHAR(200),
  recipient_email_snapshot VARCHAR(255),
  delivery_method          VARCHAR(30) NOT NULL,
  delivery_status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  delivered_at             DATETIME,
  confirmed_at             DATETIME,
  confirmation_note        VARCHAR(1000),
  idempotency_key          VARCHAR(200),
  version                  INT NOT NULL DEFAULT 0,
  created_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag             TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_document_delivery_idempotency UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT fk_delivery_contract FOREIGN KEY (contract_id) REFERENCES t_contract(id)
    ON DELETE SET NULL
);
CREATE INDEX IF NOT EXISTS idx_delivery_document ON t_document_delivery(document_id, delivered_at);
CREATE INDEX IF NOT EXISTS idx_delivery_contract ON t_document_delivery(contract_id, delivered_at);
CREATE INDEX IF NOT EXISTS idx_delivery_confirmation ON t_document_delivery(confirmed_at);

-- T066/R19-P1-01 G2 gate schema（H2ではtriggerを省略し、MySQL V102で強制する）
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS mapping_version_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS mapping_version VARCHAR(50);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS mapping_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS review_policy_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS gate_evaluated_at TIMESTAMP(6);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS gate_snapshot_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS profile_snapshot_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS profile_snapshot_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS worker_snapshot_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS worker_snapshot_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS workplace_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS render_input_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS recipient_display_snapshot_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS company_config_snapshot_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS field_mask_policy_hash CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS render_engine_version VARCHAR(100);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS rendition_group_id VARCHAR(36);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS full_document_version_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS full_document_sha256 CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS mask_document_version_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS mask_document_sha256 CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS limited_document_version_id BIGINT;
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS limited_document_sha256 CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS delivery_business_key CHAR(64);
ALTER TABLE t_document_delivery ADD COLUMN IF NOT EXISTS generation_state VARCHAR(20);
CREATE UNIQUE INDEX IF NOT EXISTS uk_delivery_business_key ON t_document_delivery(tenant_id, delivery_business_key);
CREATE INDEX IF NOT EXISTS idx_delivery_mapping_version ON t_document_delivery(tenant_id, mapping_version_id);
CREATE INDEX IF NOT EXISTS idx_delivery_gate_evaluated ON t_document_delivery(tenant_id, gate_evaluated_at);
CREATE INDEX IF NOT EXISTS idx_delivery_rendition_group ON t_document_delivery(tenant_id, rendition_group_id);

CREATE TABLE IF NOT EXISTS m_compliance_mapping_version (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_code VARCHAR(100) NOT NULL,
  mapping_version VARCHAR(50) NOT NULL, mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL,
  effective_from DATE NOT NULL, effective_to DATE, status VARCHAR(30) NOT NULL, active_slot TINYINT, future_slot TINYINT,
  activated_at TIMESTAMP(6), activated_by BIGINT, version INT NOT NULL DEFAULT 0, created_by BIGINT,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
   deleted_flag TINYINT NOT NULL DEFAULT 0, UNIQUE(tenant_id, mapping_version), UNIQUE(tenant_id, mapping_code, active_slot), UNIQUE(tenant_id, mapping_code, future_slot), UNIQUE(tenant_id, id),
   CHECK((status = 'ACTIVE' AND active_slot = 1) OR (status <> 'ACTIVE' AND active_slot IS NULL))
);
CREATE TABLE IF NOT EXISTS m_compliance_mapping_source (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL,
  source_code VARCHAR(100) NOT NULL, source_url VARCHAR(1000) NOT NULL, source_version VARCHAR(100) NOT NULL,
  confirmed_on DATE NOT NULL, effective_from DATE NOT NULL, effective_to DATE, created_by BIGINT,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
   deleted_flag TINYINT NOT NULL DEFAULT 0, UNIQUE(tenant_id, mapping_id, source_code), UNIQUE(tenant_id, id),
   CONSTRAINT fk_g2_source_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
);
CREATE TABLE IF NOT EXISTS m_compliance_external_reviewer_type (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', type_code VARCHAR(100) NOT NULL,
  display_name VARCHAR(200) NOT NULL, description VARCHAR(1000), credential_label VARCHAR(200) NOT NULL,
  credential_required TINYINT NOT NULL DEFAULT 0, enabled TINYINT NOT NULL DEFAULT 1, sort_order INT NOT NULL DEFAULT 0,
  version INT NOT NULL DEFAULT 0, created_by BIGINT, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT,
  updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0, UNIQUE(tenant_id, type_code), UNIQUE(tenant_id, id)
);
-- V102_3: dynamic policy列（§8 NULL=UNCONFIGURED）
ALTER TABLE m_compliance_external_reviewer_type
  ADD COLUMN IF NOT EXISTS qualification_verification_required TINYINT NULL AFTER credential_required;
ALTER TABLE m_compliance_external_reviewer_type
  ADD COLUMN IF NOT EXISTS active_status_verification_required TINYINT NULL AFTER qualification_verification_required;
ALTER TABLE m_compliance_external_reviewer_type
  ADD COLUMN IF NOT EXISTS verification_source_id BIGINT NULL AFTER active_status_verification_required;
ALTER TABLE m_compliance_external_reviewer_type
  ADD COLUMN IF NOT EXISTS verification_method_id BIGINT NULL AFTER verification_source_id;
ALTER TABLE m_compliance_external_reviewer_type
  ADD COLUMN IF NOT EXISTS max_age_days INT NULL AFTER verification_method_id;
ALTER TABLE m_compliance_external_reviewer_type
  ADD COLUMN IF NOT EXISTS effective_from DATE NULL AFTER max_age_days;
ALTER TABLE m_compliance_external_reviewer_type
  ADD COLUMN IF NOT EXISTS effective_to DATE NULL AFTER effective_from;
CREATE TABLE IF NOT EXISTS m_compliance_verification_source (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  source_code VARCHAR(50) NOT NULL, source_name VARCHAR(200) NOT NULL, official_url VARCHAR(1000),
  enabled TINYINT NOT NULL DEFAULT 1, effective_from DATE, effective_to DATE, sort_order INT NOT NULL DEFAULT 0,
  created_by BIGINT, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT,
  updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id, source_code), UNIQUE(tenant_id, id)
);
CREATE TABLE IF NOT EXISTS m_compliance_verification_method (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  method_code VARCHAR(50) NOT NULL, method_name VARCHAR(200) NOT NULL, description VARCHAR(1000),
  enabled TINYINT NOT NULL DEFAULT 1, effective_from DATE, effective_to DATE, sort_order INT NOT NULL DEFAULT 0,
  created_by BIGINT, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT,
  updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id, method_code), UNIQUE(tenant_id, id)
);
CREATE TABLE IF NOT EXISTS m_compliance_mapping_review_requirement_group (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL,
  requirement_group_code VARCHAR(100) NOT NULL, display_name VARCHAR(200) NOT NULL, minimum_distinct_reviewers INT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0, version INT NOT NULL DEFAULT 0, created_by BIGINT, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
   updated_by BIGINT, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0,
   UNIQUE(tenant_id, mapping_id, requirement_group_code), UNIQUE(tenant_id, id),
   CONSTRAINT fk_g2_review_group_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
);
CREATE TABLE IF NOT EXISTS m_compliance_mapping_review_requirement_type (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', requirement_group_id BIGINT NOT NULL,
  reviewer_type_id BIGINT NOT NULL, reviewer_type_code_snapshot VARCHAR(100) NOT NULL, reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  credential_label_snapshot VARCHAR(200) NOT NULL, credential_required_snapshot TINYINT NOT NULL, created_by BIGINT,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
   deleted_flag TINYINT NOT NULL DEFAULT 0, UNIQUE(tenant_id, requirement_group_id, reviewer_type_id), UNIQUE(tenant_id, id),
   CONSTRAINT fk_g2_review_type_group FOREIGN KEY (tenant_id, requirement_group_id) REFERENCES m_compliance_mapping_review_requirement_group(tenant_id, id),
   CONSTRAINT fk_g2_review_type_reviewer FOREIGN KEY (tenant_id, reviewer_type_id) REFERENCES m_compliance_external_reviewer_type(tenant_id, id)
);
-- V102_3: frozen snapshot列（freeze時確定・§8）
ALTER TABLE m_compliance_mapping_review_requirement_type
  ADD COLUMN IF NOT EXISTS qualification_verification_required_snapshot TINYINT NOT NULL DEFAULT 0 AFTER credential_required_snapshot;
ALTER TABLE m_compliance_mapping_review_requirement_type
  ADD COLUMN IF NOT EXISTS active_status_verification_required_snapshot TINYINT NOT NULL DEFAULT 0 AFTER qualification_verification_required_snapshot;
CREATE TABLE IF NOT EXISTS t_compliance_responsible_assignment (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', workplace_id BIGINT NOT NULL, user_id BIGINT NOT NULL,
   role_code VARCHAR(40) NOT NULL DEFAULT 'COMPLIANCE_RESPONSIBLE', effective_from TIMESTAMP(6) NOT NULL, effective_to TIMESTAMP(6), active_slot TINYINT,
  assigned_by BIGINT NOT NULL, ended_by BIGINT, end_reason VARCHAR(500), version INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0,
   UNIQUE(tenant_id, workplace_id, active_slot), UNIQUE(tenant_id, id),
   CHECK((effective_to IS NULL AND active_slot = 1 AND ended_by IS NULL AND end_reason IS NULL) OR (effective_to IS NOT NULL AND active_slot IS NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL)),
   CONSTRAINT fk_g2_assignment_workplace FOREIGN KEY (tenant_id, workplace_id) REFERENCES m_workplace(tenant_id, id)
);
CREATE TABLE IF NOT EXISTS t_compliance_mapping_approval_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL, mapping_version VARCHAR(50) NOT NULL,
  mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL, assignment_id BIGINT NOT NULL, workplace_id_snapshot BIGINT NOT NULL,
  actor_id BIGINT NOT NULL, actor_display_name_snapshot VARCHAR(200) NOT NULL, actor_role_snapshot VARCHAR(50) NOT NULL, action VARCHAR(20) NOT NULL,
  event_chain_id VARCHAR(36) NOT NULL, target_event_id BIGINT, supersedes_event_id BIGINT, occurred_at TIMESTAMP(6) NOT NULL, reason VARCHAR(1000),
  evidence_document_id BIGINT, evidence_document_version_id BIGINT, evidence_document_version VARCHAR(100), evidence_document_hash CHAR(64), evidence_scan_status VARCHAR(30),
   operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
   UNIQUE(tenant_id, idempotency_key), UNIQUE(tenant_id, id),
   CONSTRAINT fk_g2_approval_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id),
   CONSTRAINT fk_g2_approval_assignment FOREIGN KEY (tenant_id, assignment_id) REFERENCES t_compliance_responsible_assignment(tenant_id, id),
   CONSTRAINT fk_g2_approval_workplace FOREIGN KEY (tenant_id, workplace_id_snapshot) REFERENCES m_workplace(tenant_id, id),
   CONSTRAINT fk_g2_approval_target FOREIGN KEY (tenant_id, target_event_id) REFERENCES t_compliance_mapping_approval_event(tenant_id, id),
   CONSTRAINT fk_g2_approval_supersedes FOREIGN KEY (tenant_id, supersedes_event_id) REFERENCES t_compliance_mapping_approval_event(tenant_id, id)
);
CREATE TABLE IF NOT EXISTS t_compliance_external_review_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL, mapping_version VARCHAR(50) NOT NULL,
  mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL, requirement_group_id BIGINT NOT NULL, requirement_group_code_snapshot VARCHAR(100) NOT NULL,
  reviewer_type_id BIGINT NOT NULL, reviewer_type_code_snapshot VARCHAR(100) NOT NULL, reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  reviewer_name_snapshot VARCHAR(200) NOT NULL, organization_snapshot VARCHAR(255), credential_snapshot_encrypted CLOB, credential_key_version VARCHAR(64),
  credential_cipher_format VARCHAR(20), credential_masked_snapshot VARCHAR(255), reviewer_identity_hash CHAR(64) NOT NULL, action VARCHAR(20) NOT NULL,
  review_chain_id VARCHAR(36) NOT NULL, target_event_id BIGINT, supersedes_event_id BIGINT, reviewed_at TIMESTAMP(6) NOT NULL, valid_until TIMESTAMP(6), recorded_at TIMESTAMP(6) NOT NULL,
  evidence_document_id BIGINT, evidence_document_version_id BIGINT, evidence_document_version VARCHAR(100), evidence_document_hash CHAR(64), recorded_by BIGINT NOT NULL,
   operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
   UNIQUE(tenant_id, idempotency_key), UNIQUE(tenant_id, id),
   CONSTRAINT fk_g2_external_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id),
   CONSTRAINT fk_g2_external_group FOREIGN KEY (tenant_id, requirement_group_id) REFERENCES m_compliance_mapping_review_requirement_group(tenant_id, id),
   CONSTRAINT fk_g2_external_reviewer_type FOREIGN KEY (tenant_id, reviewer_type_id) REFERENCES m_compliance_external_reviewer_type(tenant_id, id),
   CONSTRAINT fk_g2_external_target FOREIGN KEY (tenant_id, target_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id),
   CONSTRAINT fk_g2_external_supersedes FOREIGN KEY (tenant_id, supersedes_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id)
);
CREATE TABLE IF NOT EXISTS t_compliance_mapping_status_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', mapping_id BIGINT NOT NULL, mapping_version VARCHAR(50) NOT NULL,
  mapping_hash CHAR(64) NOT NULL, review_policy_hash CHAR(64) NOT NULL, before_status VARCHAR(30), after_status VARCHAR(30) NOT NULL,
  actor_id BIGINT NOT NULL, actor_display_name_snapshot VARCHAR(200) NOT NULL, actor_role_snapshot VARCHAR(50) NOT NULL, occurred_at TIMESTAMP(6) NOT NULL,
  expected_version INT NOT NULL, gate_snapshot_hash CHAR(64), operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, reason VARCHAR(1000),
   created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, UNIQUE(tenant_id, id),
   CONSTRAINT fk_g2_status_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
);
CREATE TABLE IF NOT EXISTS t_compliance_operation_ledger (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default', operation_id VARCHAR(36) NOT NULL,
  operation_type VARCHAR(60) NOT NULL, idempotency_key VARCHAR(200) NOT NULL, request_hash CHAR(64) NOT NULL, state VARCHAR(20) NOT NULL,
  retryable_flag TINYINT NOT NULL DEFAULT 0, attempt_count INT NOT NULL DEFAULT 1, started_at TIMESTAMP(6) NOT NULL, lease_until TIMESTAMP(6), finished_at TIMESTAMP(6),
  result_reference_type VARCHAR(80), result_reference_id BIGINT, result_reference_version VARCHAR(100), result_summary_canonical CLOB, result_http_status INT,
  result_hash CHAR(64), failure_code VARCHAR(100), correlation_id VARCHAR(100) NOT NULL, expires_at TIMESTAMP(6), version INT NOT NULL DEFAULT 0,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id, operation_type, idempotency_key), UNIQUE(tenant_id, operation_id),
  CHECK((state = 'SUCCEEDED' AND finished_at IS NOT NULL AND failure_code IS NULL
      AND result_summary_canonical IS NOT NULL AND result_http_status IS NOT NULL AND result_hash IS NOT NULL)
    OR (state = 'PROCESSING' AND finished_at IS NULL AND failure_code IS NULL
      AND result_reference_type IS NULL AND result_reference_id IS NULL AND result_reference_version IS NULL
      AND result_summary_canonical IS NULL AND result_http_status IS NULL AND result_hash IS NULL)
    OR (state = 'FAILED' AND finished_at IS NOT NULL AND failure_code IS NOT NULL
      AND result_reference_type IS NULL AND result_reference_id IS NULL AND result_reference_version IS NULL
      AND result_summary_canonical IS NULL AND result_http_status IS NULL AND result_hash IS NULL))
);

-- R23-P1-01: reviewer subject / verification / adoption events (H2 mirror of V102_1)
CREATE TABLE IF NOT EXISTS t_compliance_external_reviewer_subject (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  subject_code VARCHAR(100) NOT NULL, display_name VARCHAR(200) NOT NULL, organization_name VARCHAR(200) NOT NULL,
  person_fingerprint_snapshot CHAR(64) NOT NULL, fingerprint_key_version VARCHAR(64) NOT NULL,
  created_by BIGINT, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, updated_by BIGINT, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0, UNIQUE(tenant_id, subject_code), UNIQUE(tenant_id, id),
  CHECK(CHAR_LENGTH(person_fingerprint_snapshot) = 64)
);
-- V102_3: subject×資格association（subject CREATE後に定義）
CREATE TABLE IF NOT EXISTS t_compliance_reviewer_qualification (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  reviewer_subject_id BIGINT NOT NULL, reviewer_type_id BIGINT NOT NULL,
  registration_identifier_masked_snapshot VARCHAR(255), registration_identifier_label VARCHAR(200),
  enabled TINYINT NOT NULL DEFAULT 1, created_by BIGINT, created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
  updated_by BIGINT, updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP, deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE(tenant_id, reviewer_subject_id, reviewer_type_id), UNIQUE(tenant_id, id),
  CONSTRAINT fk_g2_qualification_subject FOREIGN KEY (tenant_id, reviewer_subject_id) REFERENCES t_compliance_external_reviewer_subject(tenant_id, id),
  CONSTRAINT fk_g2_qualification_type FOREIGN KEY (tenant_id, reviewer_type_id) REFERENCES m_compliance_external_reviewer_type(tenant_id, id)
);
CREATE TABLE IF NOT EXISTS t_compliance_external_reviewer_verification_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  reviewer_type_id BIGINT NOT NULL, reviewer_type_code_snapshot VARCHAR(100) NOT NULL, reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  reviewer_subject_id BIGINT NOT NULL, person_fingerprint_snapshot CHAR(64) NOT NULL, qualification_fingerprint_snapshot CHAR(64) NOT NULL,
  fingerprint_key_version VARCHAR(64) NOT NULL, verification_kind VARCHAR(20) NOT NULL, result VARCHAR(20) NOT NULL,
  method_code VARCHAR(50) NOT NULL, authority_source_code VARCHAR(50) NOT NULL, authority_source_name VARCHAR(200) NOT NULL,
  official_url_reference_snapshot VARCHAR(1000), registration_identifier_encrypted CLOB,
  registration_identifier_key_version VARCHAR(64), registration_identifier_cipher_format VARCHAR(20), registration_identifier_masked_snapshot VARCHAR(255),
  checked_at TIMESTAMP(6) NOT NULL, source_data_as_of TIMESTAMP(6), max_age_days_snapshot INT, valid_until TIMESTAMP(6), checked_by BIGINT NOT NULL,
  evidence_document_id BIGINT, evidence_document_version_id BIGINT, evidence_document_version VARCHAR(100), evidence_document_hash CHAR(64),
  review_policy_version VARCHAR(50), review_policy_hash CHAR(64), mapping_id BIGINT, mapping_version VARCHAR(50), mapping_hash CHAR(64),
  external_review_event_id BIGINT, external_review_chain_id VARCHAR(36), submitted_review_event_id BIGINT NOT NULL,
  revoked_verification_event_id BIGINT, supersedes_verification_event_id BIGINT,
  operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(tenant_id, idempotency_key), UNIQUE(tenant_id, id),
  CHECK(verification_kind IN ('IDENTITY','QUALIFICATION','ACTIVE_STATUS','REVIEW_AUTHORSHIP')),
  CHECK(result IN ('VERIFIED','FAILED','INCONCLUSIVE','REVOKED')),
  CHECK(CHAR_LENGTH(person_fingerprint_snapshot) = 64 AND CHAR_LENGTH(qualification_fingerprint_snapshot) = 64),
  CHECK((registration_identifier_encrypted IS NULL AND registration_identifier_key_version IS NULL
      AND registration_identifier_cipher_format IS NULL AND registration_identifier_masked_snapshot IS NULL)
    OR (registration_identifier_encrypted IS NOT NULL AND registration_identifier_key_version IS NOT NULL
      AND registration_identifier_cipher_format IS NOT NULL AND registration_identifier_masked_snapshot IS NOT NULL)),
  CHECK((evidence_document_id IS NULL AND evidence_document_version_id IS NULL
      AND evidence_document_version IS NULL AND evidence_document_hash IS NULL)
    OR (evidence_document_id IS NOT NULL AND evidence_document_version_id IS NOT NULL
      AND evidence_document_version IS NOT NULL AND evidence_document_hash IS NOT NULL)),
  CHECK((result = 'REVOKED' AND revoked_verification_event_id IS NOT NULL)
    OR (result <> 'REVOKED' AND revoked_verification_event_id IS NULL)),
  CHECK((verification_kind = 'REVIEW_AUTHORSHIP'
      AND review_policy_version IS NOT NULL AND review_policy_hash IS NOT NULL
      AND mapping_id IS NOT NULL AND mapping_version IS NOT NULL AND mapping_hash IS NOT NULL
      AND external_review_event_id IS NOT NULL AND external_review_chain_id IS NOT NULL)
    OR (verification_kind <> 'REVIEW_AUTHORSHIP'
      AND review_policy_version IS NULL AND review_policy_hash IS NULL
      AND mapping_id IS NULL AND mapping_version IS NULL AND mapping_hash IS NULL
      AND external_review_event_id IS NULL AND external_review_chain_id IS NULL)),
  CONSTRAINT fk_g2_verification_subject FOREIGN KEY (tenant_id, reviewer_subject_id) REFERENCES t_compliance_external_reviewer_subject(tenant_id, id),
  CONSTRAINT fk_g2_verification_submitted FOREIGN KEY (tenant_id, submitted_review_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id),
  CONSTRAINT fk_g2_verification_revoked FOREIGN KEY (tenant_id, revoked_verification_event_id) REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id),
  CONSTRAINT fk_g2_verification_supersedes FOREIGN KEY (tenant_id, supersedes_verification_event_id) REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id),
  CONSTRAINT fk_g2_verification_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id),
  CONSTRAINT fk_g2_verification_review FOREIGN KEY (tenant_id, external_review_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id)
);
CREATE TABLE IF NOT EXISTS t_compliance_external_review_adoption_event (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
  action VARCHAR(20) NOT NULL, review_chain_id VARCHAR(36) NOT NULL, submitted_review_event_id BIGINT NOT NULL, revoked_adoption_event_id BIGINT,
  identity_verification_event_id BIGINT, qualification_verification_event_id BIGINT, active_status_verification_event_id BIGINT,
  authorship_verification_event_id BIGINT, mapping_id BIGINT, mapping_version VARCHAR(50), mapping_hash CHAR(64),
  review_policy_version VARCHAR(50), review_policy_hash CHAR(64),
  evidence_document_id BIGINT, evidence_document_version_id BIGINT, evidence_document_version VARCHAR(100), evidence_document_hash CHAR(64),
  adopted_at TIMESTAMP(6) NOT NULL, adopted_by BIGINT NOT NULL,
  operation_id VARCHAR(36) NOT NULL, correlation_id VARCHAR(100) NOT NULL, idempotency_key VARCHAR(200) NOT NULL,
  created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP,
  -- V102_3 R23-R1-P1-01: 初回adoption一意化（APPROVED/REJECTEDのみ非NULLの生成列）
  first_slot BIGINT GENERATED ALWAYS AS (CASE WHEN action IN ('APPROVED','REJECTED') THEN submitted_review_event_id ELSE NULL END),
  UNIQUE(tenant_id, idempotency_key), UNIQUE(tenant_id, id), UNIQUE(tenant_id, first_slot),
  CHECK(action IN ('SUBMITTED','APPROVED','REJECTED','REVOKED')),
  CHECK((action = 'APPROVED'
      AND identity_verification_event_id IS NOT NULL AND authorship_verification_event_id IS NOT NULL
      AND mapping_id IS NOT NULL AND mapping_version IS NOT NULL AND mapping_hash IS NOT NULL
      AND review_policy_version IS NOT NULL AND review_policy_hash IS NOT NULL
      AND evidence_document_id IS NOT NULL AND evidence_document_version_id IS NOT NULL
      AND evidence_document_version IS NOT NULL AND evidence_document_hash IS NOT NULL)
    OR (action <> 'APPROVED'
      AND identity_verification_event_id IS NULL AND authorship_verification_event_id IS NULL
      AND mapping_id IS NULL AND mapping_version IS NULL AND mapping_hash IS NULL
      AND review_policy_version IS NULL AND review_policy_hash IS NULL
      AND evidence_document_id IS NULL AND evidence_document_version_id IS NULL
      AND evidence_document_version IS NULL AND evidence_document_hash IS NULL)),
  CHECK((action = 'REVOKED' AND revoked_adoption_event_id IS NOT NULL)
    OR (action <> 'REVOKED' AND revoked_adoption_event_id IS NULL)),
  CONSTRAINT fk_g2_adoption_submitted FOREIGN KEY (tenant_id, submitted_review_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id),
  CONSTRAINT fk_g2_adoption_revoked FOREIGN KEY (tenant_id, revoked_adoption_event_id) REFERENCES t_compliance_external_review_adoption_event(tenant_id, id),
  CONSTRAINT fk_g2_adoption_identity FOREIGN KEY (tenant_id, identity_verification_event_id) REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id),
  CONSTRAINT fk_g2_adoption_qualification FOREIGN KEY (tenant_id, qualification_verification_event_id) REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id),
  CONSTRAINT fk_g2_adoption_active_status FOREIGN KEY (tenant_id, active_status_verification_event_id) REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id),
  CONSTRAINT fk_g2_adoption_authorship FOREIGN KEY (tenant_id, authorship_verification_event_id) REFERENCES t_compliance_external_reviewer_verification_event(tenant_id, id),
  CONSTRAINT fk_g2_adoption_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
);


-- ============================================================
-- S12 staffing-capacity-planning（T075 F1・V103相当）: 案件ポジション/配置計画/シナリオ
-- ============================================================
ALTER TABLE t_proposal ADD COLUMN IF NOT EXISTS position_id BIGINT;
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS position_id BIGINT;

DROP TABLE IF EXISTS t_staffing_scenario_allocation CASCADE;
DROP TABLE IF EXISTS t_staffing_scenario CASCADE;
DROP TABLE IF EXISTS t_allocation_plan CASCADE;
DROP TABLE IF EXISTS t_project_position CASCADE;

CREATE TABLE t_project_position (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id         BIGINT NOT NULL,
  position_no        VARCHAR(50) NOT NULL,
  role_name          VARCHAR(200) NOT NULL,
  required_count     INT NOT NULL DEFAULT 1,
  skills_json        TEXT,
  unit_price_min     DECIMAL(10,0),
  unit_price_max     DECIMAL(10,0),
  start_date         DATE,
  end_date           DATE,
  location           VARCHAR(255),
  allocation_percent DECIMAL(5,2) NOT NULL DEFAULT 100,
  priority           VARCHAR(20),
  status             VARCHAR(20) NOT NULL DEFAULT '募集中',
  version            INT NOT NULL DEFAULT 0,
  created_by         BIGINT,
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag       TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_project_position_count CHECK (required_count >= 1),
  CONSTRAINT chk_project_position_percent CHECK (allocation_percent > 0 AND allocation_percent <= 100),
  CONSTRAINT chk_project_position_price CHECK (unit_price_min IS NULL OR unit_price_max IS NULL OR unit_price_min <= unit_price_max),
  CONSTRAINT chk_project_position_period CHECK (end_date IS NULL OR start_date IS NULL OR start_date <= end_date),
  CONSTRAINT chk_project_position_status CHECK (status IN ('募集中','候補選定','充足','保留','取消'))
);
CREATE UNIQUE INDEX uk_project_position_no ON t_project_position(project_id, position_no);
CREATE INDEX idx_project_position_status ON t_project_position(status);
CREATE INDEX idx_project_position_period ON t_project_position(start_date, end_date);

CREATE TABLE t_allocation_plan (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id        BIGINT NOT NULL,
  position_id        BIGINT,
  allocation_type    VARCHAR(20) NOT NULL DEFAULT '案件',
  start_date         DATE NOT NULL,
  end_date           DATE,
  allocation_percent DECIMAL(5,2) NOT NULL,
  status             VARCHAR(20) NOT NULL DEFAULT '下書き',
  source_contract_id BIGINT,
  exception_reason   VARCHAR(1000),
  approval_request_id BIGINT,
  version            INT NOT NULL DEFAULT 0,
  created_by         BIGINT,
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag       TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_allocation_plan_period CHECK (end_date IS NULL OR start_date <= end_date),
  CONSTRAINT chk_allocation_plan_percent CHECK (allocation_percent > 0 AND allocation_percent <= 100),
  CONSTRAINT chk_allocation_plan_status CHECK (status IN ('下書き','確定','破棄')),
  CONSTRAINT chk_allocation_plan_type CHECK (
    (allocation_type = '案件' AND position_id IS NOT NULL)
    OR (allocation_type IN ('社内','待機') AND position_id IS NULL))
);
CREATE INDEX idx_allocation_plan_engineer_period ON t_allocation_plan(engineer_id, start_date, end_date);
CREATE INDEX idx_allocation_plan_position ON t_allocation_plan(position_id);
CREATE INDEX idx_allocation_plan_status ON t_allocation_plan(status);
CREATE INDEX idx_allocation_plan_source_contract ON t_allocation_plan(source_contract_id);

CREATE TABLE t_staffing_scenario (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner_user_id    BIGINT NOT NULL,
  name             VARCHAR(200) NOT NULL,
  base_date        DATE NOT NULL,
  shared_flag      TINYINT NOT NULL DEFAULT 0,
  assumptions_json TEXT,
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag     TINYINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_staffing_scenario_owner ON t_staffing_scenario(owner_user_id);

CREATE TABLE t_staffing_scenario_allocation (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  scenario_id BIGINT NOT NULL,
  engineer_id BIGINT NOT NULL,
  position_id BIGINT,
  dates       TEXT NOT NULL,
  percent     DECIMAL(5,2) NOT NULL,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at  DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_scenario_alloc_percent CHECK (percent > 0 AND percent <= 100)
);
CREATE INDEX idx_scenario_alloc_scenario ON t_staffing_scenario_allocation(scenario_id);
CREATE INDEX idx_scenario_alloc_engineer ON t_staffing_scenario_allocation(engineer_id);

-- ============================================================
-- 顧客・BP外部ポータル (V104, S13 external-customer-bp-portal)
-- 本ファイルはFKを張る方針（既存t_engineer等と同じ）のため、portalもFK付きで再現する。
-- ============================================================
DROP TABLE IF EXISTS t_portal_access_log CASCADE;
DROP TABLE IF EXISTS t_portal_session CASCADE;
DROP TABLE IF EXISTS t_portal_terms_consent CASCADE;
DROP TABLE IF EXISTS t_portal_user_permission CASCADE;
DROP TABLE IF EXISTS t_portal_invitation CASCADE;
DROP TABLE IF EXISTS t_portal_user CASCADE;
DROP TABLE IF EXISTS m_portal_organization CASCADE;

CREATE TABLE m_portal_organization (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     VARCHAR(64) NOT NULL DEFAULT 'default',
  type          VARCHAR(20) NOT NULL,
  customer_id   BIGINT,
  bp_company_id BIGINT,
  status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag  TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_portal_org_customer (customer_id),
  UNIQUE KEY uk_portal_org_bp (bp_company_id),
  CONSTRAINT chk_portal_org_type CHECK (type IN ('CUSTOMER','BP')),
  CONSTRAINT chk_portal_org_status CHECK (status IN ('ACTIVE','SUSPENDED')),
  CONSTRAINT fk_portal_org_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id),
  CONSTRAINT fk_portal_org_bp FOREIGN KEY (bp_company_id) REFERENCES m_bp_company(id)
);

CREATE TABLE t_portal_user (
  id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
  portal_org_id          BIGINT NOT NULL,
  email                  VARCHAR(255) NOT NULL,
  display_name           VARCHAR(255),
  password_hash          VARCHAR(255),
  status                 VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  mfa_policy             VARCHAR(20) NOT NULL DEFAULT 'REQUIRED',
  notify_email           TINYINT NOT NULL DEFAULT 1,
  totp_secret_encrypted  VARCHAR(255),
  totp_secret_key_version VARCHAR(64),
  mfa_enabled_at         DATETIME,
  recovery_code_hash     VARCHAR(255),
  recovery_code_used_at  DATETIME,
  last_used_step         BIGINT,
  last_login_at          DATETIME,
  version                INT NOT NULL DEFAULT 0,
  created_at             DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag           TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_portal_user_email (email),
  CONSTRAINT chk_portal_user_status CHECK (status IN ('ACTIVE','SUSPENDED')),
  CONSTRAINT chk_portal_user_mfa_policy CHECK (mfa_policy IN ('REQUIRED','OPTIONAL')),
  CONSTRAINT fk_portal_user_org FOREIGN KEY (portal_org_id) REFERENCES m_portal_organization(id)
);

CREATE TABLE t_portal_invitation (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  portal_org_id BIGINT NOT NULL,
  email         VARCHAR(255) NOT NULL,
  role          VARCHAR(50) NOT NULL DEFAULT 'MEMBER',
  token_hash    CHAR(64) NOT NULL,
  expires_at    DATETIME NOT NULL,
  used_at       DATETIME,
  accepted_by   BIGINT,
  invited_by    BIGINT,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag  TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_portal_invite_token_hash (token_hash),
  CONSTRAINT chk_portal_invite_role CHECK (role IN ('MEMBER','ADMIN')),
  CONSTRAINT fk_portal_invite_org FOREIGN KEY (portal_org_id) REFERENCES m_portal_organization(id)
);

CREATE TABLE t_portal_user_permission (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id        BIGINT NOT NULL,
  permission_key VARCHAR(100) NOT NULL,
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_portal_user_permission (user_id, permission_key),
  CONSTRAINT fk_portal_user_perm_user FOREIGN KEY (user_id) REFERENCES t_portal_user(id)
);

CREATE TABLE t_portal_terms_consent (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT NOT NULL,
  terms_version VARCHAR(50) NOT NULL,
  consented_at  DATETIME NOT NULL,
  ip_hash       VARCHAR(64),
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_portal_terms_consent (user_id, terms_version),
  CONSTRAINT fk_portal_terms_user FOREIGN KEY (user_id) REFERENCES t_portal_user(id)
);

CREATE TABLE t_portal_session (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id       BIGINT NOT NULL,
  token_hash    CHAR(64) NOT NULL,
  issued_at     DATETIME NOT NULL,
  last_seen_at  DATETIME NOT NULL,
  idle_expires_at DATETIME NOT NULL,
  expires_at    DATETIME NOT NULL,
  ip_hash       VARCHAR(64),
  user_agent    VARCHAR(512),
  revoked_at    DATETIME,
  revoked_reason VARCHAR(100),
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_portal_session_token_hash (token_hash),
  CONSTRAINT fk_portal_session_user FOREIGN KEY (user_id) REFERENCES t_portal_user(id)
);

CREATE TABLE t_portal_access_log (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  portal_user_id BIGINT NOT NULL,
  portal_org_id  BIGINT NOT NULL,
  email          VARCHAR(255) NOT NULL,
  org_type       VARCHAR(20) NOT NULL,
  action         VARCHAR(50) NOT NULL,
  target_type    VARCHAR(50),
  target_id      BIGINT,
  ip_hash        VARCHAR(64),
  user_agent     VARCHAR(512),
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_portal_access_log_org ON t_portal_access_log(portal_org_id, created_at);
CREATE INDEX idx_portal_access_log_user ON t_portal_access_log(portal_user_id, created_at);
CREATE INDEX idx_portal_access_log_action ON t_portal_access_log(action, created_at);

-- S15: 会計・支払連携テーブル (m_integration_connection, m_external_mapping, t_integration_job, t_integration_job_event)
DROP TABLE IF EXISTS t_integration_job_event CASCADE;
DROP TABLE IF EXISTS t_integration_job CASCADE;
DROP TABLE IF EXISTS m_external_mapping CASCADE;
DROP TABLE IF EXISTS m_integration_connection CASCADE;

CREATE TABLE m_integration_connection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    legal_entity_id BIGINT,
    provider VARCHAR(32) NOT NULL,
    product VARCHAR(32) NOT NULL,
    external_company_id BIGINT,
    external_company_key BIGINT AS (COALESCE(external_company_id, 0)),
    company_name VARCHAR(255),
    encrypted_tokens TEXT,
    expires_at DATETIME,
    status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED',
    connected_by BIGINT,
    connected_at TIMESTAMP,
    last_refreshed_at TIMESTAMP,
    token_version INT NOT NULL DEFAULT 1,
    refresh_lease_token VARCHAR(64),
    refresh_lease_expires_at DATETIME,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_int_conn ON m_integration_connection (tenant_id, legal_entity_id, external_company_key, provider, product, deleted_flag);

CREATE TABLE m_external_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id BIGINT NULL,
    object_type VARCHAR(64) NOT NULL,
    internal_id BIGINT,
    internal_code VARCHAR(64) NOT NULL,
    external_id VARCHAR(64) NOT NULL,
    external_code VARCHAR(64),
    payload_snapshot TEXT,
    verified_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_ext_mapping ON m_external_mapping (connection_id, object_type, internal_code, deleted_flag);
CREATE INDEX idx_ext_mapping_conn ON m_external_mapping (connection_id, object_type);

CREATE TABLE t_integration_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id BIGINT NULL,
    job_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT NOT NULL,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    legal_entity_id BIGINT,
    organization_id BIGINT,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_snapshot TEXT,
    payload_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    lease_token VARCHAR(64),
    lease_expires_at DATETIME,
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    next_retry_at DATETIME,
    external_id VARCHAR(128),
    provider_request_id VARCHAR(128),
    error_code VARCHAR(64),
    error_message_safe VARCHAR(500),
    sent_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_int_job_idempotency ON t_integration_job (idempotency_key, deleted_flag);
CREATE INDEX idx_int_job_status ON t_integration_job (status, next_retry_at);
CREATE INDEX idx_int_job_target ON t_integration_job (target_type, target_id);

CREATE TABLE t_integration_job_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    occurred_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    safe_detail VARCHAR(1000)
);
CREATE INDEX idx_job_event_job_id ON t_integration_job_event (job_id);
-- H2 Schema for JP PINT Digital Invoice (T103)

CREATE TABLE IF NOT EXISTS t_peppol_participant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_type VARCHAR(50) NOT NULL,
    owner_id BIGINT NOT NULL,
    scheme_id VARCHAR(50) NOT NULL,
    participant_id VARCHAR(100) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    verified_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(50) NULL,
    deleted_flag TINYINT(1) DEFAULT 0,
    UNIQUE KEY uk_peppol_participant_owner (owner_type, owner_id)
);

CREATE TABLE IF NOT EXISTS t_digital_invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NULL,
    direction VARCHAR(20) NOT NULL,
    profile VARCHAR(50) NOT NULL,
    specification_version VARCHAR(20) NOT NULL,
    message_id VARCHAR(100) NOT NULL,
    provider_message_id VARCHAR(100) NULL,
    xml_document_id BIGINT NULL,
    validation_document_id BIGINT NULL,
    status VARCHAR(20) NOT NULL,
    sent_at DATETIME NULL,
    received_at DATETIME NULL,
    version BIGINT NOT NULL DEFAULT 0,
    supplier_company_id BIGINT NULL,
    purchase_order_id BIGINT NULL,
    contract_id BIGINT NULL,
    match_status VARCHAR(20) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(50) NULL,
    deleted_flag TINYINT(1) DEFAULT 0,
    send_active_slot TINYINT GENERATED ALWAYS AS (
        CASE
            WHEN direction = 'SEND'
             AND deleted_flag = 0
             AND status NOT IN ('CANCELLED', 'REVOKED')
            THEN 1
            ELSE NULL
        END
    ),
    UNIQUE KEY uk_digital_invoice_message (message_id),
    UNIQUE KEY uk_digital_invoice_send (invoice_id, direction, profile, specification_version, send_active_slot)
);

CREATE TABLE IF NOT EXISTS t_digital_invoice_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    digital_invoice_id BIGINT NOT NULL,
    provider_event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_at DATETIME NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    signature_valid TINYINT(1) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    UNIQUE KEY uk_digital_invoice_event_provider (provider_event_id)
);

ALTER TABLE t_proposal ADD COLUMN IF NOT EXISTS ai_trace_id VARCHAR(36);
ALTER TABLE t_proposal ADD COLUMN IF NOT EXISTS ai_item_id BIGINT;

DROP TABLE IF EXISTS t_pwa_client_mutation CASCADE;
CREATE TABLE t_pwa_client_mutation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_request_id VARCHAR(80) NOT NULL,
    user_id BIGINT NOT NULL,
    user_scope_hash CHAR(64) NOT NULL,
    operation VARCHAR(160),
    screen VARCHAR(40) NOT NULL,
    work_month CHAR(7),
    payload_hash CHAR(64) NOT NULL,
    base_version INT,
    status VARCHAR(20) NOT NULL,
    response_json LONGTEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME,
    CONSTRAINT uk_pwa_client_mutation_user_request UNIQUE (user_id, client_request_id)
);
