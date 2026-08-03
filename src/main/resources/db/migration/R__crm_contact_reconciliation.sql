-- CRM担当者の再照合。
-- V73適用済みDBではV73本文を編集しない。contact_personがNULLでもemail/phoneだけを
-- 持つ既存顧客を、V73後のRepeatableで安全に補完する。名前は移行時の明示的な既定値とする。
DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_crm_contact_reconciliation $$
CREATE PROCEDURE __ses_crm_contact_reconciliation()
BEGIN
IF EXISTS (SELECT 1 FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 't_opportunity')
   AND EXISTS (SELECT 1 FROM flyway_schema_history
  WHERE version = '73' AND success = 1) THEN
SET @crm_stage_changed_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_opportunity' AND column_name = 'stage_changed_at');
SET @crm_sql = IF(@crm_stage_changed_exists = 0,
  'ALTER TABLE t_opportunity ADD COLUMN stage_changed_at DATETIME COMMENT ''現ステージ開始日時''', 'SELECT 1');
PREPARE crm_stmt FROM @crm_sql; EXECUTE crm_stmt; DEALLOCATE PREPARE crm_stmt;
SET @crm_lead_cost_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_lead' AND column_name = 'source_cost');
SET @crm_sql = IF(@crm_lead_cost_exists = 0,
  'ALTER TABLE t_lead ADD COLUMN source_cost DECIMAL(14,0) COMMENT ''リード獲得原価''', 'SELECT 1');
PREPARE crm_stmt FROM @crm_sql; EXECUTE crm_stmt; DEALLOCATE PREPARE crm_stmt;
SET @crm_proposal_source_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_proposal' AND column_name = 'source_opportunity_id');
SET @crm_sql = IF(@crm_proposal_source_exists = 0,
  'ALTER TABLE t_proposal ADD COLUMN source_opportunity_id BIGINT COMMENT ''商機からの提案元ID''', 'SELECT 1');
PREPARE crm_stmt FROM @crm_sql; EXECUTE crm_stmt; DEALLOCATE PREPARE crm_stmt;
SET @crm_probability_reason_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_opportunity' AND column_name = 'probability_override_reason');
SET @crm_sql = IF(@crm_probability_reason_exists = 0,
  'ALTER TABLE t_opportunity ADD COLUMN probability_override_reason VARCHAR(500) COMMENT ''確度上書き理由''', 'SELECT 1');
PREPARE crm_stmt FROM @crm_sql; EXECUTE crm_stmt; DEALLOCATE PREPARE crm_stmt;
INSERT INTO t_customer_contact
  (customer_id, name, email, phone, primary_flag, valid_from, valid_to, status, version)
SELECT
  c.id,
  COALESCE(NULLIF(TRIM(c.contact_person), ''), '顧客担当者'),
  NULLIF(TRIM(c.contact_email), ''),
  NULLIF(TRIM(c.contact_phone), ''),
  1,
  COALESCE(CAST(c.created_at AS DATE), DATE '1900-01-01'),
  NULL,
  '有効',
  1
FROM m_customer c
WHERE c.deleted_flag = 0
  AND (NULLIF(TRIM(c.contact_person), '') IS NOT NULL
       OR NULLIF(TRIM(c.contact_email), '') IS NOT NULL
       OR NULLIF(TRIM(c.contact_phone), '') IS NOT NULL)
  AND NOT EXISTS (
    SELECT 1 FROM t_customer_contact e WHERE e.customer_id = c.id
  );

-- メール履歴は文字列宛先だけにせず、CRMの接点・商機を保持できる形へ収束させる。
SET @crm_mail_contact_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_mail_delivery' AND column_name = 'contact_id');
SET @crm_sql = IF(@crm_mail_contact_exists = 0,
  'ALTER TABLE t_mail_delivery ADD COLUMN contact_id BIGINT COMMENT ''担当者ID''', 'SELECT 1');
PREPARE crm_stmt FROM @crm_sql; EXECUTE crm_stmt; DEALLOCATE PREPARE crm_stmt;
SET @crm_mail_opp_exists = (SELECT COUNT(*) FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 't_mail_delivery' AND column_name = 'opportunity_id');
SET @crm_sql = IF(@crm_mail_opp_exists = 0,
  'ALTER TABLE t_mail_delivery ADD COLUMN opportunity_id BIGINT COMMENT ''商機ID''', 'SELECT 1');
PREPARE crm_stmt FROM @crm_sql; EXECUTE crm_stmt; DEALLOCATE PREPARE crm_stmt;
SET @crm_mail_contact_index = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 't_mail_delivery' AND index_name = 'idx_mail_delivery_contact');
SET @crm_sql = IF(@crm_mail_contact_index = 0,
  'ALTER TABLE t_mail_delivery ADD INDEX idx_mail_delivery_contact (contact_id)', 'SELECT 1');
PREPARE crm_stmt FROM @crm_sql; EXECUTE crm_stmt; DEALLOCATE PREPARE crm_stmt;
SET @crm_mail_opp_index = (SELECT COUNT(*) FROM information_schema.statistics
  WHERE table_schema = DATABASE() AND table_name = 't_mail_delivery' AND index_name = 'idx_mail_delivery_opportunity');
SET @crm_sql = IF(@crm_mail_opp_index = 0,
  'ALTER TABLE t_mail_delivery ADD INDEX idx_mail_delivery_opportunity (opportunity_id)', 'SELECT 1');
PREPARE crm_stmt FROM @crm_sql; EXECUTE crm_stmt; DEALLOCATE PREPARE crm_stmt;
END IF;
END $$
CALL __ses_crm_contact_reconciliation() $$
DROP PROCEDURE __ses_crm_contact_reconciliation $$
DELIMITER ;
