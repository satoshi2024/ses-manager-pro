-- ===================================================================
-- V136: NF-05 B2 provider/resource/admin reference binding。
-- provider/event subscription and inbound resource bindingを受信前から固定する。
-- admin/replay URLへ内部DB IDを出さないため、opaque referenceを保存する。
-- ===================================================================

ALTER TABLE m_webhook_subscription
    ADD COLUMN provider_name VARCHAR(100) NOT NULL DEFAULT '__UNBOUND__'
    COMMENT 'Owner承認済みprovider binding。旧行は明示再登録までinbound不許可';
CREATE INDEX idx_webhook_subscription_inbound_binding
    ON m_webhook_subscription (client_id, provider_name, direction, event_type, status);

ALTER TABLE t_inbound_event
    ADD COLUMN admin_reference VARCHAR(64) NULL
    COMMENT 'client/provider/eventから導出したopaque admin reference';
ALTER TABLE t_inbound_event
    ADD COLUMN primary_resource_type VARCHAR(64) NULL
    COMMENT 'resource bindingのprimary type。内部IDはadmin DTOへ返さない';
ALTER TABLE t_inbound_event
    ADD COLUMN primary_resource_id BIGINT NULL
    COMMENT 'resource bindingの内部ID。外部へ返さない';
CREATE UNIQUE INDEX uk_inbound_admin_reference ON t_inbound_event (admin_reference);
CREATE INDEX idx_inbound_primary_resource
    ON t_inbound_event (primary_resource_type, primary_resource_id, status, id);

ALTER TABLE t_inbound_event_replay
    ADD COLUMN replay_reference VARCHAR(64) NULL
    COMMENT 'opaque replay operation reference。内部IDを外部へ返さない';
CREATE UNIQUE INDEX uk_inbound_replay_reference ON t_inbound_event_replay (replay_reference);

ALTER TABLE t_inbound_event
    ADD CONSTRAINT chk_inbound_primary_resource
    CHECK ((primary_resource_type IS NULL AND primary_resource_id IS NULL)
        OR (primary_resource_type IN ('engineer-availability', 'project', 'contract-status', 'invoice-status')
            AND primary_resource_id IS NOT NULL AND primary_resource_id > 0));

-- ROLLBACK EVIDENCE:
-- inbound/replay受信を停止し、referenceを含むbackupとFlyway履歴を取得する。
-- 新規index/constraintを検証後に削除し、追加列を全row確認してから手動でDROPする。
-- 適用済みmigrationの編集・再実行は禁止する。
