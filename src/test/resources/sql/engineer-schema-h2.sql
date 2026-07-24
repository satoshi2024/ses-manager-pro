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
  created_by        BIGINT,
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag      TINYINT DEFAULT 0
);

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
  settlement_hours_min    DECIMAL(5,1),
  settlement_hours_max    DECIMAL(5,1),
  fraction_rule           VARCHAR(50),
  auto_renew              TINYINT DEFAULT 0,
  status                  VARCHAR(20) DEFAULT '稼動中',
  remarks                 TEXT,
  direct_command_flag     TINYINT NOT NULL DEFAULT 0,
  sales_user_id           BIGINT,
  commission_base_type    VARCHAR(10),
  commission_rate         DECIMAL(5,2),
  renewed_from_contract_id BIGINT,
  quotation_id            BIGINT,
  renewal_decision        VARCHAR(20),
  created_by              BIGINT,
  created_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at              DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag            TINYINT DEFAULT 0
);

DROP TABLE IF EXISTS t_quotation CASCADE;
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
  created_by            BIGINT,
  created_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at            DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag          TINYINT DEFAULT 0
);

DROP TABLE IF EXISTS t_proposal CASCADE;

-- Proposal エンティティのマッピング（proposed_unit_price / proposed_by 等）と一致させること。
-- t_proposal に created_at 列は無い（作成日時は proposed_at。エンティティ側も exist=false）。
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
  tax_rate      DECIMAL(4,3),
  status        VARCHAR(20) DEFAULT '未送付',
  issued_date   DATE,
  paid_date     DATE,
  due_date      DATE,
  remarks       VARCHAR(500),
  created_by    BIGINT,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT DEFAULT 0
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
  parent_payment_id  BIGINT,
  amount             DECIMAL(12,0) NOT NULL,
  status             VARCHAR(20) DEFAULT '未払',
  paid_date          DATE,
  remarks            VARCHAR(500),
  deleted_flag       TINYINT NOT NULL DEFAULT 0,
  created_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at         DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_work_record_layer (work_record_id, layer_order),
  CONSTRAINT fk_bp_payment_parent FOREIGN KEY (parent_payment_id) REFERENCES t_bp_payment(id)
);

DROP TABLE IF EXISTS t_sales_activity CASCADE;
CREATE TABLE t_sales_activity (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  customer_id      BIGINT NOT NULL,
  activity_type    VARCHAR(20) NOT NULL,
  activity_date    DATE NOT NULL,
  title            VARCHAR(200) NOT NULL,
  content          TEXT,
  next_action_date DATE,
  completed_flag   TINYINT DEFAULT 0,
  created_by       BIGINT,
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag     TINYINT DEFAULT 0
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

-- Menu エンティティは path_prefix/api_prefix/created_at/updated_at にマッピングされる
-- （旧 path/parent_id 定義は実体と不一致で MenuMapper.selectList() が
--   「Unknown column」で失敗する潜在バグだったため実スキーマに合わせて修正）。
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

DROP TABLE IF EXISTS t_role_menu CASCADE;
CREATE TABLE t_role_menu (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  role     VARCHAR(50) NOT NULL,
  menu_id  BIGINT NOT NULL
);

-- SysUser エンティティ（BaseEntity継承 + failed_count/locked_until）
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

-- グローバルスキーマ初期化(V2)のadminシードと同一内容を再投入する。
-- このスクリプトはsys_userを再作成するため、他テストクラスと同一H2インスタンスを
-- 共有する実行順序次第でadminユーザーが失われる問題を防ぐ。
INSERT INTO sys_user (username, password, real_name, role, email, status)
VALUES ('admin', 'admin123', 'システム管理者', '管理者', 'admin@ses.local', 1);

-- SystemConfig エンティティ（文字列PK、BaseEntity非継承）
DROP TABLE IF EXISTS m_system_config CASCADE;
CREATE TABLE m_system_config (
  config_key   VARCHAR(100) PRIMARY KEY,
  config_value TEXT,
  description  VARCHAR(200)
);

-- AiLog エンティティ（updated_at/deleted_flagはexist=falseで対象外）
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

-- EmailTemplate エンティティ（deleted_flagはexist=falseで対象外）
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

-- AuditLog エンティティ（監査ログ、P8フォローアップ）
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

-- freee 給与連携（給与明細自体は保存しない）
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

-- 要員セルフサービス勤怠（engineer-self-service-timesheet / V32）
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

-- 契約単価改定履歴（contract-price-history / V33）
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
  status                VARCHAR(20)  NOT NULL DEFAULT '\u53d6\u8fbc\u5f85\u3061',
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

-- 銀行入金明細（入金消込の半自動化 / FR-09 / V50）
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
