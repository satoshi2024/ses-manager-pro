-- CRM Round 6 forward fix
-- V74.2は予約済みV75(S07 approval)より前に適用し、V74.1適用済みDBも含めて
-- source_costと重複候補検索キーの最終形を収束させる。

-- 金額単位は円（整数）に統一する。V74.1で小数列になった既存値は
-- 小数円を四捨五入してから型を収束させる（既存V74.1は編集しない）。
UPDATE t_lead
   SET source_cost = ROUND(source_cost, 0)
 WHERE source_cost IS NOT NULL;

ALTER TABLE t_lead
  MODIFY COLUMN source_cost DECIMAL(14,0) NULL COMMENT 'リード獲得原価';

SET @crm_review_key_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_lead' AND column_name = 'company_name_normalized') = 0,
    'ALTER TABLE t_lead ADD COLUMN company_name_normalized VARCHAR(200) NULL AFTER company_name',
    'SELECT 1'
);
PREPARE crm_review_key_stmt FROM @crm_review_key_sql;
EXECUTE crm_review_key_stmt;
DEALLOCATE PREPARE crm_review_key_stmt;

SET @crm_review_key_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_lead' AND column_name = 'contact_email_normalized') = 0,
    'ALTER TABLE t_lead ADD COLUMN contact_email_normalized VARCHAR(255) NULL AFTER contact_email',
    'SELECT 1'
);
PREPARE crm_review_key_stmt FROM @crm_review_key_sql;
EXECUTE crm_review_key_stmt;
DEALLOCATE PREPARE crm_review_key_stmt;

SET @crm_review_key_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_lead' AND column_name = 'contact_phone_normalized') = 0,
    'ALTER TABLE t_lead ADD COLUMN contact_phone_normalized VARCHAR(50) NULL AFTER contact_phone',
    'SELECT 1'
);
PREPARE crm_review_key_stmt FROM @crm_review_key_sql;
EXECUTE crm_review_key_stmt;
DEALLOCATE PREPARE crm_review_key_stmt;

UPDATE t_lead
   SET company_name_normalized = REGEXP_REPLACE(LOWER(TRIM(company_name)), '[[:space:]]+', ''),
       contact_email_normalized = CASE WHEN contact_email IS NULL THEN NULL
           ELSE REGEXP_REPLACE(LOWER(TRIM(contact_email)), '[^a-z0-9+@.]', '') END,
       contact_phone_normalized = CASE WHEN contact_phone IS NULL THEN NULL
           ELSE REGEXP_REPLACE(LOWER(TRIM(contact_phone)), '[^a-z0-9+@.]', '') END;

SET @crm_review_key_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 't_lead' AND index_name = 'idx_lead_company_normalized') = 0,
    'CREATE INDEX idx_lead_company_normalized ON t_lead (company_name_normalized)',
    'SELECT 1'
);
PREPARE crm_review_key_stmt FROM @crm_review_key_sql;
EXECUTE crm_review_key_stmt;
DEALLOCATE PREPARE crm_review_key_stmt;

SET @crm_review_key_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 't_lead' AND index_name = 'idx_lead_email_normalized') = 0,
    'CREATE INDEX idx_lead_email_normalized ON t_lead (contact_email_normalized)',
    'SELECT 1'
);
PREPARE crm_review_key_stmt FROM @crm_review_key_sql;
EXECUTE crm_review_key_stmt;
DEALLOCATE PREPARE crm_review_key_stmt;

SET @crm_review_key_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 't_lead' AND index_name = 'idx_lead_phone_normalized') = 0,
    'CREATE INDEX idx_lead_phone_normalized ON t_lead (contact_phone_normalized)',
    'SELECT 1'
);
PREPARE crm_review_key_stmt FROM @crm_review_key_sql;
EXECUTE crm_review_key_stmt;
DEALLOCATE PREPARE crm_review_key_stmt;
