-- V151: 資格取得renew継続グループ（continuity group）の永続化と整合性制約

CREATE TABLE IF NOT EXISTS t_certification_continuity_group (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    engineer_id BIGINT NOT NULL,
    certification_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_cert_continuity_group_ident (id, tenant_id, engineer_id, certification_id),
    INDEX idx_cert_continuity_group_eng (tenant_id, engineer_id, certification_id),
    CONSTRAINT fk_cert_continuity_group_eng FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
    CONSTRAINT fk_cert_continuity_group_cert FOREIGN KEY (certification_id) REFERENCES m_certification(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資格取得renew継続グループ';

-- 既存レコードから continuity group をバックフィル
INSERT IGNORE INTO t_certification_continuity_group (id, tenant_id, engineer_id, certification_id, created_at, updated_at, created_by, deleted_flag)
SELECT continuity_group_id, tenant_id, engineer_id, certification_id, MIN(created_at), MIN(updated_at), MIN(created_by), 0
FROM t_engineer_certification
GROUP BY continuity_group_id, tenant_id, engineer_id, certification_id;

-- 外部キー制約と current holder 整合性制約を追加
ALTER TABLE t_engineer_certification
    ADD CONSTRAINT fk_eng_cert_continuity_group
    FOREIGN KEY (continuity_group_id, tenant_id, engineer_id, certification_id)
    REFERENCES t_certification_continuity_group (id, tenant_id, engineer_id, certification_id);

ALTER TABLE t_engineer_certification
    ADD CONSTRAINT chk_eng_cert_current_holder
    CHECK ((current_flag = 1 AND current_holder_key IS NOT NULL AND current_holder_key = continuity_group_id) OR (current_flag = 0 AND current_holder_key IS NULL));
