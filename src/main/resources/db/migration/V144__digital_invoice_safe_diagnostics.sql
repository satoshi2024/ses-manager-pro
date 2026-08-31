-- 電子請求書の安全な診断識別子を保持する。
-- 本文・SQLバインド値・error_descriptionは追加しない。
-- V136で追加済みの t_audit_log.correlation_id 等と衝突しないよう存在判定する。

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_integration_job ADD COLUMN correlation_id VARCHAR(100) NULL COMMENT ''API・ワーカー横断相関ID''',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'correlation_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_integration_job ADD COLUMN provider_operation_id VARCHAR(128) NULL COMMENT ''プロバイダ操作ID（照合用）''',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'provider_operation_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_integration_job ADD COLUMN error_category VARCHAR(32) NULL COMMENT ''業務/システムエラー分類''',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_integration_job' AND column_name = 'error_category');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_integration_job_event ADD COLUMN correlation_id VARCHAR(100) NULL COMMENT ''相関ID''',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_integration_job_event' AND column_name = 'correlation_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN correlation_id VARCHAR(128) NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'correlation_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN invoice_id VARCHAR(100) NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'invoice_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN digital_invoice_id VARCHAR(100) NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'digital_invoice_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN job_id VARCHAR(100) NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'job_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN provider_operation_id VARCHAR(128) NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'provider_operation_id');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN error_code VARCHAR(64) NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'error_code');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_audit_log ADD COLUMN error_category VARCHAR(32) NULL',
  'SELECT 1') FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 't_audit_log' AND column_name = 'error_category');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
