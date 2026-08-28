-- ===================================================================
-- H2 Schema for NF-03 certification / learning / skill-gap (F1-1〜F1-5)
-- MySQL migration V115〜V119 のDDL相当（H2方言）
-- ===================================================================

-- ---- F1-1: 資格master・engineer取得record ----
CREATE TABLE IF NOT EXISTS m_certification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    issuer_key VARCHAR(200) NOT NULL,
    external_code_key VARCHAR(200) NULL,
    name_key VARCHAR(200) NOT NULL,
    identity_key VARCHAR(128) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    issuer_display VARCHAR(200) NULL,
    external_code VARCHAR(100) NULL,
    expiry_type VARCHAR(30) NOT NULL DEFAULT 'NONE',
    expiry_months INT NULL,
    rule_version INT NOT NULL DEFAULT 1,
    active_flag TINYINT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cert_tenant_identity ON m_certification(tenant_id, identity_key, deleted_flag);

CREATE TABLE IF NOT EXISTS m_certification_alias (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    certification_id BIGINT NOT NULL,
    alias_issuer_key VARCHAR(200) NULL,
    alias_name_key VARCHAR(200) NOT NULL,
    normalized_key VARCHAR(256) NOT NULL,
    valid_from DATE NULL,
    valid_to DATE NULL,
    approved_by BIGINT NULL,
    approved_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cert_alias_norm ON m_certification_alias(tenant_id, normalized_key, deleted_flag);

CREATE TABLE IF NOT EXISTS t_engineer_certification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    engineer_id BIGINT NOT NULL,
    certification_id BIGINT NOT NULL,
    continuity_group_id BIGINT NOT NULL,
    acquired_on DATE NOT NULL,
    expires_on DATE NULL,
    expiry_rule_version INT NOT NULL DEFAULT 1,
    certificate_number_encrypted VARBINARY(512) NULL,
    certificate_number_key_version VARCHAR(64) NULL,
    certificate_number_cipher_format VARCHAR(16) NULL,
    certificate_number_masked VARCHAR(64) NULL,
    record_state VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    current_flag TINYINT NOT NULL DEFAULT 0,
    current_holder_key BIGINT NULL,
    revision INT NOT NULL DEFAULT 1,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_eng_cert_current_holder
    ON t_engineer_certification(tenant_id, engineer_id, certification_id, current_holder_key);

-- ---- F1-2: 資格event・証憑文書種別 ----
CREATE TABLE IF NOT EXISTS t_certification_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    certification_record_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    supersedes_event_id BIGINT NULL,
    reason VARCHAR(2000) NULL,
    actor_user_id BIGINT NULL,
    actor_role_snapshot VARCHAR(50) NULL,
    occurred_at TIMESTAMP NOT NULL,
    effective_record_state VARCHAR(30) NULL,
    effective_acquired_on DATE NULL,
    effective_expires_on DATE NULL,
    evidence_document_id BIGINT NULL,
    evidence_document_version_id BIGINT NULL,
    evidence_document_hash VARCHAR(64) NULL,
    idempotency_key VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_cert_event_idempotency
    ON t_certification_event(tenant_id, idempotency_key);

INSERT INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
SELECT 'CERTIFICATION_EVIDENCE', '資格証憑', 'INCOMING', 7, 'TRANSACTION_DATE', 1
WHERE NOT EXISTS (SELECT 1 FROM m_document_type WHERE code = 'CERTIFICATION_EVIDENCE');

-- ---- F1-3: course・plan・enrollment ----
CREATE TABLE IF NOT EXISTS m_training_course (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    provider VARCHAR(200) NOT NULL,
    name VARCHAR(200) NOT NULL,
    description CLOB NULL,
    cost_jpy DECIMAL(12,0) NOT NULL,
    period_days INT NULL,
    capacity INT NULL,
    active_flag TINYINT NOT NULL DEFAULT 1,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_training_course_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    course_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    target_level VARCHAR(20) NULL,
    required_flag TINYINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_course_skill ON t_training_course_skill(tenant_id, course_id, skill_id, deleted_flag);

CREATE TABLE IF NOT EXISTS t_learning_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    engineer_id BIGINT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    goal_description CLOB NULL,
    attainment_criteria CLOB NOT NULL,
    planned_start_on DATE NULL,
    planned_end_on DATE NULL,
    planned_cost_jpy DECIMAL(12,0) NULL,
    expense_request_id BIGINT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    approval_request_id BIGINT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_learning_plan_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    plan_id BIGINT NOT NULL,
    source_type VARCHAR(40) NOT NULL,
    source_id BIGINT NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    amount_snapshot DECIMAL(12,0) NULL,
    actor_user_id BIGINT NULL,
    reason VARCHAR(2000) NULL,
    occurred_at TIMESTAMP NOT NULL,
    idempotency_key VARCHAR(255) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_learning_plan_event_idempotency
    ON t_learning_plan_event(tenant_id, idempotency_key);

CREATE TABLE IF NOT EXISTS t_learning_plan_skill (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    plan_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    target_level VARCHAR(20) NULL,
    target_date DATE NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_plan_skill ON t_learning_plan_skill(tenant_id, plan_id, skill_id, deleted_flag);

CREATE TABLE IF NOT EXISTS t_training_enrollment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    plan_id BIGINT NULL,
    course_id BIGINT NOT NULL,
    engineer_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PLANNED',
    started_on DATE NULL,
    completed_on DATE NULL,
    score DECIMAL(5,2) NULL,
    certificate_document_id BIGINT NULL,
    planned_cost_snapshot DECIMAL(12,0) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_training_enrollment_expense (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    enrollment_id BIGINT NOT NULL,
    expense_request_id BIGINT NOT NULL,
    relation_reason VARCHAR(200) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_enrollment_expense
    ON t_training_enrollment_expense(tenant_id, enrollment_id, expense_request_id, deleted_flag);

-- ---- F2-3: 承認済みskill synonym ----
CREATE TABLE IF NOT EXISTS t_skill_tag_alias (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    alias_name VARCHAR(100) NOT NULL,
    normalized_alias VARCHAR(100) NOT NULL,
    canonical_skill_id BIGINT NOT NULL,
    valid_from DATE NULL,
    valid_to DATE NULL,
    approved_by BIGINT NULL,
    approved_at TIMESTAMP NULL,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_alias_active
    ON t_skill_tag_alias(tenant_id, normalized_alias, deleted_flag);

-- ---- F1-4: effective history・gap snapshot ----
CREATE TABLE IF NOT EXISTS t_engineer_skill_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    engineer_id BIGINT NOT NULL,
    engineer_skill_id BIGINT NULL,
    skill_id BIGINT NOT NULL,
    proficiency VARCHAR(20) NULL,
    experience_years INT NULL,
    event_type VARCHAR(30) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    supersedes_event_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    actor_role_snapshot VARCHAR(50) NULL,
    reason VARCHAR(1000) NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_project_skill_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    project_id BIGINT NOT NULL,
    project_skill_id BIGINT NULL,
    skill_id BIGINT NOT NULL,
    required_level VARCHAR(20) NULL,
    is_must TINYINT NULL,
    event_type VARCHAR(30) NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    supersedes_event_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    actor_role_snapshot VARCHAR(50) NULL,
    reason VARCHAR(1000) NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_project_position_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    position_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL,
    position_no VARCHAR(50) NOT NULL,
    role_name VARCHAR(200) NOT NULL,
    required_count INT NOT NULL,
    skills_json CLOB NULL,
    unit_price_min DECIMAL(10,0) NULL,
    unit_price_max DECIMAL(10,0) NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    location VARCHAR(255) NULL,
    allocation_percent DECIMAL(5,2) NOT NULL,
    priority VARCHAR(20) NULL,
    status VARCHAR(20) NOT NULL,
    source_version INT NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    actor_user_id BIGINT NULL,
    actor_role_snapshot VARCHAR(50) NULL,
    reason VARCHAR(1000) NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_skill_gap_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    as_of_date DATE NOT NULL,
    engineer_id BIGINT NULL,
    project_id BIGINT NULL,
    demand_source VARCHAR(20) NULL,
    demand_version VARCHAR(64) NULL,
    supply_version VARCHAR(64) NULL,
    taxonomy_version VARCHAR(64) NULL,
    result_hash VARCHAR(64) NOT NULL,
    result_json CLOB NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL
);

-- ---- F1-5: 評価・決定監査 ----
CREATE TABLE IF NOT EXISTS t_engineer_skill_assessment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    engineer_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    assessment_type VARCHAR(20) NOT NULL,
    proposed_level VARCHAR(20) NULL,
    assessment_state VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
    effective_from DATE NULL,
    effective_to DATE NULL,
    actor_user_id BIGINT NULL,
    reason VARCHAR(2000) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_learning_decision_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    decision_domain VARCHAR(50) NOT NULL,
    source_type VARCHAR(50) NOT NULL,
    source_id BIGINT NOT NULL,
    human_actor_user_id BIGINT NOT NULL,
    adverse_use_flag TINYINT NOT NULL DEFAULT 0,
    reason VARCHAR(2000) NOT NULL,
    snapshot_hash VARCHAR(64) NULL,
    occurred_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
