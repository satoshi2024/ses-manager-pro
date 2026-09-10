-- NF09/NF10: 背景処理の監査主体、電子請求書の冪等境界、recipient snapshotを固定する。

DROP PROCEDURE IF EXISTS __ses_nf09_add_column;
DELIMITER $$
CREATE PROCEDURE __ses_nf09_add_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64), IN p_definition VARCHAR(500))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column
  ) THEN
    SET @nf09_sql = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_definition);
    PREPARE nf09_stmt FROM @nf09_sql;
    EXECUTE nf09_stmt;
    DEALLOCATE PREPARE nf09_stmt;
  END IF;
END$$
DELIMITER ;

CALL __ses_nf09_add_column('t_digital_invoice', 'actor_type', 'VARCHAR(20) NULL COMMENT ''監査主体区分''');
CALL __ses_nf09_add_column('t_digital_invoice', 'confirmation_source', 'VARCHAR(40) NULL COMMENT ''監査実行経路''');
CALL __ses_nf09_add_column('t_digital_invoice', 'human_user_id', 'BIGINT NULL COMMENT ''人間主体ID''');
CALL __ses_nf09_add_column('t_digital_invoice', 'correlation_id', 'VARCHAR(128) NULL COMMENT ''相関ID''');
CALL __ses_nf09_add_column('t_digital_invoice', 'idempotency_key', 'VARCHAR(190) NULL COMMENT ''冪等キー''');
CALL __ses_nf09_add_column('t_digital_invoice_event', 'actor_type', 'VARCHAR(20) NULL COMMENT ''監査主体区分''');
CALL __ses_nf09_add_column('t_digital_invoice_event', 'confirmation_source', 'VARCHAR(40) NULL COMMENT ''監査実行経路''');
CALL __ses_nf09_add_column('t_digital_invoice_event', 'human_user_id', 'BIGINT NULL COMMENT ''人間主体ID''');
CALL __ses_nf09_add_column('t_digital_invoice_event', 'correlation_id', 'VARCHAR(128) NULL COMMENT ''相関ID''');
CALL __ses_nf09_add_column('t_digital_invoice_event', 'idempotency_key', 'VARCHAR(190) NULL COMMENT ''冪等キー''');
CALL __ses_nf09_add_column('t_digital_invoice_event', 'canonical_payload_hash', 'VARCHAR(64) NULL COMMENT ''正規化XMLのSHA-256''');
CALL __ses_nf09_add_column('t_document', 'actor_type', 'VARCHAR(20) NULL COMMENT ''監査主体区分''');
CALL __ses_nf09_add_column('t_document', 'confirmation_source', 'VARCHAR(40) NULL COMMENT ''監査実行経路''');
CALL __ses_nf09_add_column('t_document', 'human_user_id', 'BIGINT NULL COMMENT ''人間主体ID''');
CALL __ses_nf09_add_column('t_document', 'correlation_id', 'VARCHAR(128) NULL COMMENT ''相関ID''');
CALL __ses_nf09_add_column('t_document', 'idempotency_key', 'VARCHAR(190) NULL COMMENT ''冪等キー''');
CALL __ses_nf09_add_column('t_document_version', 'actor_type', 'VARCHAR(20) NULL COMMENT ''監査主体区分''');
CALL __ses_nf09_add_column('t_document_version', 'confirmation_source', 'VARCHAR(40) NULL COMMENT ''監査実行経路''');
CALL __ses_nf09_add_column('t_document_version', 'human_user_id', 'BIGINT NULL COMMENT ''人間主体ID''');
CALL __ses_nf09_add_column('t_document_version', 'correlation_id', 'VARCHAR(128) NULL COMMENT ''相関ID''');
CALL __ses_nf09_add_column('t_document_version', 'idempotency_key', 'VARCHAR(190) NULL COMMENT ''冪等キー''');
CALL __ses_nf09_add_column('t_document_access_log', 'actor_type', 'VARCHAR(20) NULL COMMENT ''監査主体区分''');
CALL __ses_nf09_add_column('t_document_access_log', 'confirmation_source', 'VARCHAR(40) NULL COMMENT ''監査実行経路''');
CALL __ses_nf09_add_column('t_document_access_log', 'human_user_id', 'BIGINT NULL COMMENT ''人間主体ID''');
CALL __ses_nf09_add_column('t_document_access_log', 'correlation_id', 'VARCHAR(128) NULL COMMENT ''相関ID''');
CALL __ses_nf09_add_column('t_document_access_log', 'idempotency_key', 'VARCHAR(190) NULL COMMENT ''冪等キー''');
CALL __ses_nf09_add_column('t_report_run', 'recipient_preview_hash', 'VARCHAR(128) NULL COMMENT ''生成時の配布先プレビューHash''');
CALL __ses_nf09_add_column('t_report_run', 'recipient_snapshot_json', 'LONGTEXT NULL COMMENT ''生成時の配布先スナップショット''');
DROP PROCEDURE IF EXISTS __ses_nf09_add_column;

