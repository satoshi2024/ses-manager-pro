-- V177: SLA通知で参照する契約のtenant ownershipを正本化する。
-- tenantを特定できないlegacy契約はdefaultへ補完せず、NULLのまま修復キューへ残して不可視化する。
ALTER TABLE t_contract
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;

UPDATE t_contract c
JOIN m_customer mc ON mc.id = c.customer_id
SET c.tenant_id = mc.tenant_id
WHERE c.tenant_id IS NULL
  AND mc.tenant_id IS NOT NULL AND mc.tenant_id <> '';

-- 契約と顧客の証拠が衝突する行は、どちらかを採用せずownershipを未解決へ戻す。
UPDATE t_contract c
JOIN m_customer mc ON mc.id = c.customer_id
SET c.tenant_id = NULL
WHERE c.tenant_id IS NOT NULL
  AND mc.tenant_id IS NOT NULL AND mc.tenant_id <> ''
  AND c.tenant_id <> mc.tenant_id;

INSERT IGNORE INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, created_at, last_checked_at)
SELECT 'CONTRACT', c.id, 'TENANT_UNRESOLVED', 'PENDING', NOW(), NOW()
FROM t_contract c
WHERE c.tenant_id IS NULL;

CREATE INDEX idx_contract_tenant_customer_status_sales
    ON t_contract (tenant_id, customer_id, status, sales_user_id, deleted_flag, id);
