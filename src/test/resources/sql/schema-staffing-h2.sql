-- テスト用(冪等): V103__staffing_capacity_planning.sql のDDL相当を共有インメモリH2へ適用する。
-- MySQL固有DDL(ENGINE/COLLATE/COMMENT)はH2方言へ読み替える（platform-invariants §4.3）。
-- 共有H2は複数contextでschema-locationsを再実行するため、冪等に再構築する。

-- ---- proposal/contractへposition_id ----
ALTER TABLE t_proposal ADD COLUMN IF NOT EXISTS position_id BIGINT;
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS position_id BIGINT;

-- ---- 1) 案件ポジション ----
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

-- ---- 2) 配置計画 ----
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

-- ---- 3) 仮配置scenario ----
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

-- ---- 4) scenario内の仮配置（日単位・datesはISO日付のJSON配列） ----
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
