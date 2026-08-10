-- ============================================================
-- T065 B2: t_compliance_finding.exception_expires_at（例外承認の有効期限）
-- 本specの正式migration（V84）で作成されたt_compliance_findingへ後続列として追加する。
-- V1（consolidated baseline）には含めず、本migrationが唯一の定義源である
-- （MigrationScriptIntegrityTest: V1定義列の重複ADD COLUMN禁止）。
-- MySQL 8にはADD COLUMN IF NOT EXISTSが無いため、information_schema確認＋条件付きDDLで冪等化する。
-- ============================================================

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME = 't_compliance_finding'
                     AND COLUMN_NAME = 'exception_expires_at');

SET @ddl = IF(@col_exists = 0,
    'ALTER TABLE t_compliance_finding
       ADD COLUMN exception_expires_at DATETIME COMMENT ''例外承認の有効期限（NULL=無期限、期限超過でOPENへ戻る）'' AFTER evidence_document_id',
    'SELECT 1');

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
