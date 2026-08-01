-- V73 CRM部分適用の復旧Runbook（mysql clientで管理者が明示実行する）。
--
-- 前提:
--   * V73/V74は適用済みmigrationなので編集しない。
--   * まず INFORMATION_SCHEMA の確認を行い、対象DB名・バックアップ・停止時間を記録する。
--   * V73が未適用のDBへこのRunbookを実行してはいけない。
--   * 完了後に Flyway validate、必要な場合だけ許可リスト確認後に flyway repair を実行する。
--     flyway_schema_history の行削除・手動checksum書き換えは行わない。
--
-- このSQLはDDLの途中失敗（列だけ存在、index/FKだけ欠落）を各要素単位で収束させる。
-- MySQL 8にADD COLUMN IF NOT EXISTSはないため、各ADDはinformation_schemaで判定する。

DELIMITER $$
DROP PROCEDURE IF EXISTS crm_v73_partial_repair$$
CREATE PROCEDURE crm_v73_partial_repair()
BEGIN
  DECLARE n BIGINT DEFAULT 0;
  DECLARE sql_text TEXT;

  SELECT COUNT(*) INTO n FROM information_schema.tables
   WHERE table_schema = DATABASE() AND table_name IN ('t_customer_contact','t_lead','t_opportunity');
  IF n <> 3 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V73 CRM table is missing; do not run partial repair on an un-applied database';
  END IF;

  SELECT COUNT(*) INTO n FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 't_sales_activity' AND column_name = 'contact_id';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_sales_activity ADD COLUMN contact_id BIGINT COMMENT ''担当者ID'' AFTER customer_id';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
  SELECT COUNT(*) INTO n FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 't_sales_activity' AND column_name = 'opportunity_id';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_sales_activity ADD COLUMN opportunity_id BIGINT COMMENT ''商機ID'' AFTER contact_id';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
  SELECT COUNT(*) INTO n FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 't_sales_activity' AND column_name = 'assignee_user_id';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_sales_activity ADD COLUMN assignee_user_id BIGINT COMMENT ''担当営業ID'' AFTER completed_flag';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;

  SELECT COUNT(*) INTO n FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 't_sales_activity' AND index_name = 'idx_activity_contact';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_sales_activity ADD INDEX idx_activity_contact (contact_id)';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
  SELECT COUNT(*) INTO n FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 't_sales_activity' AND index_name = 'idx_activity_opportunity';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_sales_activity ADD INDEX idx_activity_opportunity (opportunity_id)';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
  SELECT COUNT(*) INTO n FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 't_sales_activity' AND index_name = 'idx_activity_assignee';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_sales_activity ADD INDEX idx_activity_assignee (assignee_user_id)';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;

  SELECT COUNT(*) INTO n FROM information_schema.table_constraints
   WHERE table_schema = DATABASE() AND table_name = 't_sales_activity' AND constraint_name = 'fk_activity_contact';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_sales_activity ADD CONSTRAINT fk_activity_contact FOREIGN KEY (contact_id) REFERENCES t_customer_contact(id) ON UPDATE CASCADE ON DELETE SET NULL';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
  SELECT COUNT(*) INTO n FROM information_schema.table_constraints
   WHERE table_schema = DATABASE() AND table_name = 't_sales_activity' AND constraint_name = 'fk_activity_opportunity';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_sales_activity ADD CONSTRAINT fk_activity_opportunity FOREIGN KEY (opportunity_id) REFERENCES t_opportunity(id) ON UPDATE CASCADE ON DELETE SET NULL';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
  SELECT COUNT(*) INTO n FROM information_schema.table_constraints
   WHERE table_schema = DATABASE() AND table_name = 't_sales_activity' AND constraint_name = 'fk_activity_assignee';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_sales_activity ADD CONSTRAINT fk_activity_assignee FOREIGN KEY (assignee_user_id) REFERENCES sys_user(id) ON UPDATE CASCADE ON DELETE SET NULL';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;

  SELECT COUNT(*) INTO n FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 't_project' AND column_name = 'source_opportunity_id';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_project ADD COLUMN source_opportunity_id BIGINT COMMENT ''商機からの変換元ID'' AFTER remarks';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
  SELECT COUNT(*) INTO n FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 't_project' AND index_name = 'uk_project_source_opportunity';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_project ADD UNIQUE INDEX uk_project_source_opportunity (source_opportunity_id)';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;

  SELECT COUNT(*) INTO n FROM information_schema.columns
   WHERE table_schema = DATABASE() AND table_name = 't_quotation' AND column_name = 'source_opportunity_id';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_quotation ADD COLUMN source_opportunity_id BIGINT COMMENT ''商機からの変換元ID'' AFTER remarks';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
  SELECT COUNT(*) INTO n FROM information_schema.statistics
   WHERE table_schema = DATABASE() AND table_name = 't_quotation' AND index_name = 'uk_quotation_source_opportunity';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_quotation ADD UNIQUE INDEX uk_quotation_source_opportunity (source_opportunity_id)';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;

  SELECT COUNT(*) INTO n FROM information_schema.table_constraints
   WHERE table_schema = DATABASE() AND table_name = 't_project' AND constraint_name = 'fk_project_source_opportunity';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_project ADD CONSTRAINT fk_project_source_opportunity FOREIGN KEY (source_opportunity_id) REFERENCES t_opportunity(id) ON UPDATE CASCADE ON DELETE SET NULL';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;
  SELECT COUNT(*) INTO n FROM information_schema.table_constraints
   WHERE table_schema = DATABASE() AND table_name = 't_quotation' AND constraint_name = 'fk_quotation_source_opportunity';
  IF n = 0 THEN
    SET sql_text = 'ALTER TABLE t_quotation ADD CONSTRAINT fk_quotation_source_opportunity FOREIGN KEY (source_opportunity_id) REFERENCES t_opportunity(id) ON UPDATE CASCADE ON DELETE SET NULL';
    SET @crm_sql = sql_text; PREPARE s FROM @crm_sql; EXECUTE s; DEALLOCATE PREPARE s;
  END IF;

  INSERT INTO t_customer_contact
    (customer_id, name, email, phone, primary_flag, valid_from, valid_to, status, version)
  SELECT c.id, COALESCE(NULLIF(TRIM(c.contact_person), ''), '顧客担当者'),
         NULLIF(TRIM(c.contact_email), ''), NULLIF(TRIM(c.contact_phone), ''),
         1, COALESCE(CAST(c.created_at AS DATE), DATE '1900-01-01'), NULL, '有効', 1
    FROM m_customer c
   WHERE c.deleted_flag = 0
     AND (NULLIF(TRIM(c.contact_person), '') IS NOT NULL
          OR NULLIF(TRIM(c.contact_email), '') IS NOT NULL
          OR NULLIF(TRIM(c.contact_phone), '') IS NOT NULL)
     AND NOT EXISTS (SELECT 1 FROM t_customer_contact e WHERE e.customer_id = c.id);
END$$
CALL crm_v73_partial_repair()$$
DROP PROCEDURE crm_v73_partial_repair$$
DELIMITER ;

-- 事後確認（必ず実行結果を保存する）。
SELECT table_name, column_name FROM information_schema.columns
 WHERE table_schema = DATABASE()
   AND ((table_name = 't_sales_activity' AND column_name IN ('contact_id','opportunity_id','assignee_user_id'))
     OR (table_name IN ('t_project','t_quotation') AND column_name = 'source_opportunity_id'))
 ORDER BY table_name, column_name;
SELECT table_name, index_name FROM information_schema.statistics
 WHERE table_schema = DATABASE()
   AND index_name IN ('idx_activity_contact','idx_activity_opportunity','idx_activity_assignee',
                      'uk_project_source_opportunity','uk_quotation_source_opportunity')
 GROUP BY table_name, index_name ORDER BY table_name, index_name;
SELECT COUNT(*) AS crm_contact_rows FROM t_customer_contact;
