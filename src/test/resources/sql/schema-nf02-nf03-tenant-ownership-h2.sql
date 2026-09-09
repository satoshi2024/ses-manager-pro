-- V163相当のH2 schema。NULL ownershipは修復対象としてresolverから除外する。
ALTER TABLE m_customer ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100);
ALTER TABLE t_engineer ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100);
ALTER TABLE t_bp_availability ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_engineer_account_link_tenant_owner
    ON t_engineer_account_link (tenant_id, engineer_id, sys_user_id);
CREATE INDEX IF NOT EXISTS idx_customer_tenant_population
    ON m_customer (tenant_id, deleted_flag, id);
CREATE INDEX IF NOT EXISTS idx_engineer_tenant_population
    ON t_engineer (tenant_id, deleted_flag, id);
CREATE INDEX IF NOT EXISTS idx_bp_availability_tenant_population
    ON t_bp_availability (tenant_id, status, deleted_flag, id);
CREATE TABLE IF NOT EXISTS nf02_nf03_ownership_repair_queue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(32) NOT NULL,
    entity_id BIGINT NOT NULL,
    reason VARCHAR(128) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    assignee_user_id BIGINT,
    repair_tenant_id VARCHAR(100),
    resolution_reason VARCHAR(500),
    evidence CLOB,
    resolved_at DATETIME,
    resolved_by BIGINT,
    last_checked_at DATETIME,
    UNIQUE (entity_type, entity_id)
);
CREATE INDEX IF NOT EXISTS idx_nf02_nf03_repair_status_age
    ON nf02_nf03_ownership_repair_queue (status, created_at, id);
