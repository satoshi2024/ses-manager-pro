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
    conflicting_tenant_id VARCHAR(100),
    resolution_reason VARCHAR(500),
    evidence CLOB,
    resolved_at DATETIME,
    resolved_by BIGINT,
    last_checked_at DATETIME,
    version INT NOT NULL DEFAULT 0,
    claim_token VARCHAR(100),
    claimed_by BIGINT,
    claimed_at DATETIME,
    incident_id BIGINT,
    actor_tenant_id VARCHAR(100),
    evidence_hash VARCHAR(64),
    approver_id BIGINT,
    UNIQUE (entity_type, entity_id)
);
CREATE INDEX IF NOT EXISTS idx_nf02_nf03_repair_status_age
    ON nf02_nf03_ownership_repair_queue (status, created_at, id);
CREATE INDEX IF NOT EXISTS idx_nf02_nf03_repair_claim
    ON nf02_nf03_ownership_repair_queue (status, version, claim_token, id);
CREATE INDEX IF NOT EXISTS idx_nf02_nf03_repair_conflict
    ON nf02_nf03_ownership_repair_queue (entity_type, conflicting_tenant_id, status, id);

ALTER TABLE t_candidate ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100);
ALTER TABLE t_candidate ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_candidate_tenant_stage
    ON t_candidate (tenant_id, current_stage, deleted_flag, id);
ALTER TABLE t_project ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;
CREATE INDEX IF NOT EXISTS idx_project_customer_version
    ON t_project (customer_id, version, deleted_flag, id);

-- V171相当。契約tenant不明行はNULLのままSLA通知母集団から除外する。
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_contract_tenant_customer_status_sales
    ON t_contract (tenant_id, customer_id, status, sales_user_id, deleted_flag, id);

-- V172相当。契約の要員・案件・顧客・営業ownership監査を高速化する。
-- 不明・衝突行はtenantを推測せず、resolverから不可視のまま修復キューで扱う。
CREATE INDEX IF NOT EXISTS idx_contract_tenant_reference
    ON t_contract (tenant_id, customer_id, engineer_id, project_id, sales_user_id, deleted_flag, id);
CREATE INDEX IF NOT EXISTS idx_user_org_tenant_owner
    ON t_user_organization (tenant_id, user_id, organization_id, deleted_flag, id);
