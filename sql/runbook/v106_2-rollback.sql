-- V106.2 forward repair の rollback runbook。
-- V106/V106.1のFlyway history/checksumは変更せず、V106.2が追加した
-- external_company_keyとcompany-aware UNIQUEだけをV106.1形状へ戻す。
-- company_id違いのactive行を旧identityへ戻すと情報を失うため、重複時はSIGNALして停止する。

DELIMITER $$
DROP PROCEDURE IF EXISTS v106_2_rollback$$
CREATE PROCEDURE v106_2_rollback()
BEGIN
  DECLARE duplicate_count BIGINT DEFAULT 0;

  SELECT COUNT(*) INTO duplicate_count
  FROM (
    SELECT tenant_id, COALESCE(legal_entity_id, 0) AS legal_key,
           provider, product
    FROM m_integration_connection
    WHERE deleted_flag = 0
    GROUP BY tenant_id, COALESCE(legal_entity_id, 0), provider, product
    HAVING COUNT(*) > 1
  ) duplicates;

  IF duplicate_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V106.2 rollback refused: active company rows require an explicit data decision';
  END IF;

  SET @drop_uk = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND index_name = 'uk_int_conn') > 0,
    'ALTER TABLE m_integration_connection DROP INDEX uk_int_conn',
    'SELECT 1'));
  PREPARE stmt FROM @drop_uk; EXECUTE stmt; DEALLOCATE PREPARE stmt;

  SET @drop_company_key = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'external_company_key') > 0,
    'ALTER TABLE m_integration_connection DROP COLUMN external_company_key',
    'SELECT 1'));
  PREPARE stmt FROM @drop_company_key; EXECUTE stmt; DEALLOCATE PREPARE stmt;

  SET @create_old_uk = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND index_name = 'uk_int_conn') = 0,
    'ALTER TABLE m_integration_connection ADD UNIQUE KEY uk_int_conn (tenant_id, legal_entity_key, provider, product, active_slot)',
    'SELECT 1'));
  PREPARE stmt FROM @create_old_uk; EXECUTE stmt; DEALLOCATE PREPARE stmt;
END$$
DELIMITER ;
CALL v106_2_rollback();
