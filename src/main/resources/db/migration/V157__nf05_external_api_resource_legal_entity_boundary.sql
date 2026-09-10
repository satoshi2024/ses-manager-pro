-- NF-05: 公開read対象の業務リソースへ法人境界を追加する。
-- V1/V5が統合ベーススキーマのため、列と索引はそれぞれのCREATE TABLEへ折り込み済み。
-- baseline済みの既存DBだけが未適用になり得るため、information_schemaで存在を確認してから
-- 固定SQLを一度だけ実行する。空DBの逐次replayではすべてSELECT 1となり重複を起こさない。
SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE m_customer ADD COLUMN legal_entity_id BIGINT NULL COMMENT ''法人境界（公開API）''',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'm_customer' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql;
EXECUTE nf05_stmt;
DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_engineer ADD COLUMN legal_entity_id BIGINT NULL COMMENT ''法人境界（公開API）''',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_engineer' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql;
EXECUTE nf05_stmt;
DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_project ADD COLUMN legal_entity_id BIGINT NULL COMMENT ''法人境界（公開API）''',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_project' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql;
EXECUTE nf05_stmt;
DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_contract ADD COLUMN legal_entity_id BIGINT NULL COMMENT ''法人境界（公開API）''',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_contract' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql;
EXECUTE nf05_stmt;
DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_invoice ADD COLUMN legal_entity_id BIGINT NULL COMMENT ''法人境界（公開API）''',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_invoice' AND column_name = 'legal_entity_id');
PREPARE nf05_stmt FROM @nf05_sql;
EXECUTE nf05_stmt;
DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_customer_legal_entity ON m_customer (legal_entity_id, id)',
    'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'm_customer' AND index_name = 'idx_customer_legal_entity');
PREPARE nf05_stmt FROM @nf05_sql;
EXECUTE nf05_stmt;
DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_engineer_legal_entity ON t_engineer (legal_entity_id, id)',
    'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 't_engineer' AND index_name = 'idx_engineer_legal_entity');
PREPARE nf05_stmt FROM @nf05_sql;
EXECUTE nf05_stmt;
DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_project_legal_entity_customer ON t_project (legal_entity_id, customer_id, id)',
    'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 't_project' AND index_name = 'idx_project_legal_entity_customer');
PREPARE nf05_stmt FROM @nf05_sql;
EXECUTE nf05_stmt;
DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_contract_legal_entity_relation ON t_contract (legal_entity_id, project_id, engineer_id, customer_id, id)',
    'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 't_contract' AND index_name = 'idx_contract_legal_entity_relation');
PREPARE nf05_stmt FROM @nf05_sql;
EXECUTE nf05_stmt;
DEALLOCATE PREPARE nf05_stmt;

SET @nf05_sql = (SELECT IF(COUNT(*) = 0,
    'CREATE INDEX idx_invoice_legal_entity_customer ON t_invoice (legal_entity_id, customer_id, id)',
    'SELECT 1')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 't_invoice' AND index_name = 'idx_invoice_legal_entity_customer');
PREPARE nf05_stmt FROM @nf05_sql;
EXECUTE nf05_stmt;
DEALLOCATE PREPARE nf05_stmt;
