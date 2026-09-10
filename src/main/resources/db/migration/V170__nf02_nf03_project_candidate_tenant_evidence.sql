-- V170: 案件取込のtenant/CASと、候補者・履歴取込のtenant証拠衝突を不可視化する。
-- created_by と converted_engineer_id のtenantが一致しない行は推測で補正せず、
-- ownership repair queueへ監査記録を残してresolverから除外する。
ALTER TABLE t_project_ingestion
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id,
    ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER converted_project_id;

UPDATE t_project_ingestion p
JOIN sys_user u ON u.id = p.created_by AND u.deleted_flag = 0
SET p.tenant_id = u.tenant_id
WHERE p.tenant_id IS NULL AND u.tenant_id IS NOT NULL AND u.tenant_id <> '';

CREATE INDEX idx_project_ingestion_tenant_status
    ON t_project_ingestion (tenant_id, status, deleted_flag, id);

ALTER TABLE nf02_nf03_ownership_repair_queue
    ADD COLUMN conflicting_tenant_id VARCHAR(100) NULL AFTER repair_tenant_id;

CREATE INDEX idx_nf02_nf03_repair_conflict
    ON nf02_nf03_ownership_repair_queue (entity_type, conflicting_tenant_id, status, id);

-- V169のcreated_by優先回填で発生し得るtenant証拠衝突をNULL ownershipへ戻す。
UPDATE t_candidate c
JOIN sys_user u ON u.id = c.created_by AND u.deleted_flag = 0
JOIN t_engineer e ON e.id = c.converted_engineer_id AND e.deleted_flag = 0
SET c.tenant_id = NULL
WHERE c.tenant_id IS NOT NULL
  AND u.tenant_id IS NOT NULL AND u.tenant_id <> ''
  AND e.tenant_id IS NOT NULL AND e.tenant_id <> ''
  AND u.tenant_id <> e.tenant_id;

INSERT IGNORE INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, created_at, last_checked_at)
SELECT 'CANDIDATE', c.id, 'TENANT_EVIDENCE_CONFLICT', 'PENDING', NOW(), NOW()
FROM t_candidate c
JOIN sys_user u ON u.id = c.created_by AND u.deleted_flag = 0
JOIN t_engineer e ON e.id = c.converted_engineer_id AND e.deleted_flag = 0
WHERE u.tenant_id IS NOT NULL AND u.tenant_id <> ''
  AND e.tenant_id IS NOT NULL AND e.tenant_id <> ''
  AND u.tenant_id <> e.tenant_id;

UPDATE nf02_nf03_ownership_repair_queue q
JOIN t_candidate c ON q.entity_type = 'CANDIDATE' AND q.entity_id = c.id
JOIN sys_user u ON u.id = c.created_by AND u.deleted_flag = 0
JOIN t_engineer e ON e.id = c.converted_engineer_id AND e.deleted_flag = 0
SET q.conflicting_tenant_id = e.tenant_id,
    q.reason = 'TENANT_EVIDENCE_CONFLICT',
    q.evidence = CONCAT('created_by tenant=', u.tenant_id,
                        '; converted_engineer tenant=', e.tenant_id),
    q.evidence_hash = SHA2(CONCAT('CANDIDATE:', c.id, ':', u.tenant_id, ':', e.tenant_id), 256),
    q.last_checked_at = NOW()
WHERE q.entity_type = 'CANDIDATE'
  AND u.tenant_id <> e.tenant_id;

UPDATE t_resume_ingestion r
JOIN sys_user u ON u.id = r.created_by AND u.deleted_flag = 0
JOIN t_engineer e ON e.id = r.converted_engineer_id AND e.deleted_flag = 0
SET r.tenant_id = NULL
WHERE r.tenant_id IS NOT NULL
  AND u.tenant_id IS NOT NULL AND u.tenant_id <> ''
  AND e.tenant_id IS NOT NULL AND e.tenant_id <> ''
  AND u.tenant_id <> e.tenant_id;

INSERT IGNORE INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, created_at, last_checked_at)
SELECT 'RESUME_INGESTION', r.id, 'TENANT_EVIDENCE_CONFLICT', 'PENDING', NOW(), NOW()
FROM t_resume_ingestion r
JOIN sys_user u ON u.id = r.created_by AND u.deleted_flag = 0
JOIN t_engineer e ON e.id = r.converted_engineer_id AND e.deleted_flag = 0
WHERE u.tenant_id IS NOT NULL AND u.tenant_id <> ''
  AND e.tenant_id IS NOT NULL AND e.tenant_id <> ''
  AND u.tenant_id <> e.tenant_id;

UPDATE nf02_nf03_ownership_repair_queue q
JOIN t_resume_ingestion r ON q.entity_type = 'RESUME_INGESTION' AND q.entity_id = r.id
JOIN sys_user u ON u.id = r.created_by AND u.deleted_flag = 0
JOIN t_engineer e ON e.id = r.converted_engineer_id AND e.deleted_flag = 0
SET q.conflicting_tenant_id = e.tenant_id,
    q.reason = 'TENANT_EVIDENCE_CONFLICT',
    q.evidence = CONCAT('created_by tenant=', u.tenant_id,
                        '; converted_engineer tenant=', e.tenant_id),
    q.evidence_hash = SHA2(CONCAT('RESUME_INGESTION:', r.id, ':', u.tenant_id, ':', e.tenant_id), 256),
    q.last_checked_at = NOW()
WHERE q.entity_type = 'RESUME_INGESTION'
  AND u.tenant_id <> e.tenant_id;

-- 証拠不足のNULL ownershipも、静かに捨てず修復キューで可視化する。
INSERT IGNORE INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, created_at, last_checked_at)
SELECT 'PROJECT_INGESTION', p.id, 'TENANT_UNRESOLVED', 'PENDING', NOW(), NOW()
FROM t_project_ingestion p
WHERE p.tenant_id IS NULL;

INSERT IGNORE INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, created_at, last_checked_at)
SELECT 'CANDIDATE', c.id, 'TENANT_UNRESOLVED', 'PENDING', NOW(), NOW()
FROM t_candidate c
WHERE c.tenant_id IS NULL;

INSERT IGNORE INTO nf02_nf03_ownership_repair_queue
    (entity_type, entity_id, reason, status, created_at, last_checked_at)
SELECT 'RESUME_INGESTION', r.id, 'TENANT_UNRESOLVED', 'PENDING', NOW(), NOW()
FROM t_resume_ingestion r
WHERE r.tenant_id IS NULL;
