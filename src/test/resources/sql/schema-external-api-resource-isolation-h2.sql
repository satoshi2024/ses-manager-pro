-- NF-05 H2 counterpart of V151. H2 schema-locations are replayed by several contexts.
ALTER TABLE m_customer ADD COLUMN IF NOT EXISTS legal_entity_id BIGINT;
ALTER TABLE t_engineer ADD COLUMN IF NOT EXISTS legal_entity_id BIGINT;
ALTER TABLE t_project ADD COLUMN IF NOT EXISTS legal_entity_id BIGINT;
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS legal_entity_id BIGINT;
ALTER TABLE t_invoice ADD COLUMN IF NOT EXISTS legal_entity_id BIGINT;

CREATE INDEX IF NOT EXISTS idx_customer_legal_entity ON m_customer (legal_entity_id, id);
CREATE INDEX IF NOT EXISTS idx_engineer_legal_entity ON t_engineer (legal_entity_id, id);
CREATE INDEX IF NOT EXISTS idx_project_legal_entity_customer ON t_project (legal_entity_id, customer_id, id);
CREATE INDEX IF NOT EXISTS idx_contract_legal_entity_relation
    ON t_contract (legal_entity_id, project_id, engineer_id, customer_id, id);
CREATE INDEX IF NOT EXISTS idx_invoice_legal_entity_customer ON t_invoice (legal_entity_id, customer_id, id);
CREATE TABLE IF NOT EXISTS t_legal_entity_backfill_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(40) NOT NULL,
    entity_id BIGINT NOT NULL,
    previous_legal_entity_id BIGINT,
    resolved_legal_entity_id BIGINT,
    decision VARCHAR(20) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_legal_entity_backfill_audit UNIQUE (entity_type, entity_id, decision)
);

UPDATE m_customer SET legal_entity_id = 1 WHERE legal_entity_id IS NULL;
UPDATE t_engineer SET legal_entity_id = 1 WHERE legal_entity_id IS NULL;
UPDATE t_project SET legal_entity_id = 1 WHERE legal_entity_id IS NULL;
UPDATE t_contract SET legal_entity_id = 1 WHERE legal_entity_id IS NULL;
UPDATE t_invoice SET legal_entity_id = 1 WHERE legal_entity_id IS NULL;
