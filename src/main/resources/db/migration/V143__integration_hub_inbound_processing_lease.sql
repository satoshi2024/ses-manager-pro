-- ===================================================================
-- V137: NF-05 inbound PROCESSING lease / stale recovery (R-NF05 P1-004)。
-- claim後crashでPROCESSING滞留しないよう、outbound deliveryと同型のlease列を追加する。
-- ===================================================================

ALTER TABLE t_inbound_event
    ADD COLUMN lease_token VARCHAR(128) NULL COMMENT 'processor lease UUID',
    ADD COLUMN lease_expires_at DATETIME NULL COMMENT 'processor lease期限';

CREATE INDEX idx_inbound_processing_lease
    ON t_inbound_event (status, lease_expires_at, id);

-- ROLLBACK EVIDENCE:
-- inbound受信/replay/purgeを停止しbackupを取得する。
-- idx_inbound_processing_leaseとlease列を検証後にDROPする。適用済みmigrationの編集・再実行は禁止。