-- scheduler/provider主体は人間IDを持たないため、versionの作成者列はNULLを許容する。
ALTER TABLE t_document_version MODIFY COLUMN created_by BIGINT NULL COMMENT '登録ユーザーID（system/providerはNULL）';
ALTER TABLE t_document_access_log MODIFY COLUMN user_id BIGINT NULL COMMENT '操作ユーザーID（system/providerはNULL）';

-- 旧DBの重複・受信NULLは削除せず、元値と修復値を監査用退避表へ記録する。
-- 同一行を一度だけ退避するため、途中失敗後の再実行でも退避履歴を増やさない。
CREATE TABLE IF NOT EXISTS t_nf09_digital_invoice_provider_message_quarantine (
  id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '退避ID',
  digital_invoice_id BIGINT NOT NULL COMMENT '対象電子請求書ID',
  original_provider_message_id VARCHAR(100) NULL COMMENT '修復前provider_message_id',
  replacement_provider_message_id VARCHAR(100) NOT NULL COMMENT '修復後provider_message_id',
  reason VARCHAR(40) NOT NULL COMMENT '退避理由',
  quarantined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '退避日時',
  UNIQUE KEY uk_nf09_provider_quarantine_invoice (digital_invoice_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF09旧provider_message_id退避';

-- 同一値は最小IDを正本として残し、それ以外を監査可能な修復値へ移す。
INSERT INTO t_nf09_digital_invoice_provider_message_quarantine
    (digital_invoice_id, original_provider_message_id, replacement_provider_message_id, reason)
SELECT d.id, d.provider_message_id,
       CONCAT('LEGACY-REPAIR-', LPAD(CAST(d.id AS CHAR), 20, '0')),
       'DUPLICATE_PROVIDER_MESSAGE'
FROM t_digital_invoice d
JOIN (
    SELECT provider_message_id, MIN(id) AS survivor_id
    FROM t_digital_invoice
    WHERE provider_message_id IS NOT NULL
    GROUP BY provider_message_id
    HAVING COUNT(*) > 1
) duplicate_group
  ON duplicate_group.provider_message_id = d.provider_message_id
 AND duplicate_group.survivor_id <> d.id
LEFT JOIN t_nf09_digital_invoice_provider_message_quarantine q
  ON q.digital_invoice_id = d.id
WHERE q.id IS NULL;

-- RECEIVEのNULLは受信再送を一意に識別できないため、元のNULLを退避して明示的修復値を付与する。
INSERT INTO t_nf09_digital_invoice_provider_message_quarantine
    (digital_invoice_id, original_provider_message_id, replacement_provider_message_id, reason)
SELECT d.id, NULL,
       CONCAT('LEGACY-REPAIR-', LPAD(CAST(d.id AS CHAR), 20, '0')),
       'RECEIVE_PROVIDER_MESSAGE_NULL'
FROM t_digital_invoice d
LEFT JOIN t_nf09_digital_invoice_provider_message_quarantine q
  ON q.digital_invoice_id = d.id
WHERE d.direction = 'RECEIVE'
  AND d.provider_message_id IS NULL
  AND q.id IS NULL;

-- 既存の同一行を別の修復処理が先に占有していれば、データを上書きせず診断付きで停止する。
DROP PROCEDURE IF EXISTS __ses_nf09_repair_provider_message;
DELIMITER $$
CREATE PROCEDURE __ses_nf09_repair_provider_message()
BEGIN
  IF EXISTS (
    SELECT 1
    FROM t_nf09_digital_invoice_provider_message_quarantine q
    JOIN t_digital_invoice d
      ON d.provider_message_id = q.replacement_provider_message_id
     AND d.id <> q.digital_invoice_id
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V156 provider_message_id repair value collision';
  END IF;

  UPDATE t_digital_invoice d
  JOIN t_nf09_digital_invoice_provider_message_quarantine q
    ON q.digital_invoice_id = d.id
   AND d.provider_message_id <=> q.original_provider_message_id
  SET d.provider_message_id = q.replacement_provider_message_id;

  IF EXISTS (
    SELECT 1
    FROM t_digital_invoice
    WHERE provider_message_id IS NOT NULL
    GROUP BY provider_message_id
    HAVING COUNT(*) > 1
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V156 duplicate provider_message_id remains after repair';
  END IF;

  IF EXISTS (
    SELECT 1 FROM t_digital_invoice
    WHERE direction = 'RECEIVE' AND provider_message_id IS NULL
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'V156 RECEIVE provider_message_id NULL remains after repair';
  END IF;
END$$
DELIMITER ;

CALL __ses_nf09_repair_provider_message();
DROP PROCEDURE IF EXISTS __ses_nf09_repair_provider_message;

SET @nf09_index_sql = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 't_digital_invoice'
      AND index_name = 'uk_digital_invoice_provider_message') = 0,
  'ALTER TABLE t_digital_invoice ADD UNIQUE KEY uk_digital_invoice_provider_message (provider_message_id)',
  'SELECT 1');
PREPARE nf09_index_stmt FROM @nf09_index_sql;
EXECUTE nf09_index_stmt;
DEALLOCATE PREPARE nf09_index_stmt;

-- 受信電文だけはprovider_message_idを必須とし、送信電文のNULLは許容する。
SET @nf09_check_sql = IF(
  (SELECT COUNT(*) FROM information_schema.table_constraints
    WHERE table_schema = DATABASE() AND table_name = 't_digital_invoice'
      AND constraint_name = 'ck_digital_invoice_receive_provider_message'
      AND constraint_type = 'CHECK') = 0,
  'ALTER TABLE t_digital_invoice ADD CONSTRAINT ck_digital_invoice_receive_provider_message CHECK (direction <> ''RECEIVE'' OR provider_message_id IS NOT NULL)',
  'SELECT 1');
PREPARE nf09_check_stmt FROM @nf09_check_sql;
EXECUTE nf09_check_stmt;
DEALLOCATE PREPARE nf09_check_stmt;

-- tokenはhash検索でのみ利用するため、delivery IDをbearer tokenにしない。
SET @nf09_token_index_sql = IF(
  (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 't_report_delivery'
      AND index_name = 'idx_report_delivery_link_token_hash') = 0,
  'ALTER TABLE t_report_delivery ADD INDEX idx_report_delivery_link_token_hash (link_token_hash)',
  'SELECT 1');
PREPARE nf09_token_index_stmt FROM @nf09_token_index_sql;
EXECUTE nf09_token_index_stmt;
DEALLOCATE PREPARE nf09_token_index_stmt;

SELECT 1;
