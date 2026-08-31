-- ===================================================================
-- V134: B1 deliveryの一次resource bindingを永続化する。
--
-- replayではevent envelopeのpublicResourceIdを単一の内部IDとだけ照合し、
-- customer/project/contract等のsecondary dimensionは各専用public IDで検証する。
-- 既存rowは一次bindingが不明なためreplay対象外とし、新enqueue経路は必須入力にする。
-- 適用済みmigrationは編集・再実行しない。
-- ===================================================================

ALTER TABLE t_api_delivery
    ADD COLUMN primary_resource_type VARCHAR(64) NULL
    COMMENT 'replayの一次resource種別。publicResourceIdのbind対象';

ALTER TABLE t_api_delivery
    ADD COLUMN primary_resource_id BIGINT NULL
    COMMENT 'replayの一次resource内部ID。外部へ返さない';

ALTER TABLE t_api_delivery
    ADD CONSTRAINT chk_api_delivery_primary_resource
    CHECK ((primary_resource_type IS NULL AND primary_resource_id IS NULL)
        OR (primary_resource_type IN ('engineer-availability', 'project', 'contract-status', 'invoice-status')
            AND primary_resource_id IS NOT NULL AND primary_resource_id > 0));

CREATE INDEX idx_api_delivery_primary_resource
    ON t_api_delivery (primary_resource_type, primary_resource_id, status, id);

-- ROLLBACK EVIDENCE:
-- 新規enqueue/replayを停止し、primary bindingを設定したrow数とNULL rowを棚卸し後、
-- indexとcheck制約を削除してから2列をDROPする。適用済みmigrationの編集・再実行は行わない。
