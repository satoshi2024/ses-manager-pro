-- NF-05 B2 inbound replay metadata H2 schema。元event purgeをFKで阻害しない。
DROP TABLE IF EXISTS t_inbound_event_replay CASCADE;

ALTER TABLE t_api_retention_hold DROP CONSTRAINT chk_api_retention_hold_kind;
ALTER TABLE t_api_retention_hold ADD CONSTRAINT chk_api_retention_hold_kind
    CHECK (record_kind IN ('IDEMPOTENCY', 'DELIVERY', 'INBOUND', 'INBOUND_REPLAY', 'AUDIT'));
ALTER TABLE t_api_purge_checkpoint DROP CONSTRAINT chk_api_purge_checkpoint_kind;
ALTER TABLE t_api_purge_checkpoint ADD CONSTRAINT chk_api_purge_checkpoint_kind
    CHECK (record_kind IN ('IDEMPOTENCY', 'DELIVERY', 'INBOUND', 'INBOUND_REPLAY', 'AUDIT'));

CREATE TABLE t_inbound_event_replay (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inbound_event_id BIGINT,
    replay_reference VARCHAR(64),
    client_id VARCHAR(100) NOT NULL,
    provider_name VARCHAR(100) NOT NULL,
    provider_event_id VARCHAR(160) NOT NULL,
    raw_body_hash CHAR(64) NOT NULL,
    replay_generation INT NOT NULL,
    operator_ref VARCHAR(128) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'REQUESTED',
    result_code VARCHAR(64),
    retention_class VARCHAR(32) NOT NULL DEFAULT 'AUDIT_METADATA_1Y',
    retention_expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_inbound_event_replay_generation UNIQUE (inbound_event_id, replay_generation),
    CONSTRAINT uk_inbound_replay_reference UNIQUE (replay_reference),
    CONSTRAINT fk_inbound_event_replay_event FOREIGN KEY (inbound_event_id)
        REFERENCES t_inbound_event(id) ON DELETE SET NULL,
    CONSTRAINT chk_inbound_event_replay_status
        CHECK (status IN ('REQUESTED', 'PROCESSING', 'PROCESSED', 'REJECTED', 'DLQ')),
    CONSTRAINT chk_inbound_event_replay_retention
        CHECK (retention_class = 'AUDIT_METADATA_1Y')
);
CREATE INDEX idx_inbound_event_replay_expiry
    ON t_inbound_event_replay (retention_class, retention_expires_at, id);
CREATE INDEX idx_inbound_event_replay_event
    ON t_inbound_event_replay (provider_event_id, created_at, id);
