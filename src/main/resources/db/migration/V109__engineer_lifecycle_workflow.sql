-- ===================================================================
-- V109: 要員ライフサイクルワークフロー (入社・配属・異動・休職・復職・退社)
-- ===================================================================

-- 1. ライフサイクルテンプレート
CREATE TABLE IF NOT EXISTS m_lifecycle_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_type VARCHAR(30) NOT NULL COMMENT 'JOIN, ASSIGNMENT, TRANSFER, LEAVE, REINSTATEMENT, RESIGNATION',
    name VARCHAR(100) NOT NULL COMMENT 'テンプレート名',
    description TEXT NULL COMMENT '説明',
    version_no INT NOT NULL DEFAULT 1 COMMENT '版番号 (改定ごとに+1)',
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, INACTIVE',
    valid_from DATE NOT NULL COMMENT '有効開始日 (inclusive)',
    valid_to DATE NULL COMMENT '有効終了日 (inclusive, NULL=無期限)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_lifecycle_tpl_lookup (template_type, status, valid_from, valid_to),
    UNIQUE KEY uk_lifecycle_tpl_type_ver (template_type, version_no, deleted_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ライフサイクルテンプレート';

-- 2. テンプレートタスク定義
CREATE TABLE IF NOT EXISTS m_lifecycle_template_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL COMMENT 'm_lifecycle_template.id',
    task_code VARCHAR(50) NOT NULL COMMENT 'タスクコード (例: JOIN_DOC_SUBMIT, ACC_REVOKE)',
    task_name VARCHAR(100) NOT NULL COMMENT 'タスク名',
    description TEXT NULL COMMENT 'タスク説明・手順',
    relative_due_days INT NOT NULL DEFAULT 0 COMMENT '基準日からの相対日数 (-7=7日前, 0=当日, 3=3日後)',
    assignee_rule VARCHAR(30) NOT NULL COMMENT 'SPECIFIC_USER, ROLE, ORGANIZATION_MANAGER, PRIMARY_SALES, ENGINEER_SELF, APPLICANT',
    assignee_rule_value VARCHAR(100) NULL COMMENT 'ROLE名や特定UserID等の補助値',
    is_mandatory TINYINT NOT NULL DEFAULT 1 COMMENT '1: 必須, 0: 任意',
    is_blocking TINYINT NOT NULL DEFAULT 1 COMMENT '1: 案件完了を阻害, 0: 非阻害',
    evidence_type VARCHAR(30) NOT NULL DEFAULT 'NONE' COMMENT 'NONE, SELF_DECLARATION, DUAL_CONFIRMATION, DOCUMENT_LINK, SYSTEM_CHECK',
    is_engineer_visible TINYINT NOT NULL DEFAULT 1 COMMENT '1: 本人公開, 0: 内部限定',
    target_employment_types VARCHAR(100) NULL COMMENT '適用雇用形態カンマ区切り (例: 正社員,契約社員,BP / NULL=全形態)',
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_tpl_task_template (template_id, sort_order),
    UNIQUE KEY uk_tpl_task_code (template_id, task_code, deleted_flag),
    CONSTRAINT fk_tpl_task_template FOREIGN KEY (template_id) REFERENCES m_lifecycle_template(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='テンプレートタスク定義';

-- 3. テンプレートタスク依存関係 (DAG)
CREATE TABLE IF NOT EXISTS m_lifecycle_template_task_dep (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_id BIGINT NOT NULL,
    predecessor_task_code VARCHAR(50) NOT NULL COMMENT '先行タスクコード',
    successor_task_code VARCHAR(50) NOT NULL COMMENT '後続タスクコード',
    UNIQUE KEY uk_tpl_task_dep (template_id, predecessor_task_code, successor_task_code),
    CONSTRAINT fk_tpl_dep_template FOREIGN KEY (template_id) REFERENCES m_lifecycle_template(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='テンプレートタスク依存関係';

-- 4. ライフサイクル案件インスタンス
CREATE TABLE IF NOT EXISTS t_lifecycle_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_no VARCHAR(50) NOT NULL COMMENT '案件番号 (例: LC-202608-0001)',
    lifecycle_type VARCHAR(30) NOT NULL COMMENT 'JOIN, ASSIGNMENT, TRANSFER, LEAVE, REINSTATEMENT, RESIGNATION',
    engineer_id BIGINT NOT NULL COMMENT '対象要員ID',
    template_id BIGINT NOT NULL COMMENT '適用テンプレートID',
    template_version INT NOT NULL COMMENT '適用テンプレート版番号スナップショット',
    anchor_date DATE NOT NULL COMMENT '基準日 (入社日、異動日、退社日等)',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT, ACTIVE, ON_HOLD, COMPLETED, CANCELLED',
    title VARCHAR(150) NOT NULL COMMENT '案件タイトル',
    remarks TEXT NULL COMMENT '特記事項',
    applicant_user_id BIGINT NOT NULL COMMENT '起票者ユーザーID',
    engineer_snapshot_json LONGTEXT NOT NULL COMMENT '起票時点の要員・組織・営業スナップショット',
    completed_at DATETIME NULL COMMENT '案件完了日時',
    completed_by BIGINT NULL COMMENT '完了確定ユーザーID',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_lifecycle_case_no (case_no),
    INDEX idx_lifecycle_case_eng (engineer_id, lifecycle_type, status),
    INDEX idx_lifecycle_case_status (status, anchor_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ライフサイクル案件';

-- 5. ライフサイクルタスクインスタンス
CREATE TABLE IF NOT EXISTS t_lifecycle_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL COMMENT 't_lifecycle_case.id',
    task_code VARCHAR(50) NOT NULL COMMENT 'タスクコード',
    task_name VARCHAR(100) NOT NULL COMMENT 'タスク名',
    description TEXT NULL COMMENT '説明・手順',
    due_date DATE NOT NULL COMMENT '算出された期日 (anchor_date + relative_due_days)',
    assignee_user_id BIGINT NULL COMMENT '解決された担当ユーザーID (特定時)',
    assignee_role VARCHAR(30) NULL COMMENT '担当ロール (ROLE解決時)',
    assignee_name_snapshot VARCHAR(100) NULL COMMENT '担当者名スナップショット',
    is_mandatory TINYINT NOT NULL DEFAULT 1 COMMENT '1: 必須, 0: 任意',
    is_blocking TINYINT NOT NULL DEFAULT 1 COMMENT '1: 案件完了を阻害, 0: 非阻害',
    evidence_type VARCHAR(30) NOT NULL DEFAULT 'NONE' COMMENT 'NONE, SELF_DECLARATION, DUAL_CONFIRMATION, DOCUMENT_LINK, SYSTEM_CHECK',
    is_engineer_visible TINYINT NOT NULL DEFAULT 1 COMMENT '1: 本人公開, 0: 内部限定',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING, IN_PROGRESS, ON_HOLD, COMPLETED, WAIVED',
    completed_at DATETIME NULL COMMENT '完了日時',
    completed_by BIGINT NULL COMMENT '完了実行者ID',
    completion_comment TEXT NULL COMMENT '完了時コメント',
    evidence_data_json TEXT NULL COMMENT '証跡メタデータJSON',
    approval_request_id BIGINT NULL COMMENT '例外免除時の承認申請ID (ApprovalEngine連携)',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロックバージョン',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_lifecycle_task_case (case_id, status),
    INDEX idx_lifecycle_task_assignee (assignee_user_id, status, due_date),
    INDEX idx_lifecycle_task_due (due_date, status),
    CONSTRAINT fk_lifecycle_task_case FOREIGN KEY (case_id) REFERENCES t_lifecycle_case(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ライフサイクルタスク';

-- 6. タスク依存関係インスタンス
CREATE TABLE IF NOT EXISTS t_lifecycle_task_dep (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    predecessor_task_id BIGINT NOT NULL,
    successor_task_id BIGINT NOT NULL,
    UNIQUE KEY uk_task_dep (case_id, predecessor_task_id, successor_task_id),
    INDEX idx_task_dep_succ (successor_task_id),
    CONSTRAINT fk_task_dep_case FOREIGN KEY (case_id) REFERENCES t_lifecycle_case(id) ON DELETE CASCADE,
    CONSTRAINT fk_task_dep_pred FOREIGN KEY (predecessor_task_id) REFERENCES t_lifecycle_task(id) ON DELETE CASCADE,
    CONSTRAINT fk_task_dep_succ FOREIGN KEY (successor_task_id) REFERENCES t_lifecycle_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='タスク依存関係インスタンス';

-- 7. 証跡文書リンク
CREATE TABLE IF NOT EXISTS t_lifecycle_evidence_link (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL COMMENT 't_lifecycle_task.id',
    document_id BIGINT NOT NULL COMMENT 't_document.id (法定文書台帳リンク)',
    document_version_id BIGINT NULL COMMENT '文書版ID',
    verified_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    verified_by BIGINT NOT NULL,
    remarks VARCHAR(255) NULL,
    UNIQUE KEY uk_evidence_doc (task_id, document_id),
    CONSTRAINT fk_evidence_task FOREIGN KEY (task_id) REFERENCES t_lifecycle_task(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='タスク証跡文書リンク';

-- 8. ライフサイクル追記イベント台帳 (監査ログ)
CREATE TABLE IF NOT EXISTS t_lifecycle_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    case_id BIGINT NOT NULL,
    task_id BIGINT NULL,
    event_type VARCHAR(50) NOT NULL COMMENT 'CASE_CREATED, CASE_ACTIVATED, TASK_STARTED, TASK_COMPLETED, TASK_WAIVED, EXCEPTION_APPLIED, RESIGNATION_GATE_CHECKED, CASE_COMPLETED, CASE_CANCELLED',
    actor_user_id BIGINT NOT NULL,
    actor_role_snapshot VARCHAR(30) NOT NULL,
    before_state VARCHAR(30) NULL,
    after_state VARCHAR(30) NULL,
    details_json LONGTEXT NULL,
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_lifecycle_event_case (case_id, occurred_at),
    INDEX idx_lifecycle_event_task (task_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ライフサイクルイベント台帳';

-- 9. メニューseed
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'lifecycle', 'ライフサイクル管理', '/lifecycle', '/api/lifecycle', 45
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'lifecycle');

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'myLifecycle', 'マイライフサイクル', '/my/lifecycle', '/api/my/lifecycle', 86
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'myLifecycle');

-- 10. 権限付与
-- lifecycle は 管理者, HR, マネージャー, 営業 に付与
INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'lifecycle'
AND NOT EXISTS (SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id WHERE rm.role = '管理者' AND m.menu_key = 'lifecycle');

INSERT INTO t_role_menu (role, menu_id)
SELECT 'HR', id FROM m_menu WHERE menu_key = 'lifecycle'
AND NOT EXISTS (SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id WHERE rm.role = 'HR' AND m.menu_key = 'lifecycle');

INSERT INTO t_role_menu (role, menu_id)
SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'lifecycle'
AND NOT EXISTS (SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id WHERE rm.role = 'マネージャー' AND m.menu_key = 'lifecycle');

INSERT INTO t_role_menu (role, menu_id)
SELECT '営業', id FROM m_menu WHERE menu_key = 'lifecycle'
AND NOT EXISTS (SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id WHERE rm.role = '営業' AND m.menu_key = 'lifecycle');

-- myLifecycle は 要員 に付与
INSERT INTO t_role_menu (role, menu_id)
SELECT '要員', id FROM m_menu WHERE menu_key = 'myLifecycle'
AND NOT EXISTS (SELECT 1 FROM t_role_menu rm JOIN m_menu m ON m.id = rm.menu_id WHERE rm.role = '要員' AND m.menu_key = 'myLifecycle');
