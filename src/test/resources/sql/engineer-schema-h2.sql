-- 削除機能検証用の最小スキーマ（H2 / MySQLモード互換）
-- 本番の 001_create_tables.sql は ENUM / ENGINE=InnoDB / インライン INDEX など
-- H2 が解釈できない構文を含むため、検証に必要な列だけを持つ簡易版を用意する。

DROP TABLE IF EXISTS m_customer;
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

DROP TABLE IF EXISTS t_engineer;

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

DROP TABLE IF EXISTS m_skill_tag;
CREATE TABLE m_skill_tag (
  id         BIGINT       AUTO_INCREMENT PRIMARY KEY,
  skill_name VARCHAR(100) NOT NULL,
  category   VARCHAR(50),
  created_at DATETIME
);

DROP TABLE IF EXISTS t_engineer_skill;
CREATE TABLE t_engineer_skill (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id      BIGINT NOT NULL,
  skill_id         BIGINT NOT NULL,
  proficiency      VARCHAR(20),
  experience_years INT
);

DROP TABLE IF EXISTS t_engineer_career;
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

DROP TABLE IF EXISTS t_project_skill;
CREATE TABLE t_project_skill (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_id     BIGINT NOT NULL,
  skill_id       BIGINT NOT NULL,
  required_level VARCHAR(20),
  is_must        TINYINT DEFAULT 1
);

DROP TABLE IF EXISTS t_notification;
CREATE TABLE t_notification (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  type        VARCHAR(30)  NOT NULL,
  title       VARCHAR(200) NOT NULL,
  message     VARCHAR(500),
  link_url    VARCHAR(300),
  dedupe_key  VARCHAR(200) NOT NULL UNIQUE,
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_notification_read;
CREATE TABLE t_notification_read (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  user_id         BIGINT NOT NULL,
  read_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_notification_read (notification_id, user_id)
);

DROP TABLE IF EXISTS t_project;
CREATE TABLE t_project (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  project_name      VARCHAR(200) NOT NULL,
  customer_id       BIGINT,
  status            VARCHAR(30) DEFAULT '募集中',
  start_date        DATE,
  end_date          DATE,
  remarks           TEXT,
  created_by        BIGINT,
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag      TINYINT DEFAULT 0
);

DROP TABLE IF EXISTS t_contract;
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
  created_by              BIGINT,
  created_at              DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at              DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag            TINYINT DEFAULT 0
);

DROP TABLE IF EXISTS t_proposal;

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

DROP TABLE IF EXISTS t_proposal_history;
CREATE TABLE t_proposal_history (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  proposal_id     BIGINT NOT NULL,
  from_status     VARCHAR(30),
  to_status       VARCHAR(30) NOT NULL,
  changed_by      BIGINT,
  changed_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  remarks         TEXT
);

DROP TABLE IF EXISTS t_work_record;
CREATE TABLE t_work_record (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  contract_id    BIGINT NOT NULL,
  work_month     CHAR(7) NOT NULL,
  actual_hours   DECIMAL(5,1) NOT NULL,
  billing_amount DECIMAL(12,0),
  payment_amount DECIMAL(12,0),
  status         VARCHAR(20) DEFAULT '入力中',
  remarks        VARCHAR(500),
  created_by     BIGINT,
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_work_record (contract_id, work_month)
);

DROP TABLE IF EXISTS t_invoice;
CREATE TABLE t_invoice (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_no    VARCHAR(30) NOT NULL UNIQUE,
  customer_id   BIGINT NOT NULL,
  billing_month CHAR(7) NOT NULL,
  subtotal      DECIMAL(12,0) NOT NULL,
  tax           DECIMAL(12,0) NOT NULL,
  total         DECIMAL(12,0) NOT NULL,
  status        VARCHAR(20) DEFAULT '未送付',
  issued_date   DATE,
  paid_date     DATE,
  remarks       VARCHAR(500),
  created_by    BIGINT,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT DEFAULT 0
);

DROP TABLE IF EXISTS t_invoice_item;
CREATE TABLE t_invoice_item (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  invoice_id     BIGINT NOT NULL,
  work_record_id BIGINT NOT NULL UNIQUE,
  description    VARCHAR(300),
  amount         DECIMAL(12,0) NOT NULL
);

DROP TABLE IF EXISTS t_bp_payment;
CREATE TABLE t_bp_payment (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_record_id BIGINT NOT NULL UNIQUE,
  amount         DECIMAL(12,0) NOT NULL,
  status         VARCHAR(20) DEFAULT '未払',
  paid_date      DATE,
  remarks        VARCHAR(500),
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

DROP TABLE IF EXISTS t_sales_activity;
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

DROP TABLE IF EXISTS m_menu;
CREATE TABLE m_menu (
  id         BIGINT AUTO_INCREMENT PRIMARY KEY,
  menu_key   VARCHAR(50) NOT NULL,
  menu_name  VARCHAR(100),
  path       VARCHAR(200),
  parent_id  BIGINT,
  sort_order INT DEFAULT 0
);

DROP TABLE IF EXISTS t_role_menu;
CREATE TABLE t_role_menu (
  id       BIGINT AUTO_INCREMENT PRIMARY KEY,
  role     VARCHAR(50) NOT NULL,
  menu_id  BIGINT NOT NULL
);
