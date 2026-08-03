-- V73 CRM部分失敗の復旧Runbook（mysql clientで管理者が明示実行する）。
--
-- 重要: これは「補完してからrepair」するSQLではない。V73のfailed historyをrepairすると
-- FlywayはV73本文を再実行するため、途中まで残ったALTERを先に取り除く必要がある。
-- 本RunbookはV73が失敗した停止中DBで、V73追加ALTERだけを元に戻す。
--
-- 手順:
--   1. アプリを停止し、全量dumpとflyway_schema_historyの結果を保存する。
--   2. version=73 AND success=0 があること、失敗前はV73未適用だったことを確認する。
--   3. このSQLを実行する（V73成功済みDBでは実行禁止）。
--   4. 次のコマンドだけでFlywayのfailed行をrepairする。
--      mvn org.flywaydb:flyway-maven-plugin:repair
--   5. アプリまたはFlyway migrateを実行し、migrate後にvalidateを実行する。
--   6. INFORMATION_SCHEMAの事後確認結果とvalidate結果をdeploy記録へ保存する。
--
-- flyway_schema_historyの行削除・手動checksum書き換えは行わない。
-- t_customer_contact/t_lead/t_opportunityはV73本文がCREATE TABLE IF NOT EXISTSのため、
-- 途中失敗時に残っていても削除しない。既存データを守り、V73のINSERT ... NOT EXISTSを再実行させる。

DELIMITER $$
DROP PROCEDURE IF EXISTS crm_v73_partial_repair$$
CREATE PROCEDURE crm_v73_partial_repair()
BEGIN
  DECLARE n BIGINT DEFAULT 0;
  DECLARE sql_text TEXT;

  SELECT COUNT(*) INTO n FROM flyway_schema_history
   WHERE version = '73' AND success = 0;
  IF n <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'V73 failed history is not present; do not run this recovery SQL';
  END IF;

  -- t_sales_activity: FK -> index -> columnの順でV73途中変更を取り除く。
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name='t_sales_activity' AND constraint_name='fk_activity_contact') THEN
    ALTER TABLE t_sales_activity DROP FOREIGN KEY fk_activity_contact;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name='t_sales_activity' AND constraint_name='fk_activity_opportunity') THEN
    ALTER TABLE t_sales_activity DROP FOREIGN KEY fk_activity_opportunity;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name='t_sales_activity' AND constraint_name='fk_activity_assignee') THEN
    ALTER TABLE t_sales_activity DROP FOREIGN KEY fk_activity_assignee;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='t_sales_activity' AND index_name='idx_activity_contact') THEN
    ALTER TABLE t_sales_activity DROP INDEX idx_activity_contact;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='t_sales_activity' AND index_name='idx_activity_opportunity') THEN
    ALTER TABLE t_sales_activity DROP INDEX idx_activity_opportunity;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='t_sales_activity' AND index_name='idx_activity_assignee') THEN
    ALTER TABLE t_sales_activity DROP INDEX idx_activity_assignee;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_sales_activity' AND column_name='assignee_user_id') THEN
    ALTER TABLE t_sales_activity DROP COLUMN assignee_user_id;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_sales_activity' AND column_name='opportunity_id') THEN
    ALTER TABLE t_sales_activity DROP COLUMN opportunity_id;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_sales_activity' AND column_name='contact_id') THEN
    ALTER TABLE t_sales_activity DROP COLUMN contact_id;
  END IF;

  -- t_project/t_quotationの変換元列もV73再実行前に除去する。
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name='t_project' AND constraint_name='fk_project_source_opportunity') THEN
    ALTER TABLE t_project DROP FOREIGN KEY fk_project_source_opportunity;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='t_project' AND index_name='uk_project_source_opportunity') THEN
    ALTER TABLE t_project DROP INDEX uk_project_source_opportunity;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_project' AND column_name='source_opportunity_id') THEN
    ALTER TABLE t_project DROP COLUMN source_opportunity_id;
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND table_name='t_quotation' AND constraint_name='fk_quotation_source_opportunity') THEN
    ALTER TABLE t_quotation DROP FOREIGN KEY fk_quotation_source_opportunity;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='t_quotation' AND index_name='uk_quotation_source_opportunity') THEN
    ALTER TABLE t_quotation DROP INDEX uk_quotation_source_opportunity;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='t_quotation' AND column_name='source_opportunity_id') THEN
    ALTER TABLE t_quotation DROP COLUMN source_opportunity_id;
  END IF;
END$$
CALL crm_v73_partial_repair()$$
DROP PROCEDURE crm_v73_partial_repair$$
DELIMITER ;

-- 事後確認。ここでV73追加ALTERが0件であることを確認してからrepairする。
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
SELECT version, success, description FROM flyway_schema_history WHERE version = '73';
