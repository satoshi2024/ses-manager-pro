-- NF05の外部API監査を一年保存し、既存の保留・件数上限付き削除へ統合する。
-- 既存created_atは旧実装のJST。移行後の書込みはUTCへ統一する。
ALTER TABLE t_external_api_audit
    ADD COLUMN retention_expires_at DATETIME NULL COMMENT 'UTCの保存期限（一年）';
UPDATE t_external_api_audit
    SET retention_expires_at = DATE_ADD(DATE_SUB(created_at, INTERVAL 9 HOUR), INTERVAL 1 YEAR),
        created_at = DATE_SUB(created_at, INTERVAL 9 HOUR);
ALTER TABLE t_external_api_audit
    MODIFY retention_expires_at DATETIME NOT NULL COMMENT 'UTCの保存期限（一年）',
    ADD INDEX idx_external_audit_retention (retention_expires_at, id);
ALTER TABLE t_api_retention_hold
    DROP CHECK chk_api_retention_hold_kind,
    ADD CONSTRAINT chk_api_retention_hold_kind CHECK (
        record_kind IN ('IDEMPOTENCY', 'DELIVERY', 'INBOUND', 'INBOUND_REPLAY', 'AUDIT', 'EXTERNAL_AUDIT'));
ALTER TABLE t_api_purge_checkpoint
    DROP CHECK chk_api_purge_checkpoint_kind,
    ADD CONSTRAINT chk_api_purge_checkpoint_kind CHECK (
        record_kind IN ('IDEMPOTENCY', 'DELIVERY', 'INBOUND', 'INBOUND_REPLAY', 'AUDIT', 'EXTERNAL_AUDIT'));
