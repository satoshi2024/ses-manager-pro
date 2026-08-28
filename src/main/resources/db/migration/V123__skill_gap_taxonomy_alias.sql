-- ===================================================================
-- V123: NF-03 F2-3 承認済みskill synonym map
-- ===================================================================

CREATE TABLE IF NOT EXISTS t_skill_tag_alias (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    alias_name VARCHAR(100) NOT NULL,
    normalized_alias VARCHAR(100) NOT NULL,
    canonical_skill_id BIGINT NOT NULL,
    valid_from DATE NULL,
    valid_to DATE NULL,
    approved_by BIGINT NULL,
    approved_at DATETIME NULL,
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_skill_alias_canonical (tenant_id, canonical_skill_id, valid_from, valid_to),
    UNIQUE KEY uk_skill_alias_active (tenant_id, normalized_alias, deleted_flag),
    CONSTRAINT fk_skill_alias_canonical FOREIGN KEY (canonical_skill_id) REFERENCES m_skill_tag(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='承認済みskill synonym map';
