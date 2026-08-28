-- ===================================================================
-- V118: NF-03 F1-3 course・learning plan・enrollment DDL
-- ===================================================================

CREATE TABLE IF NOT EXISTS m_training_course (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    provider VARCHAR(200) NOT NULL COMMENT '提供元',
    name VARCHAR(200) NOT NULL COMMENT 'コース名',
    description TEXT NULL,
    cost_jpy DECIMAL(12,0) NOT NULL COMMENT '税込JPY',
    period_days INT NULL COMMENT '期間（日）',
    capacity INT NULL COMMENT '定員（NULL=無制限）',
    active_flag TINYINT NOT NULL DEFAULT 1,
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_training_course_active (tenant_id, active_flag, deleted_flag),
    CONSTRAINT chk_training_course_cost CHECK (cost_jpy >= 0),
    CONSTRAINT chk_training_course_capacity CHECK (capacity IS NULL OR capacity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='研修コースcatalog';

CREATE TABLE IF NOT EXISTS t_training_course_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    course_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL COMMENT 'm_skill_tag.id',
    target_level VARCHAR(20) NULL COMMENT '初級/中級/上級',
    required_flag TINYINT NOT NULL DEFAULT 0 COMMENT '1=必須skill',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_course_skill (tenant_id, course_id, skill_id, deleted_flag),
    INDEX idx_course_skill_skill (skill_id, deleted_flag),
    CONSTRAINT fk_course_skill_course FOREIGN KEY (course_id) REFERENCES m_training_course(id),
    CONSTRAINT fk_course_skill_tag FOREIGN KEY (skill_id) REFERENCES m_skill_tag(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='コース対象skill';

CREATE TABLE IF NOT EXISTS t_learning_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    engineer_id BIGINT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    goal_description TEXT NULL,
    attainment_criteria TEXT NOT NULL COMMENT '達成基準（必須）',
    planned_start_on DATE NULL,
    planned_end_on DATE NULL,
    planned_cost_jpy DECIMAL(12,0) NULL COMMENT '申請時見積snapshot',
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT,SUBMITTED,APPROVED,REJECTED,IN_PROGRESS,COMPLETED,CANCELLED',
    approval_request_id BIGINT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_learning_plan_engineer (tenant_id, engineer_id, status, deleted_flag),
    CONSTRAINT fk_learning_plan_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学習計画';

CREATE TABLE IF NOT EXISTS t_learning_plan_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    plan_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    target_level VARCHAR(20) NULL,
    target_date DATE NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_plan_skill (tenant_id, plan_id, skill_id, deleted_flag),
    CONSTRAINT fk_plan_skill_plan FOREIGN KEY (plan_id) REFERENCES t_learning_plan(id),
    CONSTRAINT fk_plan_skill_tag FOREIGN KEY (skill_id) REFERENCES m_skill_tag(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='学習計画目標skill';

CREATE TABLE IF NOT EXISTS t_training_enrollment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    plan_id BIGINT NULL COMMENT 't_learning_plan.id',
    course_id BIGINT NOT NULL,
    engineer_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PLANNED' COMMENT 'PLANNED,STARTED,COMPLETED,CANCELLED',
    started_on DATE NULL,
    completed_on DATE NULL,
    score DECIMAL(5,2) NULL,
    certificate_document_id BIGINT NULL COMMENT 't_document.id',
    planned_cost_snapshot DECIMAL(12,0) NULL COMMENT '申込時planned cost snapshot',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_enrollment_engineer (tenant_id, engineer_id, status, deleted_flag),
    INDEX idx_enrollment_plan (plan_id, deleted_flag),
    CONSTRAINT fk_enrollment_plan FOREIGN KEY (plan_id) REFERENCES t_learning_plan(id),
    CONSTRAINT fk_enrollment_course FOREIGN KEY (course_id) REFERENCES m_training_course(id),
    CONSTRAINT fk_enrollment_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='研修受講record';

CREATE TABLE IF NOT EXISTS t_training_enrollment_expense (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    enrollment_id BIGINT NOT NULL,
    expense_request_id BIGINT NOT NULL COMMENT 't_expense_request.id（金額正本）',
    relation_reason VARCHAR(200) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_enrollment_expense (tenant_id, enrollment_id, expense_request_id, deleted_flag),
    INDEX idx_enrollment_expense_request (expense_request_id, deleted_flag),
    CONSTRAINT fk_enroll_expense_enrollment FOREIGN KEY (enrollment_id) REFERENCES t_training_enrollment(id),
    CONSTRAINT fk_enroll_expense_request FOREIGN KEY (expense_request_id) REFERENCES t_expense_request(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='受講と経費の関連';
