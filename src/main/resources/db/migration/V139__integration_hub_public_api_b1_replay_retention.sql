-- ===================================================================
-- V133: NF-05 B1 replay auditとdelivery payloadのretention境界を分離する。
-- auditはsafe metadataとして1年保持し、delivery削除をFKで阻害しない。
-- 適用済みV132は編集・再実行しない。
-- ===================================================================

ALTER TABLE t_api_delivery_replay_audit
    DROP FOREIGN KEY fk_api_delivery_replay_delivery;

ALTER TABLE t_api_delivery_replay_audit
    MODIFY COLUMN delivery_id BIGINT NULL,
    ADD COLUMN retention_class VARCHAR(32) NOT NULL DEFAULT 'AUDIT_METADATA_1Y'
        COMMENT 'safe audit metadataのretention class',
    ADD COLUMN retention_expires_at DATETIME NULL
        COMMENT 'audit metadata purge期限';

UPDATE t_api_delivery_replay_audit
SET retention_expires_at = DATE_ADD(created_at, INTERVAL 1 YEAR)
WHERE retention_expires_at IS NULL;

ALTER TABLE t_api_delivery_replay_audit
    MODIFY COLUMN retention_expires_at DATETIME NOT NULL,
    ADD CONSTRAINT chk_api_delivery_replay_audit_retention
        CHECK (retention_class = 'AUDIT_METADATA_1Y'),
    ADD CONSTRAINT fk_api_delivery_replay_delivery
        FOREIGN KEY (delivery_id) REFERENCES t_api_delivery (id) ON DELETE SET NULL;

CREATE INDEX idx_api_delivery_replay_expiry
    ON t_api_delivery_replay_audit (retention_class, retention_expires_at, id);

-- ROLLBACK EVIDENCE:
-- 新規replayとpurgeを停止しbackupを取得後、V132 FKを復元する前にdelivery_idのNULL行を
-- safe metadataとして退避する。適用済みmigrationの編集・再実行は行わない。
