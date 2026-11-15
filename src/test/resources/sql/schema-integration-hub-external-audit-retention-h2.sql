-- V192のH2再生。既存の監査行も一年後の期限を持たせる。
ALTER TABLE t_external_api_audit ADD COLUMN retention_expires_at DATETIME;
UPDATE t_external_api_audit SET retention_expires_at = DATEADD('YEAR', 1, created_at);
ALTER TABLE t_external_api_audit ALTER COLUMN retention_expires_at SET NOT NULL;
CREATE INDEX idx_external_audit_retention ON t_external_api_audit (retention_expires_at, id);
ALTER TABLE t_api_retention_hold DROP CONSTRAINT IF EXISTS chk_api_retention_hold_kind;
ALTER TABLE t_api_retention_hold ADD CONSTRAINT chk_api_retention_hold_kind CHECK (record_kind IN ('IDEMPOTENCY','DELIVERY','INBOUND','INBOUND_REPLAY','AUDIT','EXTERNAL_AUDIT'));
ALTER TABLE t_api_purge_checkpoint DROP CONSTRAINT IF EXISTS chk_api_purge_checkpoint_kind;
ALTER TABLE t_api_purge_checkpoint ADD CONSTRAINT chk_api_purge_checkpoint_kind CHECK (record_kind IN ('IDEMPOTENCY','DELIVERY','INBOUND','INBOUND_REPLAY','AUDIT','EXTERNAL_AUDIT'));
