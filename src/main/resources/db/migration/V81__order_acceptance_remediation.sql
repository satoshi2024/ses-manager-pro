-- ============================================================
-- SES Manager Pro - 注文・検収ワークフロー 補正マイグレーション
-- MySQL 8.0+
-- ファイル: V81__order_acceptance_remediation.sql
-- 説明: R10 Review指摘事項（INDEX/FK3分岐修復、DB CHECK制約、文書Hash Claim）の順方向補正
-- ============================================================

-- ------------------------------------------------------------
-- 1. INDEX / FK 3-Way 構造修復 (R09-P1-01)
-- ------------------------------------------------------------

-- uk_contract_order_line / fk_contract_order_line の構造検証と3分岐修復
SET @uk_status = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 't_contract' AND index_name = 'uk_contract_order_line') = 0,
    'MISSING',
    IF(
        (SELECT COUNT(*) FROM information_schema.statistics
          WHERE table_schema = DATABASE() AND table_name = 't_contract' AND index_name = 'uk_contract_order_line' AND non_unique = 0 AND seq_in_index = 1 AND column_name = 'order_line_id') = 1
        AND
        (SELECT COUNT(*) FROM information_schema.statistics
          WHERE table_schema = DATABASE() AND table_name = 't_contract' AND index_name = 'uk_contract_order_line') = 1,
        'CORRECT',
        'WRONG'
    )
);

SET @fk_status = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 't_contract' AND constraint_name = 'fk_contract_order_line') = 0,
    'MISSING',
    IF(
        (SELECT COUNT(*) FROM information_schema.key_column_usage
          WHERE table_schema = DATABASE() AND table_name = 't_contract' AND constraint_name = 'fk_contract_order_line' AND column_name = 'order_line_id' AND referenced_table_name = 't_sales_order_line' AND referenced_column_name = 'id') = 1,
        'CORRECT',
        'WRONG'
    )
);

-- FKが存在し、かつFKまたはUKがWRONGの場合は、先にFKをドロップしてUKインデックスの依存ロックを解除する
SET @fk_drop_sql = IF((@fk_status = 'WRONG' OR @uk_status = 'WRONG') AND (SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema = DATABASE() AND table_name = 't_contract' AND constraint_name = 'fk_contract_order_line') > 0,
    'ALTER TABLE t_contract DROP FOREIGN KEY fk_contract_order_line',
    'SELECT 1'
);
PREPARE fk_drop_stmt FROM @fk_drop_sql; EXECUTE fk_drop_stmt; DEALLOCATE PREPARE fk_drop_stmt;

-- UKのドロップと再構築
SET @uk_drop_sql = IF(@uk_status = 'WRONG', 'ALTER TABLE t_contract DROP INDEX uk_contract_order_line', 'SELECT 1');
PREPARE uk_drop_stmt FROM @uk_drop_sql; EXECUTE uk_drop_stmt; DEALLOCATE PREPARE uk_drop_stmt;

SET @uk_add_sql = IF(@uk_status = 'MISSING' OR @uk_status = 'WRONG',
    'ALTER TABLE t_contract ADD UNIQUE KEY uk_contract_order_line (order_line_id)',
    'SELECT 1'
);
PREPARE uk_add_stmt FROM @uk_add_sql; EXECUTE uk_add_stmt; DEALLOCATE PREPARE uk_add_stmt;

-- FKの再構築（MISSING、WRONG、またはUK再構築に伴い一時ドロップした場合）
SET @fk_add_sql = IF(@fk_status = 'MISSING' OR @fk_status = 'WRONG' OR @uk_status = 'WRONG',
    'ALTER TABLE t_contract ADD CONSTRAINT fk_contract_order_line FOREIGN KEY (order_line_id) REFERENCES t_sales_order_line(id) ON UPDATE CASCADE ON DELETE SET NULL',
    'SELECT 1'
);
PREPARE fk_add_stmt FROM @fk_add_sql; EXECUTE fk_add_stmt; DEALLOCATE PREPARE fk_add_stmt;


