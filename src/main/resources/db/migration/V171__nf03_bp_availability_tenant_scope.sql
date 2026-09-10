-- NF03 AI候補の外部要員在庫もtenant ownershipを明示する。判定不能行は修復待ちとする。
ALTER TABLE t_bp_availability
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;

UPDATE t_bp_availability a
JOIN m_bp_company c ON c.id = a.bp_company_id
SET a.tenant_id = CAST(c.tenant_id AS CHAR)
WHERE a.tenant_id IS NULL AND c.tenant_id IS NOT NULL;

INSERT IGNORE INTO nf02_nf03_ownership_repair_queue (entity_type, entity_id, reason)
SELECT 'BP_AVAILABILITY', a.id, 'TENANT_UNRESOLVED'
  FROM t_bp_availability a
 WHERE a.tenant_id IS NULL;

CREATE INDEX idx_bp_availability_tenant_population
    ON t_bp_availability (tenant_id, status, deleted_flag, id);
