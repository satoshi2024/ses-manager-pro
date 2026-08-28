-- ===================================================================
-- V119: NF-03 F1-5 評価proposal・人の確定監査DDL
-- ===================================================================

CREATE TABLE IF NOT EXISTS t_engineer_skill_assessment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    engineer_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    assessment_type VARCHAR(20) NOT NULL COMMENT 'SELF,MANAGER,HR_FINAL',
    proposed_level VARCHAR(20) NULL COMMENT '初級/中級/上級',
    assessment_state VARCHAR(30) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT,SUBMITTED,ACCEPTED,REJECTED,SUPERSEDED',
    effective_from DATE NULL,
    effective_to DATE NULL,
    actor_user_id BIGINT NULL,
    reason VARCHAR(2000) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_skill_assessment_engineer (tenant_id, engineer_id, assessment_type, deleted_flag),
    INDEX idx_skill_assessment_skill (tenant_id, skill_id, assessment_type, deleted_flag),
    CONSTRAINT fk_skill_assessment_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
    CONSTRAINT fk_skill_assessment_tag FOREIGN KEY (skill_id) REFERENCES m_skill_tag(id),
    CONSTRAINT chk_skill_assessment_type CHECK (assessment_type IN ('SELF','MANAGER','HR_FINAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='本人/上長/HR skill評価';

CREATE TABLE IF NOT EXISTS t_learning_decision_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    decision_domain VARCHAR(50) NOT NULL COMMENT 'SKILL,PLACEMENT,LEARNING,...',
    source_type VARCHAR(50) NOT NULL COMMENT 'AI_CANDIDATE,ASSESSMENT,...',
    source_id BIGINT NOT NULL,
    human_actor_user_id BIGINT NOT NULL,
    adverse_use_flag TINYINT NOT NULL DEFAULT 0,
    reason VARCHAR(2000) NOT NULL,
    snapshot_hash VARCHAR(64) NULL,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_learning_decision_domain (tenant_id, decision_domain, occurred_at),
    INDEX idx_learning_decision_source (tenant_id, source_type, source_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='人の確定・利用目的監査event';
