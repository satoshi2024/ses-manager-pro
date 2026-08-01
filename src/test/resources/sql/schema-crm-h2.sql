-- テスト用(冪等): V73__crm_contact_lead_opportunity.sql 相当を
-- 共有インメモリH2へ適用する。複数コンテキストで再実行されても失敗しないよう IF NOT EXISTS を用いる。

-- ============================================================
-- 1. t_customer_contact (顧客担当者)
-- ============================================================
CREATE TABLE IF NOT EXISTS t_customer_contact (
  id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
  customer_id   BIGINT       NOT NULL,
  name          VARCHAR(100) NOT NULL,
  name_kana     VARCHAR(100),
  department    VARCHAR(100),
  position      VARCHAR(100),
  roles_json    CLOB,
  email         VARCHAR(255),
  phone         VARCHAR(50),
  primary_flag  TINYINT      DEFAULT 0,
  valid_from    DATE         NOT NULL,
  valid_to      DATE,
  status        VARCHAR(20)  NOT NULL DEFAULT '有効',
  version       INT          NOT NULL DEFAULT 1,
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag  TINYINT      DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_customer_contact_customer ON t_customer_contact(customer_id);
CREATE INDEX IF NOT EXISTS idx_customer_contact_email ON t_customer_contact(email);
CREATE INDEX IF NOT EXISTS idx_customer_contact_status ON t_customer_contact(status);

-- ============================================================
-- 2. t_lead (リード)
-- ============================================================
CREATE TABLE IF NOT EXISTS t_lead (
  id                       BIGINT       AUTO_INCREMENT PRIMARY KEY,
  company_name             VARCHAR(200) NOT NULL,
  contact_name             VARCHAR(100),
  contact_email            VARCHAR(255),
  contact_phone            VARCHAR(50),
  source                   VARCHAR(100),
  owner_user_id            BIGINT,
  status                   VARCHAR(20)  NOT NULL DEFAULT '未対応',
  converted_customer_id    BIGINT,
  converted_opportunity_id BIGINT,
  version                  INT          NOT NULL DEFAULT 1,
  created_at               DATETIME     DEFAULT CURRENT_TIMESTAMP,
  updated_at               DATETIME     DEFAULT CURRENT_TIMESTAMP,
  deleted_flag             TINYINT      DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_lead_owner ON t_lead(owner_user_id);
CREATE INDEX IF NOT EXISTS idx_lead_status ON t_lead(status);
CREATE INDEX IF NOT EXISTS idx_lead_company ON t_lead(company_name);

-- ============================================================
-- 3. t_opportunity (商機)
-- ============================================================
CREATE TABLE IF NOT EXISTS t_opportunity (
  id                     BIGINT        AUTO_INCREMENT PRIMARY KEY,
  customer_id            BIGINT        NOT NULL,
  title                  VARCHAR(200)  NOT NULL,
  stage                  VARCHAR(30)   NOT NULL DEFAULT '見込',
  expected_start_month   VARCHAR(7),
  duration_months        INT,
  required_count         INT           DEFAULT 1,
  unit_price             DECIMAL(12,0),
  expected_amount        DECIMAL(14,0),
  probability            INT,
  owner_user_id          BIGINT,
  next_action_date       DATE,
  competitor             VARCHAR(500),
  lost_reason            VARCHAR(500),
  converted_project_id   BIGINT,
  converted_quotation_id BIGINT,
  version                INT           NOT NULL DEFAULT 1,
  created_at             DATETIME      DEFAULT CURRENT_TIMESTAMP,
  updated_at             DATETIME      DEFAULT CURRENT_TIMESTAMP,
  deleted_flag           TINYINT       DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_opportunity_customer ON t_opportunity(customer_id);
CREATE INDEX IF NOT EXISTS idx_opportunity_stage ON t_opportunity(stage);
CREATE INDEX IF NOT EXISTS idx_opportunity_owner ON t_opportunity(owner_user_id);

-- ============================================================
-- 4. t_sales_activity 拡張列
-- ============================================================
ALTER TABLE t_sales_activity ADD COLUMN IF NOT EXISTS contact_id BIGINT;
ALTER TABLE t_sales_activity ADD COLUMN IF NOT EXISTS opportunity_id BIGINT;
ALTER TABLE t_sales_activity ADD COLUMN IF NOT EXISTS assignee_user_id BIGINT;

-- ============================================================
-- 5. t_project / t_quotation: source_opportunity_id
-- ============================================================
ALTER TABLE t_project ADD COLUMN IF NOT EXISTS source_opportunity_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_source_opportunity ON t_project(source_opportunity_id);

ALTER TABLE t_quotation ADD COLUMN IF NOT EXISTS source_opportunity_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_quotation_source_opportunity ON t_quotation(source_opportunity_id);