-- ------------------------------------------------------------
-- 2. 既存データ Preflight クレンジング & DB CHECK 制約 (R09-P1-06)
-- ------------------------------------------------------------

-- 理由なし免除データの安全クレンジング（acceptance_required=1へ復元。理由の捏造は行わない）
UPDATE t_contract
SET acceptance_required = 1
WHERE acceptance_required = 0 AND (acceptance_exemption_reason IS NULL OR TRIM(acceptance_exemption_reason) = '');

-- DB CHECK 制約の追加（既存同名制約が無ければ作成）
SET @chk_sql = IF(
    (SELECT COUNT(*) FROM information_schema.table_constraints
      WHERE table_schema = DATABASE() AND table_name = 't_contract' AND constraint_name = 'chk_contract_acceptance_exemption') = 0,
    'ALTER TABLE t_contract ADD CONSTRAINT chk_contract_acceptance_exemption CHECK (acceptance_required = 1 OR (acceptance_exemption_reason IS NOT NULL AND TRIM(acceptance_exemption_reason) != ''''))',
    'SELECT 1'
);
PREPARE chk_stmt FROM @chk_sql; EXECUTE chk_stmt; DEALLOCATE PREPARE chk_stmt;


-- ------------------------------------------------------------
-- 3. 文書アトミック Hash Claim テーブル & バックフィル (R09-P1-09)
-- ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_document_hash_claim (
  tenant_id     VARCHAR(100) NOT NULL COMMENT 'テナントID',
  document_type VARCHAR(50)  NOT NULL COMMENT '文書種別',
  sha256        VARCHAR(64)  NOT NULL COMMENT 'ファイルHash (SHA-256)',
  document_id   BIGINT       NOT NULL COMMENT '関連文書ID',
  created_at    DATETIME     DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (tenant_id, document_type, sha256),
  CONSTRAINT fk_document_hash_claim_document FOREIGN KEY (document_id) REFERENCES t_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文書HashアトミックClaimテーブル';

-- 既存重複 Hash の Preflight 検査（複数 document_id に同一 Hash が存在する場合は Flyway を異常終了させる）
DROP PROCEDURE IF EXISTS check_v81_duplicate_document_hashes;

DELIMITER $$
CREATE PROCEDURE check_v81_duplicate_document_hashes()
BEGIN
  DECLARE dup_count INT DEFAULT 0;

  SELECT COUNT(*) INTO dup_count
  FROM (
    SELECT d.tenant_id, d.document_type, v.sha256
    FROM t_document d
    JOIN t_document_version v ON v.document_id = d.id AND v.deleted_flag = 0
    WHERE d.deleted_flag = 0
      AND d.document_type IN ('ORDER_RECEIVED', 'ORDER_ACKNOWLEDGEMENT', 'ACCEPTANCE')
    GROUP BY d.tenant_id, d.document_type, v.sha256
    HAVING COUNT(DISTINCT d.id) > 1
  ) AS dups;

  IF dup_count > 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'Duplicate document hash detected during V81 preflight check';
  END IF;
END $$
DELIMITER ;

CALL check_v81_duplicate_document_hashes();
DROP PROCEDURE IF EXISTS check_v81_duplicate_document_hashes;

-- 既存文書バージョンの Hash バックフィル
INSERT IGNORE INTO t_document_hash_claim (tenant_id, document_type, sha256, document_id)
SELECT DISTINCT d.tenant_id, d.document_type, v.sha256, d.id
FROM t_document d
JOIN t_document_version v ON v.document_id = d.id AND v.deleted_flag = 0
WHERE d.deleted_flag = 0
  AND d.document_type IN ('ORDER_RECEIVED', 'ORDER_ACKNOWLEDGEMENT', 'ACCEPTANCE');

