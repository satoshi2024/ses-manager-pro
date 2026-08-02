-- テスト用(冪等): V73__crm_contact_lead_opportunity.sql の DDL 相当を共有インメモリH2へ適用する。
-- MySQL固有DDL(JSON型 / ENGINE / COLLATE / VIRTUAL)はH2方言へ読み替える
-- （platform-invariants §4.3: MySQL migrationをH2 replayリストへ直接足さない）。
-- 複数コンテキストで再実行されても失敗しないよう IF NOT EXISTS を用いる。
--
-- **外部キーは張らない**（schema-bp-company-h2.sql / schema-productivity-h2.sql 等と同じ方針）。
-- 共有インメモリH2は2つ目のcontextでschema-locationsを再実行するため、CRM側からFKを張ると
-- V1の `DROP TABLE IF EXISTS t_project` が参照制約で失敗し、全 @SpringBootTest が起動できなくなる。
-- 参照整合性(FK)の検証は実MySQLのfresh/legacy smokeが担当する。
--
-- V73の §6 移行DML は本ファイルへ写経しない。写経すると「V73の実SQL」ではなく
-- 「H2用に書き直した別のSQL」を検証することになるため、移行DMLは
-- CustomerContactSchemaTest がV73ファイル本体から抽出して実行する。

-- ============================================================
-- 1. t_customer_contact (顧客担当者)
-- ============================================================
-- DB_CLOSE_DELAY=-1 の共有H2で別テストが engineer-schema-h2.sql を先に適用していても、
-- CRM replayが常に同じ定義へ戻るようCRM 3表だけ再生成する。
DROP TABLE IF EXISTS t_customer_contact CASCADE;
DROP TABLE IF EXISTS t_lead CASCADE;
DROP TABLE IF EXISTS t_opportunity CASCADE;

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
  deleted_flag  TINYINT      DEFAULT 0,
  -- MySQL側は VIRTUAL 生成列。H2は VIRTUAL キーワードを持たないため GENERATED ALWAYS AS のみ。
  active_primary_customer_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN primary_flag = 1 AND valid_to IS NULL AND status = '有効' AND deleted_flag = 0
         THEN customer_id ELSE NULL END
  ),
  CONSTRAINT uk_customer_contact_active_primary UNIQUE (active_primary_customer_id)
);

CREATE INDEX IF NOT EXISTS idx_customer_contact_customer ON t_customer_contact(customer_id);
CREATE INDEX IF NOT EXISTS idx_customer_contact_email ON t_customer_contact(email);
CREATE INDEX IF NOT EXISTS idx_customer_contact_status ON t_customer_contact(status);
CREATE INDEX IF NOT EXISTS idx_customer_contact_valid ON t_customer_contact(customer_id, valid_from, valid_to);

-- ============================================================
-- 2. t_lead (リード)
-- ============================================================
CREATE TABLE IF NOT EXISTS t_lead (
  id                       BIGINT       AUTO_INCREMENT PRIMARY KEY,
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
CREATE INDEX IF NOT EXISTS idx_lead_email ON t_lead(contact_email);

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
  probability_override_reason VARCHAR(500),
  stage_changed_at       DATETIME,
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
CREATE INDEX IF NOT EXISTS idx_opportunity_next_action ON t_opportunity(next_action_date);

-- ============================================================
-- 4. t_sales_activity 拡張列
-- ============================================================
ALTER TABLE t_sales_activity ADD COLUMN IF NOT EXISTS contact_id BIGINT;
ALTER TABLE t_sales_activity ADD COLUMN IF NOT EXISTS opportunity_id BIGINT;
ALTER TABLE t_sales_activity ADD COLUMN IF NOT EXISTS assignee_user_id BIGINT;
ALTER TABLE t_sales_activity ADD COLUMN IF NOT EXISTS version INT DEFAULT 1;
CREATE INDEX IF NOT EXISTS idx_activity_contact ON t_sales_activity(contact_id);
CREATE INDEX IF NOT EXISTS idx_activity_opportunity ON t_sales_activity(opportunity_id);
CREATE INDEX IF NOT EXISTS idx_activity_assignee ON t_sales_activity(assignee_user_id);

-- ============================================================
-- 5. t_project / t_quotation: source_opportunity_id (UNIQUEで冪等変換)
-- ============================================================
ALTER TABLE t_project ADD COLUMN IF NOT EXISTS source_opportunity_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_project_source_opportunity ON t_project(source_opportunity_id);

ALTER TABLE t_quotation ADD COLUMN IF NOT EXISTS source_opportunity_id BIGINT;
CREATE UNIQUE INDEX IF NOT EXISTS uk_quotation_source_opportunity ON t_quotation(source_opportunity_id);

-- t_proposal: 商機起点の提案をforecastから除外するための変換元。
ALTER TABLE t_proposal ADD COLUMN IF NOT EXISTS source_opportunity_id BIGINT;
