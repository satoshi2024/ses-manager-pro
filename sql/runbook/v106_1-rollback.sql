-- V106.1 完全ロールバック runbook (mysql client / JDBC で管理者が明示実行する)。
--
-- 重要: これは「実行済みの V106.1 を元に戻し、V106 の形状へ完全復旧する」SQL である。
-- 適用対象は (a) V106.1 適用完了 DB、または (b) V106.1 が途中失敗した partial DB。
-- V106 未適用 DB (fresh V1) では実行禁止 (backup テーブル不在で SIGNAL する)。
--
-- 手順 (design.md §1.2 の厳格順序に従う):
--   1. アプリを停止し、全量 dump と flyway_schema_history の結果を保存する。
--   2. このSQLを実行する (V106.1 適用済みDBでも失敗しない冪等ガード付き)。
--   3. flyway_schema_history の version='106.1' 行を削除する (下記 CALL 内で実施)。
--   4. `mvn org.flywaydb:flyway-maven-plugin:repair` 相当で V106.1 failed history を整理する。
--   5. アプリ再起動 (Flyway migrate) で V106.1 を再適用する。再適用は差分なしで正常終了する (repair)。
--
-- 順序の意味:
--   - 新 uk_int_conn (tenant_id, legal_entity_key, provider, product, active_slot) を先に削除し、
--     重複行の復元 UPDATE 時に Duplicate Entry が発生しないようにする。
--   - バックアップ行の復元は UPDATE (PK 衝突回避のため INSERT ではなく UPDATE)。
--   - 旧 uk_int_conn (tenant_id, legal_entity_id, provider, product, deleted_flag) は未存在時のみ作成。
--   - 追加列 (connection 5列 / job 6列) は information_schema で存在判定し各列独立に DROP。
--   - バックアップテーブルは全復元・検証完了後に削除。
DELIMITER $$
DROP PROCEDURE IF EXISTS v106_1_rollback$$
CREATE PROCEDURE v106_1_rollback()
BEGIN
  DECLARE backup_count BIGINT DEFAULT 0;

  -- 事前ガード: バックアップテーブルが存在しなければ V106.1 適用前DBであり実行を拒否する
  SELECT COUNT(*) INTO backup_count
  FROM information_schema.tables
  WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection_backup_v106_1';
  IF backup_count = 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V106.1 backup table is absent; do not run this rollback SQL (fresh V106 DB)';
  END IF;

  -- 1. 新 UNIQUE インデックスの削除 (新制約を先に解除し、後続の復元時 Duplicate Entry を防止)
  SET @drop_new_uk = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND index_name = 'uk_int_conn') > 0,
    'ALTER TABLE m_integration_connection DROP INDEX uk_int_conn',
    'SELECT 1'));
  PREPARE stmt FROM @drop_new_uk; EXECUTE stmt; DEALLOCATE PREPARE stmt;

  -- 2. バックアップテーブルの退避行を UPDATE 復元 (PK 衝突回避: INSERT は使用しない)
  UPDATE m_integration_connection c
  INNER JOIN m_integration_connection_backup_v106_1 b ON c.id = b.original_id
  SET c.deleted_flag = b.deleted_flag, c.version = b.version;

  -- 3. 旧 UNIQUE インデックスの復元 (未存在時のみ)
  SET @create_old_uk = (SELECT IF(
    (SELECT COUNT(*) FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND index_name = 'uk_int_conn') = 0,
    'ALTER TABLE m_integration_connection ADD UNIQUE KEY uk_int_conn (tenant_id, legal_entity_id, provider, product, deleted_flag)',
    'SELECT 1'));
  PREPARE stmt FROM @create_old_uk; EXECUTE stmt; DEALLOCATE PREPARE stmt;

  -- 4. m_integration_connection の追加列を各列独立して存在判定し DROP
  -- 4a. active_slot
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'active_slot') > 0, 'ALTER TABLE m_integration_connection DROP COLUMN active_slot', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  -- 4b. legal_entity_key
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'legal_entity_key') > 0, 'ALTER TABLE m_integration_connection DROP COLUMN legal_entity_key', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  -- 4c. refresh_lease_expires_at
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'refresh_lease_expires_at') > 0, 'ALTER TABLE m_integration_connection DROP COLUMN refresh_lease_expires_at', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  -- 4d. refresh_lease_token
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'refresh_lease_token') > 0, 'ALTER TABLE m_integration_connection DROP COLUMN refresh_lease_token', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  -- 4e. token_version
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'm_integration_connection' AND column_name = 'token_version') > 0, 'ALTER TABLE m_integration_connection DROP COLUMN token_version', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

  -- 5. t_integration_job の追加列を各列独立して存在判定し DROP
  -- 5a. organization_id
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'organization_id') > 0, 'ALTER TABLE t_integration_job DROP COLUMN organization_id', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  -- 5b. legal_entity_id
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'legal_entity_id') > 0, 'ALTER TABLE t_integration_job DROP COLUMN legal_entity_id', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  -- 5c. tenant_id
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'tenant_id') > 0, 'ALTER TABLE t_integration_job DROP COLUMN tenant_id', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  -- 5d. lease_expires_at
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'lease_expires_at') > 0, 'ALTER TABLE t_integration_job DROP COLUMN lease_expires_at', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  -- 5e. lease_token
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'lease_token') > 0, 'ALTER TABLE t_integration_job DROP COLUMN lease_token', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;
  -- 5f. payload_snapshot
  SET @drop_col = (SELECT IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'payload_snapshot') > 0, 'ALTER TABLE t_integration_job DROP COLUMN payload_snapshot', 'SELECT 1'));
  PREPARE stmt FROM @drop_col; EXECUTE stmt; DEALLOCATE PREPARE stmt;

  -- 6. バックアップテーブルの削除 (全復元・検証完了後)
  DROP TABLE IF EXISTS m_integration_connection_backup_v106_1;

  -- 7. Flyway 失敗履歴の削除 (または flyway repair)
  DELETE FROM flyway_schema_history WHERE version = '106.1';
END$$
DELIMITER ;
CALL v106_1_rollback();
