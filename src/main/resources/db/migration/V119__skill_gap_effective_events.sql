-- ===================================================================
-- V119: NF-03 F1-4 supply/demand effective historyとgap snapshot
-- ===================================================================

CREATE TABLE IF NOT EXISTS t_engineer_skill_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    engineer_id BIGINT NOT NULL,
    engineer_skill_id BIGINT NULL COMMENT 'source t_engineer_skill.id',
    skill_id BIGINT NOT NULL,
    proficiency VARCHAR(20) NULL,
    experience_years INT NULL,
    event_type VARCHAR(30) NOT NULL COMMENT 'OPEN,CLOSE',
    effective_from DATE NOT NULL,
    effective_to DATE NULL COMMENT 'NULL=現在有効',
    supersedes_event_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    actor_role_snapshot VARCHAR(50) NULL,
    reason VARCHAR(1000) NULL,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_eng_skill_event_engineer (tenant_id, engineer_id, effective_from, effective_to),
    INDEX idx_eng_skill_event_skill (tenant_id, skill_id, effective_from),
    CONSTRAINT fk_eng_skill_event_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
    CONSTRAINT fk_eng_skill_event_tag FOREIGN KEY (skill_id) REFERENCES m_skill_tag(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='engineer skill effective history';

CREATE TABLE IF NOT EXISTS t_project_skill_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    project_id BIGINT NOT NULL,
    project_skill_id BIGINT NULL COMMENT 'source t_project_skill.id',
    skill_id BIGINT NOT NULL,
    required_level VARCHAR(20) NULL,
    is_must TINYINT NULL COMMENT '1=必須 0=尚可',
    event_type VARCHAR(30) NOT NULL COMMENT 'OPEN,CLOSE',
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    supersedes_event_id BIGINT NULL,
    actor_user_id BIGINT NULL,
    actor_role_snapshot VARCHAR(50) NULL,
    reason VARCHAR(1000) NULL,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_proj_skill_event_project (tenant_id, project_id, effective_from, effective_to),
    INDEX idx_proj_skill_event_skill (tenant_id, skill_id, effective_from),
    CONSTRAINT fk_proj_skill_event_project FOREIGN KEY (project_id) REFERENCES t_project(id),
    CONSTRAINT fk_proj_skill_event_tag FOREIGN KEY (skill_id) REFERENCES m_skill_tag(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='project skill effective history';

CREATE TABLE IF NOT EXISTS t_project_position_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    position_id BIGINT NOT NULL,
    project_id BIGINT NOT NULL,
    event_type VARCHAR(30) NOT NULL COMMENT 'CREATE,UPDATE,STATUS_CHANGE,DELETE',
    position_no VARCHAR(50) NOT NULL,
    role_name VARCHAR(200) NOT NULL,
    required_count INT NOT NULL,
    skills_json TEXT NULL,
    unit_price_min DECIMAL(10,0) NULL,
    unit_price_max DECIMAL(10,0) NULL,
    start_date DATE NULL,
    end_date DATE NULL,
    location VARCHAR(255) NULL,
    allocation_percent DECIMAL(5,2) NOT NULL,
    priority VARCHAR(20) NULL,
    status VARCHAR(20) NOT NULL,
    source_version INT NOT NULL COMMENT 't_project_position.version snapshot',
    effective_from DATE NOT NULL,
    effective_to DATE NULL,
    actor_user_id BIGINT NULL,
    actor_role_snapshot VARCHAR(50) NULL,
    reason VARCHAR(1000) NULL,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_position_event_position (tenant_id, position_id, effective_from, effective_to),
    INDEX idx_position_event_project (tenant_id, project_id, effective_from),
    CONSTRAINT fk_position_event_project FOREIGN KEY (project_id) REFERENCES t_project(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='staffing position as-of snapshot';

CREATE TABLE IF NOT EXISTS t_skill_gap_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    as_of_date DATE NOT NULL,
    engineer_id BIGINT NULL,
    project_id BIGINT NULL,
    demand_source VARCHAR(20) NULL COMMENT 'PROJECT,POSITION,COMBINED',
    demand_version VARCHAR(64) NULL,
    supply_version VARCHAR(64) NULL,
    taxonomy_version VARCHAR(64) NULL,
    result_hash VARCHAR(64) NOT NULL,
    result_json LONGTEXT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    INDEX idx_skill_gap_snapshot_asof (tenant_id, as_of_date, engineer_id, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='skill gap再現用immutable snapshot';
