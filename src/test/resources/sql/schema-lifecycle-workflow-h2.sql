-- ===================================================================
-- H2 Schema for Engineer Lifecycle Workflow (NF-01)
-- ===================================================================

CREATE TABLE IF NOT EXISTS m_lifecycle_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_type VARCHAR(30) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description CLOB NULL,
    version_no INT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    valid_from DATE NOT NULL,
    valid_to DATE NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT DEFAULT 0,
    CONSTRAINT uk_lifecycle_tpl_type_ver UNIQUE (template_type, version_no, deleted_flag)
);

CREATE TABLE IF NOT EXISTS m_lifecycle_template_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL,
    task_code VARCHAR(50) NOT NULL,
    task_name VARCHAR(100) NOT NULL,
    description CLOB NULL,
    relative_due_days INT NOT NULL DEFAULT 0,
    assignee_rule VARCHAR(30) NOT NULL,
    assignee_rule_value VARCHAR(100) NULL,
    is_mandatory TINYINT NOT NULL DEFAULT 1,
    is_blocking TINYINT NOT NULL DEFAULT 1,
    evidence_type VARCHAR(30) NOT NULL DEFAULT 'NONE',
    is_engineer_visible TINYINT NOT NULL DEFAULT 1,
    target_employment_types VARCHAR(100) NULL,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT DEFAULT 0,
    CONSTRAINT uk_tpl_task_code UNIQUE (template_id, task_code, deleted_flag),
    CONSTRAINT fk_tpl_task_template FOREIGN KEY (template_id) REFERENCES m_lifecycle_template(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS m_lifecycle_template_task_dep (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL,
    predecessor_task_code VARCHAR(50) NOT NULL,
    successor_task_code VARCHAR(50) NOT NULL,
    CONSTRAINT uk_tpl_task_dep UNIQUE (template_id, predecessor_task_code, successor_task_code),
    CONSTRAINT fk_tpl_dep_template FOREIGN KEY (template_id) REFERENCES m_lifecycle_template(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS t_lifecycle_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(50) NOT NULL,
    lifecycle_type VARCHAR(30) NOT NULL,
    engineer_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    template_version INT NOT NULL,
    anchor_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    title VARCHAR(150) NOT NULL,
    remarks CLOB NULL,
    applicant_user_id BIGINT NOT NULL,
    engineer_snapshot_json CLOB NOT NULL,
    completed_at DATETIME NULL,
    completed_by BIGINT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT DEFAULT 0,
    CONSTRAINT uk_lifecycle_case_no UNIQUE (case_no)
);

CREATE TABLE IF NOT EXISTS t_lifecycle_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    task_code VARCHAR(50) NOT NULL,
    task_name VARCHAR(100) NOT NULL,
    description CLOB NULL,
    due_date DATE NOT NULL,
    assignee_user_id BIGINT NULL,
    assignee_role VARCHAR(30) NULL,
    assignee_name_snapshot VARCHAR(100) NULL,
    is_mandatory TINYINT NOT NULL DEFAULT 1,
    is_blocking TINYINT NOT NULL DEFAULT 1,
    evidence_type VARCHAR(30) NOT NULL DEFAULT 'NONE',
    is_engineer_visible TINYINT NOT NULL DEFAULT 1,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    completed_at DATETIME NULL,
    completed_by BIGINT NULL,
    completion_comment CLOB NULL,
    evidence_data_json CLOB NULL,
    approval_request_id BIGINT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT DEFAULT 0,
    CONSTRAINT fk_lifecycle_task_case FOREIGN KEY (case_id) REFERENCES t_lifecycle_case(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS t_lifecycle_task_dep (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    predecessor_task_id BIGINT NOT NULL,
    successor_task_id BIGINT NOT NULL,
    CONSTRAINT uk_task_dep UNIQUE (case_id, predecessor_task_id, successor_task_id),
    CONSTRAINT fk_task_dep_case FOREIGN KEY (case_id) REFERENCES t_lifecycle_case(id) ON DELETE CASCADE,
    CONSTRAINT fk_task_dep_pred FOREIGN KEY (predecessor_task_id) REFERENCES t_lifecycle_task(id) ON DELETE CASCADE,
    CONSTRAINT fk_task_dep_succ FOREIGN KEY (successor_task_id) REFERENCES t_lifecycle_task(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS t_lifecycle_evidence_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    document_version_id BIGINT NULL,
    verified_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    verified_by BIGINT NOT NULL,
    remarks VARCHAR(255) NULL,
    CONSTRAINT uk_evidence_doc UNIQUE (task_id, document_id),
    CONSTRAINT fk_evidence_task FOREIGN KEY (task_id) REFERENCES t_lifecycle_task(id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS t_lifecycle_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    task_id BIGINT NULL,
    event_type VARCHAR(50) NOT NULL,
    actor_user_id BIGINT NOT NULL,
    actor_role_snapshot VARCHAR(30) NOT NULL,
    before_state VARCHAR(30) NULL,
    after_state VARCHAR(30) NULL,
    details_json CLOB NULL,
    occurred_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- メニューseed
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'lifecycle', 'ライフサイクル管理', '/lifecycle', '/api/lifecycle', 45
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'lifecycle');

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'myLifecycle', 'マイライフサイクル', '/my/lifecycle', '/api/my/lifecycle', 86
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'myLifecycle');

-- 権限付与
INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION SELECT 'HR' UNION SELECT 'マネージャー' UNION SELECT '営業') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'lifecycle'
AND NOT EXISTS (
    SELECT 1 FROM t_role_menu rm
    WHERE rm.role = r.role AND rm.menu_id = m.id
);

INSERT INTO t_role_menu (role, menu_id)
SELECT '要員', m.id
FROM m_menu m
WHERE m.menu_key = 'myLifecycle'
AND NOT EXISTS (
    SELECT 1 FROM t_role_menu rm
    WHERE rm.role = '要員' AND rm.menu_id = m.id
);

-- アクション権限seed (t_permission_group_action)
INSERT INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (
    SELECT 'lifecycle.*' AS action_key
    UNION ALL SELECT 'my.*' AS action_key
) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('ADMIN', 'EXECUTIVE', 'MANAGER', 'SALES', 'HR', 'ENGINEER', 'role-admin', 'role-manager', 'role-sales', 'role-hr', 'role-engineer')
  AND NOT EXISTS (
      SELECT 1 FROM t_permission_group_action pga
      WHERE pga.tenant_id = 'default' AND pga.group_id = g.id AND pga.action_key = a.action_key
  );

