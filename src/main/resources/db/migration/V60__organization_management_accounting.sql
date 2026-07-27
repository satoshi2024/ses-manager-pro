-- ============================================================
-- 組織・管理会計基盤（V60）
-- V1統合baselineにも同じ最終形を定義し、V58以前から更新するDBには
-- IF NOT EXISTSで追加する。V59は永久欠番であり、作成しない。
-- ============================================================

CREATE TABLE IF NOT EXISTS m_organization_unit (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       BIGINT,
  legal_entity_id BIGINT,
  code            VARCHAR(50) NOT NULL,
  name            VARCHAR(200) NOT NULL,
  type            VARCHAR(20) NOT NULL,
  parent_id       BIGINT,
  valid_from      DATE NOT NULL,
  valid_to        DATE,
  status          VARCHAR(20) NOT NULL DEFAULT '有効',
  merged_into     BIGINT,
  version         INT NOT NULL DEFAULT 0,
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag    TINYINT NOT NULL DEFAULT 0,
  INDEX idx_org_parent (parent_id),
  INDEX idx_org_legal_entity_period (legal_entity_id, valid_from, valid_to),
  INDEX idx_org_status (status),
  INDEX idx_org_merged_into (merged_into),
  CONSTRAINT fk_org_parent FOREIGN KEY (parent_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_user_organization (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id         BIGINT NOT NULL,
  organization_id BIGINT NOT NULL,
  position_name   VARCHAR(100),
  manager_user_id BIGINT,
  primary_flag    TINYINT NOT NULL DEFAULT 0,
  valid_from      DATE NOT NULL,
  valid_to        DATE,
  version         INT NOT NULL DEFAULT 0,
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag    TINYINT NOT NULL DEFAULT 0,
  INDEX idx_user_org_user_period (user_id, valid_from, valid_to),
  INDEX idx_user_org_organization (organization_id),
  INDEX idx_user_org_manager (manager_user_id),
  CONSTRAINT fk_user_org_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_user_org_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_user_org_manager FOREIGN KEY (manager_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS m_cost_center (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  legal_entity_id BIGINT,
  code            VARCHAR(50) NOT NULL,
  name            VARCHAR(200) NOT NULL,
  organization_id BIGINT,
  valid_from      DATE NOT NULL,
  valid_to        DATE,
  status          VARCHAR(20) NOT NULL DEFAULT '有効',
  version         INT NOT NULL DEFAULT 0,
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag    TINYINT NOT NULL DEFAULT 0,
  INDEX idx_cost_center_org (organization_id),
  INDEX idx_cost_center_legal_period (legal_entity_id, valid_from, valid_to),
  CONSTRAINT fk_cost_center_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_management_budget (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id   BIGINT NOT NULL,
  cost_center_id    BIGINT,
  budget_month      DATE NOT NULL,
  revenue           DECIMAL(15,0) NOT NULL DEFAULT 0,
  gross_profit      DECIMAL(15,0) NOT NULL DEFAULT 0,
  utilization_count INT NOT NULL DEFAULT 0,
  hire_count        INT NOT NULL DEFAULT 0,
  version           INT NOT NULL DEFAULT 0,
  created_at        DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at        DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag      TINYINT NOT NULL DEFAULT 0,
  cost_center_key BIGINT GENERATED ALWAYS AS (COALESCE(cost_center_id, 0)) STORED,
  UNIQUE KEY uk_management_budget (organization_id, cost_center_key, budget_month),
  INDEX idx_management_budget_month (budget_month),
  CONSTRAINT fk_management_budget_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_management_budget_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_monthly_accounting_dimension (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_month      DATE NOT NULL,
  source_type     VARCHAR(50) NOT NULL,
  source_id       BIGINT NOT NULL,
  organization_id BIGINT,
  cost_center_id  BIGINT,
  sales_user_id   BIGINT,
  revenue         DECIMAL(15,0) NOT NULL DEFAULT 0,
  cost            DECIMAL(15,0) NOT NULL DEFAULT 0,
  snapshot_at     DATETIME NOT NULL,
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_monthly_accounting_source (work_month, source_type, source_id),
  INDEX idx_monthly_accounting_org (work_month, organization_id),
  INDEX idx_monthly_accounting_cost_center (work_month, cost_center_id),
  CONSTRAINT fk_monthly_accounting_org FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_monthly_accounting_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_monthly_accounting_sales_user FOREIGN KEY (sales_user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- legacy DBでは所属テーブルにversionがないため、versionを使うbackfillより先に補う。
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_user_organization ADD COLUMN version INT NOT NULL DEFAULT 0',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_user_organization' AND column_name = 'version');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- V60以前に組織所属を持たない既存ユーザーを無所属にしないための移行所属。
-- 実組織が確定した後に個別の所属履歴へ置換する。管理者はscope対象外のため作成しない。
INSERT INTO m_organization_unit
  (tenant_id, legal_entity_id, code, name, type, parent_id, valid_from, valid_to, status, version, deleted_flag)
SELECT NULL, NULL, 'LEGACY', '既存データ移行組織', '事業部', NULL, '1970-01-01', NULL, '有効', 0, 0
WHERE NOT EXISTS (
  SELECT 1 FROM m_organization_unit WHERE code = 'LEGACY' AND deleted_flag = 0
);
INSERT INTO t_user_organization
  (user_id, organization_id, position_name, manager_user_id, primary_flag, valid_from, valid_to, version, deleted_flag)
SELECT u.id, o.id, NULL, NULL, 1, '1970-01-01', NULL, 0, 0
FROM sys_user u
JOIN m_organization_unit o ON o.code = 'LEGACY' AND o.deleted_flag = 0
WHERE u.role <> '管理者'
  AND u.status = 1
  AND u.deleted_flag = 0
  AND NOT EXISTS (
    SELECT 1 FROM t_user_organization existing
    WHERE existing.user_id = u.id AND existing.deleted_flag = 0
  );

-- 組織管理メニュー。既存V58 DBにも同じV60で投入し、V59は作成しない。
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('organization', '組織管理', '/organization', '/api/organizations', 24);
INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'organization';
INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT 'HR', id FROM m_menu WHERE menu_key = 'organization';
INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'organization';
INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('management-accounting', '管理会計', '/management-accounting', '/api/management-accounting', 25);
INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'management-accounting';
INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'management-accounting';

-- V1の旧baselineで既に存在するテーブルにも列を追加する。DDLはMySQL 8でのみ実行される。
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE m_organization_unit ADD COLUMN merged_into BIGINT NULL, ADD INDEX idx_org_merged_into (merged_into)',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'm_organization_unit' AND column_name = 'merged_into');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_engineer ADD COLUMN cost_center_id BIGINT NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_engineer' AND column_name = 'cost_center_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_contract ADD COLUMN cost_center_id BIGINT NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_contract' AND column_name = 'cost_center_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_invoice ADD COLUMN cost_center_id BIGINT NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_invoice' AND column_name = 'cost_center_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_bp_payment ADD COLUMN cost_center_id BIGINT NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_bp_payment' AND column_name = 'cost_center_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_management_budget ADD COLUMN cost_center_key BIGINT GENERATED ALWAYS AS (COALESCE(cost_center_id, 0)) STORED',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_management_budget' AND column_name = 'cost_center_key');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
-- 旧形状に同名Indexがない場合はDROPせず追加し、旧Indexが別キーなら置換する。
SET @sql = (SELECT CASE
  WHEN COUNT(*) = 0 THEN
    'ALTER TABLE t_management_budget ADD UNIQUE KEY uk_management_budget (organization_id, cost_center_key, budget_month)'
  WHEN SUM(column_name = 'cost_center_key') = 0 THEN
    'ALTER TABLE t_management_budget DROP INDEX uk_management_budget, ADD UNIQUE KEY uk_management_budget (organization_id, cost_center_key, budget_month)'
  ELSE 'SELECT 1' END
  FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 't_management_budget' AND index_name = 'uk_management_budget');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_notification ADD COLUMN organization_id BIGINT NULL, ADD INDEX idx_notification_organization (organization_id)',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_notification' AND column_name = 'organization_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_engineer ADD CONSTRAINT fk_engineer_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id) ON UPDATE CASCADE ON DELETE SET NULL',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_engineer' AND constraint_name = 'fk_engineer_cost_center');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_contract ADD CONSTRAINT fk_contract_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id) ON UPDATE CASCADE ON DELETE SET NULL',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_contract' AND constraint_name = 'fk_contract_cost_center');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_invoice ADD CONSTRAINT fk_invoice_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id) ON UPDATE CASCADE ON DELETE SET NULL',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_invoice' AND constraint_name = 'fk_invoice_cost_center');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_bp_payment ADD CONSTRAINT fk_bp_payment_cost_center FOREIGN KEY (cost_center_id) REFERENCES m_cost_center(id) ON UPDATE CASCADE ON DELETE SET NULL',
  'SELECT 1') FROM information_schema.table_constraints WHERE constraint_schema = DATABASE() AND table_name = 't_bp_payment' AND constraint_name = 'fk_bp_payment_cost_center');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
