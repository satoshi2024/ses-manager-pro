-- 電子請求書の安全な診断識別子を保持する。
-- 本文・SQLバインド値・error_descriptionは追加しない。

ALTER TABLE t_integration_job ADD COLUMN correlation_id VARCHAR(100) NULL;
ALTER TABLE t_integration_job ADD COLUMN provider_operation_id VARCHAR(128) NULL;
ALTER TABLE t_integration_job ADD COLUMN error_category VARCHAR(32) NULL;
ALTER TABLE t_integration_job_event ADD COLUMN correlation_id VARCHAR(100) NULL;
ALTER TABLE t_audit_log ADD COLUMN correlation_id VARCHAR(100) NULL;
ALTER TABLE t_audit_log ADD COLUMN invoice_id VARCHAR(100) NULL;
ALTER TABLE t_audit_log ADD COLUMN digital_invoice_id VARCHAR(100) NULL;
ALTER TABLE t_audit_log ADD COLUMN job_id VARCHAR(100) NULL;
ALTER TABLE t_audit_log ADD COLUMN provider_operation_id VARCHAR(128) NULL;
ALTER TABLE t_audit_log ADD COLUMN error_code VARCHAR(64) NULL;
ALTER TABLE t_audit_log ADD COLUMN error_category VARCHAR(32) NULL;
