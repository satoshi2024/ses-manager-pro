-- MySQLの旧V4/V5を変更せず、テスト用H2へV60の追加列だけを反映する。
ALTER TABLE t_notification ADD COLUMN IF NOT EXISTS organization_id BIGINT;
ALTER TABLE t_invoice ADD COLUMN IF NOT EXISTS cost_center_id BIGINT;
ALTER TABLE t_work_record ADD COLUMN IF NOT EXISTS organization_id BIGINT;
ALTER TABLE t_work_record ADD COLUMN IF NOT EXISTS cost_center_id BIGINT;
ALTER TABLE t_work_record ADD COLUMN IF NOT EXISTS accounting_dimension_frozen TINYINT DEFAULT 0;
ALTER TABLE t_bp_payment ADD COLUMN IF NOT EXISTS cost_center_id BIGINT;

-- V60のメニュー投入もH2へ再現する。これが無いとMenuPermissionFilterは
-- 「一致するm_menu行なし=素通し」になり、組織管理・管理会計のメニュー権限が
-- 自動テストで一切検証されないまま通ってしまう。
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'organization', '組織管理', '/organization', '/api/organizations', 24
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'organization');
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'management-accounting', '管理会計', '/management-accounting', '/api/management-accounting', 25
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'management-accounting');

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION SELECT 'HR' UNION SELECT 'マネージャー') r, m_menu m
WHERE m.menu_key = 'organization'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = m.id);
INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION SELECT 'マネージャー') r, m_menu m
WHERE m.menu_key = 'management-accounting'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = r.role AND rm.menu_id = m.id);

-- V61: 組織の親子・状態履歴と、要員の会計属性履歴。
-- 現在値だけを持つ列を過去日で参照しないための版管理テーブル。
CREATE TABLE IF NOT EXISTS t_organization_relation_history (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  organization_id BIGINT NOT NULL,
  parent_id       BIGINT,
  status          VARCHAR(20) NOT NULL,
  valid_from      DATE NOT NULL,
  valid_to        DATE,
  created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag    TINYINT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS t_engineer_accounting_history (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id         BIGINT NOT NULL,
  organization_id     BIGINT,
  organization_history_status VARCHAR(20) NOT NULL DEFAULT 'KNOWN',
  cost_center_id      BIGINT,
  expected_unit_price DECIMAL(12,0),
  valid_from          DATE NOT NULL,
  valid_to            DATE,
  created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  deleted_flag        TINYINT NOT NULL DEFAULT 0
);
