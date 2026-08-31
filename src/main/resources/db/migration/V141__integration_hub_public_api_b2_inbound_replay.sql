-- ===================================================================
-- V135: NF-05 B2 inbound DLQ replay metadata。
-- 元のt_inbound_eventはpayload retention、replay rowはaudit metadata retentionへ分離する。
-- replay tableはparsed snapshot/raw bodyを複製せず、元event purgeをFKで阻害しない。
-- ===================================================================

ALTER TABLE t_api_retention_hold
    DROP CHECK chk_api_retention_hold_kind,
    ADD CONSTRAINT chk_api_retention_hold_kind
        CHECK (record_kind IN ('IDEMPOTENCY', 'DELIVERY', 'INBOUND', 'INBOUND_REPLAY', 'AUDIT'));

ALTER TABLE t_api_purge_checkpoint
    DROP CHECK chk_api_purge_checkpoint_kind,
    ADD CONSTRAINT chk_api_purge_checkpoint_kind
        CHECK (record_kind IN ('IDEMPOTENCY', 'DELIVERY', 'INBOUND', 'INBOUND_REPLAY', 'AUDIT'));

CREATE TABLE IF NOT EXISTS t_inbound_event_replay (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    inbound_event_id      BIGINT NULL COMMENT '元event内部ID。元event purge後はNULLへ解放',
    client_id             VARCHAR(100) NOT NULL,
    provider_name         VARCHAR(100) NOT NULL,
    provider_event_id     VARCHAR(160) NOT NULL,
    raw_body_hash         CHAR(64) NOT NULL COMMENT 'raw bodyではなくhashのみ',
    replay_generation     INT NOT NULL,
    operator_ref          VARCHAR(128) NOT NULL COMMENT '認証済み内部adminから導出したsafe reference',
    reason_code           VARCHAR(64) NOT NULL COMMENT 'bounded reason codeのみ',
    status                VARCHAR(16) NOT NULL DEFAULT 'REQUESTED'
                              COMMENT 'REQUESTED/PROCESSING/PROCESSED/REJECTED/DLQ',
    result_code           VARCHAR(64),
    retention_class       VARCHAR(32) NOT NULL DEFAULT 'AUDIT_METADATA_1Y',
    retention_expires_at  DATETIME NOT NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at          DATETIME NULL,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_inbound_event_replay_generation UNIQUE (inbound_event_id, replay_generation),
    CONSTRAINT fk_inbound_event_replay_event FOREIGN KEY (inbound_event_id)
        REFERENCES t_inbound_event (id) ON DELETE SET NULL,
    CONSTRAINT chk_inbound_event_replay_status
        CHECK (status IN ('REQUESTED', 'PROCESSING', 'PROCESSED', 'REJECTED', 'DLQ')),
    CONSTRAINT chk_inbound_event_replay_retention
        CHECK (retention_class = 'AUDIT_METADATA_1Y'),
    CONSTRAINT chk_inbound_event_replay_hash
        CHECK (raw_body_hash REGEXP '^[0-9A-Fa-f]{64}$')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='NF-05 inbound replay metadata audit; no raw body/payload copy';

CREATE INDEX idx_inbound_event_replay_expiry
    ON t_inbound_event_replay (retention_class, retention_expires_at, id);
CREATE INDEX idx_inbound_event_replay_event
    ON t_inbound_event_replay (provider_event_id, created_at, id);

-- ROLLBACK EVIDENCE:
-- 新規inbound受信/replay/purgeを停止しbackupを取得する。
-- t_inbound_event_replayを検証後にDROPし、retention hold/checkpointの新しいkind制約を
-- 旧制約へ戻す。適用済みmigrationの編集・再実行は禁止する。
