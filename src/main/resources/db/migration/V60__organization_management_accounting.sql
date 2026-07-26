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
  version         INT NOT NULL DEFAULT 0,
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag    TINYINT NOT NULL DEFAULT 0,
  INDEX idx_org_parent (parent_id),
  INDEX idx_org_legal_entity_period (legal_entity_id, valid_from, valid_to),
  INDEX idx_org_status (status),
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
  UNIQUE KEY uk_management_budget (organization_id, cost_center_id, budget_month),
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
