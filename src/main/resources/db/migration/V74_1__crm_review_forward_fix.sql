-- CRM Round 5 review forward fix
-- V74.1 is intentionally placed before the reserved S07 approval migration V75.
SET @crm_review_fix_sql = 'SELECT 1';

SET @crm_review_fix_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_sales_activity' AND column_name = 'version') = 0,
    'ALTER TABLE t_sales_activity ADD COLUMN version INT NOT NULL DEFAULT 1 AFTER completed_flag',
    'SELECT 1'
);
PREPARE crm_review_fix_stmt FROM @crm_review_fix_sql;
EXECUTE crm_review_fix_stmt;
DEALLOCATE PREPARE crm_review_fix_stmt;

SET @crm_review_fix_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_opportunity' AND column_name = 'stage_changed_at') = 0,
    'ALTER TABLE t_opportunity ADD COLUMN stage_changed_at DATETIME NULL',
    'SELECT 1'
);
PREPARE crm_review_fix_stmt FROM @crm_review_fix_sql;
EXECUTE crm_review_fix_stmt;
DEALLOCATE PREPARE crm_review_fix_stmt;

SET @crm_review_fix_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_lead' AND column_name = 'source_cost') = 0,
    'ALTER TABLE t_lead ADD COLUMN source_cost DECIMAL(15,2) NULL',
    'SELECT 1'
);
PREPARE crm_review_fix_stmt FROM @crm_review_fix_sql;
EXECUTE crm_review_fix_stmt;
DEALLOCATE PREPARE crm_review_fix_stmt;

SET @crm_review_fix_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_proposal' AND column_name = 'source_opportunity_id') = 0,
    'ALTER TABLE t_proposal ADD COLUMN source_opportunity_id BIGINT NULL',
    'SELECT 1'
);
PREPARE crm_review_fix_stmt FROM @crm_review_fix_sql;
EXECUTE crm_review_fix_stmt;
DEALLOCATE PREPARE crm_review_fix_stmt;

SET @crm_review_fix_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_opportunity' AND column_name = 'probability_override_reason') = 0,
    'ALTER TABLE t_opportunity ADD COLUMN probability_override_reason VARCHAR(500) NULL',
    'SELECT 1'
);
PREPARE crm_review_fix_stmt FROM @crm_review_fix_sql;
EXECUTE crm_review_fix_stmt;
DEALLOCATE PREPARE crm_review_fix_stmt;

INSERT INTO t_customer_contact (
    customer_id, name, email, phone, primary_flag, valid_from, valid_to, status, version
)
SELECT c.id, COALESCE(NULLIF(TRIM(c.contact_person), ''), '顧客担当者'),
       NULLIF(TRIM(c.contact_email), ''), NULLIF(TRIM(c.contact_phone), ''), 1,
       COALESCE(CAST(c.created_at AS DATE), DATE '1900-01-01'), NULL, '有効', 1
  FROM m_customer c
 WHERE c.deleted_flag = 0
   AND (NULLIF(TRIM(c.contact_person), '') IS NOT NULL
     OR NULLIF(TRIM(c.contact_email), '') IS NOT NULL
     OR NULLIF(TRIM(c.contact_phone), '') IS NOT NULL)
   AND NOT EXISTS (
       SELECT 1 FROM t_customer_contact cc
        WHERE cc.customer_id = c.id AND cc.deleted_flag = 0
   );

SET @crm_review_fix_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_mail_delivery' AND column_name = 'contact_id') = 0,
    'ALTER TABLE t_mail_delivery ADD COLUMN contact_id BIGINT NULL',
    'SELECT 1'
);
PREPARE crm_review_fix_stmt FROM @crm_review_fix_sql;
EXECUTE crm_review_fix_stmt;
DEALLOCATE PREPARE crm_review_fix_stmt;

SET @crm_review_fix_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_mail_delivery' AND column_name = 'opportunity_id') = 0,
    'ALTER TABLE t_mail_delivery ADD COLUMN opportunity_id BIGINT NULL',
    'SELECT 1'
);
PREPARE crm_review_fix_stmt FROM @crm_review_fix_sql;
EXECUTE crm_review_fix_stmt;
DEALLOCATE PREPARE crm_review_fix_stmt;

SET @crm_review_fix_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 't_mail_delivery' AND index_name = 'idx_mail_delivery_contact') = 0,
    'CREATE INDEX idx_mail_delivery_contact ON t_mail_delivery (contact_id)',
    'SELECT 1'
);
PREPARE crm_review_fix_stmt FROM @crm_review_fix_sql;
EXECUTE crm_review_fix_stmt;
DEALLOCATE PREPARE crm_review_fix_stmt;

SET @crm_review_fix_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 't_mail_delivery' AND index_name = 'idx_mail_delivery_opportunity') = 0,
    'CREATE INDEX idx_mail_delivery_opportunity ON t_mail_delivery (opportunity_id)',
    'SELECT 1'
);
PREPARE crm_review_fix_stmt FROM @crm_review_fix_sql;
EXECUTE crm_review_fix_stmt;
DEALLOCATE PREPARE crm_review_fix_stmt;

DROP PROCEDURE IF EXISTS crm_review_fix_noop;
