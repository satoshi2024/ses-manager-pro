-- 候補者H2 schema。共有mem DBで再実行されるため、必ず冪等に定義する。
CREATE TABLE IF NOT EXISTS t_candidate (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id VARCHAR(100),
  name VARCHAR(100) NOT NULL,
  contact_email VARCHAR(200),
  contact_phone VARCHAR(20),
  skill_summary VARCHAR(1000),
  desired_rate DECIMAL(10,0),
  source VARCHAR(50),
  current_stage VARCHAR(20) NOT NULL DEFAULT '応募受付',
  next_action_date DATE,
  converted_engineer_id BIGINT,
  version INT NOT NULL DEFAULT 0,
  remarks VARCHAR(1000),
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  created_by BIGINT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_candidate_current_stage ON t_candidate (current_stage);
CREATE INDEX IF NOT EXISTS idx_candidate_next_action ON t_candidate (next_action_date);
CREATE INDEX IF NOT EXISTS idx_candidate_tenant_stage ON t_candidate (tenant_id, current_stage, deleted_flag, id);

CREATE TABLE IF NOT EXISTS t_candidate_activity (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  candidate_id BIGINT NOT NULL,
  stage VARCHAR(20) NOT NULL,
  reason VARCHAR(500),
  changed_by BIGINT,
  changed_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  remarks VARCHAR(500)
);
CREATE INDEX IF NOT EXISTS idx_candidate_activity_candidate ON t_candidate_activity (candidate_id, changed_at);

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'candidate', '候補者管理', '/candidate', '/api/candidates', 67
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'candidate');
INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', m.id FROM m_menu m
WHERE m.menu_key = 'candidate'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = '管理者' AND rm.menu_id = m.id);
INSERT INTO t_role_menu (role, menu_id)
SELECT '営業', m.id FROM m_menu m
WHERE m.menu_key = 'candidate'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = '営業' AND rm.menu_id = m.id);
INSERT INTO t_role_menu (role, menu_id)
SELECT 'HR', m.id FROM m_menu m
WHERE m.menu_key = 'candidate'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu rm WHERE rm.role = 'HR' AND rm.menu_id = m.id);
