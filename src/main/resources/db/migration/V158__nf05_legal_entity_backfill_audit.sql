-- NF05/NF08: 法人を推測で埋めず、確定可能な関係だけを監査付きでbackfillする。
CREATE TABLE IF NOT EXISTS t_legal_entity_backfill_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  entity_type VARCHAR(40) NOT NULL,
  entity_id BIGINT NOT NULL,
  previous_legal_entity_id BIGINT NULL,
  resolved_legal_entity_id BIGINT NULL,
  decision VARCHAR(20) NOT NULL,
  reason VARCHAR(255) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_legal_entity_backfill_audit (entity_type, entity_id, decision),
  INDEX idx_legal_entity_backfill_decision (decision, entity_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='法人境界backfillの監査記録';

-- V158時点で既に存在する取込/CRMテーブルにも同じ監査対象列を持たせる。
-- baseline済みDBで一部だけ適用済みの場合にも再実行できるよう、DDLをidempotentにする。
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_lead ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 't_lead' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_opportunity ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 't_opportunity' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_resume_ingestion ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 't_resume_ingestion' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_project_ingestion ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 't_project_ingestion' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_bp_availability ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 't_bp_availability' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_bp_availability_ingestion ADD COLUMN legal_entity_id BIGINT NULL', 'SELECT 1')
    FROM information_schema.columns WHERE table_schema = DATABASE()
    AND table_name = 't_bp_availability_ingestion' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_lead_legal_entity ON t_lead(legal_entity_id, deleted_flag, id)', 'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 't_lead' AND index_name = 'idx_lead_legal_entity');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_opportunity_legal_entity ON t_opportunity(legal_entity_id, deleted_flag, id)', 'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 't_opportunity' AND index_name = 'idx_opportunity_legal_entity');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_resume_ingestion_legal_entity ON t_resume_ingestion(legal_entity_id, deleted_flag, id)', 'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 't_resume_ingestion' AND index_name = 'idx_resume_ingestion_legal_entity');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_project_ingestion_legal_entity ON t_project_ingestion(legal_entity_id, deleted_flag, id)', 'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 't_project_ingestion' AND index_name = 'idx_project_ingestion_legal_entity');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_bp_availability_legal_entity ON t_bp_availability(legal_entity_id, deleted_flag, id)', 'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 't_bp_availability' AND index_name = 'idx_bp_availability_legal_entity');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_bp_availability_ingestion_legal_entity ON t_bp_availability_ingestion(legal_entity_id, deleted_flag, id)', 'SELECT 1')
    FROM information_schema.statistics WHERE table_schema = DATABASE()
    AND table_name = 't_bp_availability_ingestion' AND index_name = 'idx_bp_availability_ingestion_legal_entity');
PREPARE nf05_stmt FROM @nf05_sql; EXECUTE nf05_stmt; DEALLOCATE PREPARE nf05_stmt;

-- 関係先が全て同じ既知法人の場合だけ更新する。複数候補・NULLは後段のUNRESOLVED監査へ残す。
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'PROJECT', p.id, p.legal_entity_id, c.legal_entity_id, 'RESOLVED', 'customer relation has one legal entity'
FROM t_project p JOIN m_customer c ON c.id = p.customer_id
WHERE p.legal_entity_id IS NULL AND c.legal_entity_id IS NOT NULL AND p.deleted_flag = 0;
UPDATE t_project p JOIN m_customer c ON c.id = p.customer_id
SET p.legal_entity_id = c.legal_entity_id
WHERE p.legal_entity_id IS NULL AND c.legal_entity_id IS NOT NULL AND p.deleted_flag = 0;

-- engineerは組織マスタが権威sourceの場合だけ先に解決し、後続のcontract判定へ反映する。
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'ENGINEER', e.id, e.legal_entity_id, ou.legal_entity_id, 'RESOLVED', 'authoritative organization relation'
FROM t_engineer e JOIN m_organization_unit ou ON ou.id = e.organization_id
WHERE e.legal_entity_id IS NULL AND ou.legal_entity_id IS NOT NULL
  AND ou.deleted_flag = 0 AND e.deleted_flag = 0;
UPDATE t_engineer e JOIN m_organization_unit ou ON ou.id = e.organization_id
SET e.legal_entity_id = ou.legal_entity_id
WHERE e.legal_entity_id IS NULL AND ou.legal_entity_id IS NOT NULL
  AND ou.deleted_flag = 0 AND e.deleted_flag = 0;

INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'CONTRACT', c.id, c.legal_entity_id, p.legal_entity_id, 'RESOLVED', 'project engineer customer relations agree'
FROM t_contract c JOIN t_project p ON p.id = c.project_id
JOIN t_engineer e ON e.id = c.engineer_id JOIN m_customer cu ON cu.id = c.customer_id
WHERE c.legal_entity_id IS NULL AND p.legal_entity_id IS NOT NULL AND e.legal_entity_id = p.legal_entity_id
  AND cu.legal_entity_id = p.legal_entity_id AND c.deleted_flag = 0;
UPDATE t_contract c JOIN t_project p ON p.id = c.project_id
JOIN t_engineer e ON e.id = c.engineer_id JOIN m_customer cu ON cu.id = c.customer_id
SET c.legal_entity_id = p.legal_entity_id
WHERE c.legal_entity_id IS NULL AND p.legal_entity_id IS NOT NULL AND e.legal_entity_id = p.legal_entity_id
  AND cu.legal_entity_id = p.legal_entity_id AND c.deleted_flag = 0;

INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'INVOICE', i.id, i.legal_entity_id, c.legal_entity_id, 'RESOLVED', 'customer relation has one legal entity'
FROM t_invoice i JOIN m_customer c ON c.id = i.customer_id
WHERE i.legal_entity_id IS NULL AND c.legal_entity_id IS NOT NULL AND i.deleted_flag = 0;
UPDATE t_invoice i JOIN m_customer c ON c.id = i.customer_id
SET i.legal_entity_id = c.legal_entity_id
WHERE i.legal_entity_id IS NULL AND c.legal_entity_id IS NOT NULL AND i.deleted_flag = 0;

-- 既存の法人値があっても関連行と食い違う場合は、値を上書きせず不整合を監査する。
-- これらは「解決済み」に見せて公開境界へ流してはならない。
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'PROJECT', p.id, p.legal_entity_id, NULL, 'UNRESOLVED', 'project/customer legal entities disagree'
FROM t_project p JOIN m_customer c ON c.id = p.customer_id
WHERE p.deleted_flag = 0
  AND (p.legal_entity_id IS NULL OR c.legal_entity_id IS NULL
       OR p.legal_entity_id <> c.legal_entity_id);
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'CONTRACT', c.id, c.legal_entity_id, NULL, 'UNRESOLVED', 'contract relation legal entities disagree'
FROM t_contract c JOIN t_project p ON p.id = c.project_id
JOIN t_engineer e ON e.id = c.engineer_id JOIN m_customer cu ON cu.id = c.customer_id
WHERE c.deleted_flag = 0
  AND (c.legal_entity_id IS NULL OR p.legal_entity_id IS NULL OR e.legal_entity_id IS NULL
       OR cu.legal_entity_id IS NULL OR c.legal_entity_id <> p.legal_entity_id
       OR c.legal_entity_id <> e.legal_entity_id OR c.legal_entity_id <> cu.legal_entity_id);
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'INVOICE', i.id, i.legal_entity_id, NULL, 'UNRESOLVED', 'invoice/customer legal entities disagree'
FROM t_invoice i JOIN m_customer c ON c.id = i.customer_id
WHERE i.deleted_flag = 0
  AND (i.legal_entity_id IS NULL OR c.legal_entity_id IS NULL
       OR i.legal_entity_id <> c.legal_entity_id);

-- side tableは検証済みの確定先からのみ導出し、それ以外は推測しない。
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'LEAD', l.id, l.legal_entity_id, c.legal_entity_id, 'RESOLVED', 'converted customer relation'
FROM t_lead l JOIN m_customer c ON c.id = l.converted_customer_id
WHERE l.legal_entity_id IS NULL AND l.converted_customer_id IS NOT NULL
  AND c.legal_entity_id IS NOT NULL AND l.deleted_flag = 0;
UPDATE t_lead l JOIN m_customer c ON c.id = l.converted_customer_id
SET l.legal_entity_id = c.legal_entity_id
WHERE l.legal_entity_id IS NULL AND l.converted_customer_id IS NOT NULL
  AND c.legal_entity_id IS NOT NULL AND l.deleted_flag = 0;

INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'OPPORTUNITY', o.id, o.legal_entity_id, c.legal_entity_id, 'RESOLVED', 'customer relation'
FROM t_opportunity o JOIN m_customer c ON c.id = o.customer_id
WHERE o.legal_entity_id IS NULL AND c.legal_entity_id IS NOT NULL AND o.deleted_flag = 0;
UPDATE t_opportunity o JOIN m_customer c ON c.id = o.customer_id
SET o.legal_entity_id = c.legal_entity_id
WHERE o.legal_entity_id IS NULL AND c.legal_entity_id IS NOT NULL AND o.deleted_flag = 0;

INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'RESUME_INGESTION', r.id, r.legal_entity_id, e.legal_entity_id, 'RESOLVED', 'converted engineer relation'
FROM t_resume_ingestion r JOIN t_engineer e ON e.id = r.converted_engineer_id
WHERE r.legal_entity_id IS NULL AND r.converted_engineer_id IS NOT NULL
  AND e.legal_entity_id IS NOT NULL AND r.deleted_flag = 0;
UPDATE t_resume_ingestion r JOIN t_engineer e ON e.id = r.converted_engineer_id
SET r.legal_entity_id = e.legal_entity_id
WHERE r.legal_entity_id IS NULL AND r.converted_engineer_id IS NOT NULL
  AND e.legal_entity_id IS NOT NULL AND r.deleted_flag = 0;

INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'PROJECT_INGESTION', r.id, r.legal_entity_id, p.legal_entity_id, 'RESOLVED', 'converted project relation'
FROM t_project_ingestion r JOIN t_project p ON p.id = r.converted_project_id
WHERE r.legal_entity_id IS NULL AND r.converted_project_id IS NOT NULL
  AND p.legal_entity_id IS NOT NULL AND r.deleted_flag = 0;
UPDATE t_project_ingestion r JOIN t_project p ON p.id = r.converted_project_id
SET r.legal_entity_id = p.legal_entity_id
WHERE r.legal_entity_id IS NULL AND r.converted_project_id IS NOT NULL
  AND p.legal_entity_id IS NOT NULL AND r.deleted_flag = 0;

INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'BP_AVAILABILITY', b.id, b.legal_entity_id, e.legal_entity_id, 'RESOLVED', 'promoted engineer relation'
FROM t_bp_availability b JOIN t_engineer e ON e.id = b.promoted_engineer_id
WHERE b.legal_entity_id IS NULL AND b.promoted_engineer_id IS NOT NULL
  AND e.legal_entity_id IS NOT NULL AND b.deleted_flag = 0;
UPDATE t_bp_availability b JOIN t_engineer e ON e.id = b.promoted_engineer_id
SET b.legal_entity_id = e.legal_entity_id
WHERE b.legal_entity_id IS NULL AND b.promoted_engineer_id IS NOT NULL
  AND e.legal_entity_id IS NOT NULL AND b.deleted_flag = 0;

-- customerは関係だけでは一意に推測できないため、未解決を監査し公開境界で拒否する。
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'CUSTOMER', id, legal_entity_id, NULL, 'UNRESOLVED', 'no authoritative legal entity relation'
FROM m_customer WHERE legal_entity_id IS NULL;
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'ENGINEER', id, legal_entity_id, NULL, 'UNRESOLVED', 'no authoritative legal entity relation'
FROM t_engineer WHERE legal_entity_id IS NULL;
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'PROJECT', id, legal_entity_id, NULL, 'UNRESOLVED', 'relation is missing or ambiguous'
FROM t_project WHERE legal_entity_id IS NULL;
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'CONTRACT', id, legal_entity_id, NULL, 'UNRESOLVED', 'relations do not agree on one legal entity'
FROM t_contract WHERE legal_entity_id IS NULL;
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'INVOICE', id, legal_entity_id, NULL, 'UNRESOLVED', 'customer relation is missing or ambiguous'
FROM t_invoice WHERE legal_entity_id IS NULL;
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'LEAD', id, legal_entity_id, NULL, 'UNRESOLVED', 'no authoritative legal entity relation'
FROM t_lead WHERE legal_entity_id IS NULL;
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'OPPORTUNITY', id, legal_entity_id, NULL, 'UNRESOLVED', 'customer relation is missing or ambiguous'
FROM t_opportunity WHERE legal_entity_id IS NULL;
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'RESUME_INGESTION', id, legal_entity_id, NULL, 'UNRESOLVED', 'converted engineer relation is missing or ambiguous'
FROM t_resume_ingestion WHERE legal_entity_id IS NULL;
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'PROJECT_INGESTION', id, legal_entity_id, NULL, 'UNRESOLVED', 'converted project relation is missing or ambiguous'
FROM t_project_ingestion WHERE legal_entity_id IS NULL;
INSERT IGNORE INTO t_legal_entity_backfill_audit
    (entity_type, entity_id, previous_legal_entity_id, resolved_legal_entity_id, decision, reason)
SELECT 'BP_AVAILABILITY', id, legal_entity_id, NULL, 'UNRESOLVED', 'promoted engineer relation is missing or ambiguous'
FROM t_bp_availability WHERE legal_entity_id IS NULL;
