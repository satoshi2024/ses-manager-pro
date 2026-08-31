-- NF-05 A1短期read snapshot H2 schema。
DROP TABLE IF EXISTS t_api_read_snapshot_item CASCADE;
DROP TABLE IF EXISTS t_api_read_snapshot CASCADE;

CREATE TABLE t_api_read_snapshot (
    snapshot_id     VARCHAR(36) NOT NULL PRIMARY KEY,
    client_id       VARCHAR(100) NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL,
    legal_entity_id BIGINT NOT NULL,
    route_template  VARCHAR(255) NOT NULL,
    scope_digest    CHAR(64) NOT NULL,
    as_of           TIMESTAMP NOT NULL,
    expires_at      TIMESTAMP NOT NULL,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_api_read_snapshot_expiry CHECK (expires_at > as_of)
);

CREATE TABLE t_api_read_snapshot_item (
    snapshot_id VARCHAR(36) NOT NULL,
    resource_id BIGINT NOT NULL,
    payload_json CLOB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (snapshot_id, resource_id),
    CONSTRAINT fk_api_read_snapshot_item_snapshot FOREIGN KEY (snapshot_id)
        REFERENCES t_api_read_snapshot(snapshot_id) ON DELETE CASCADE
);
CREATE INDEX idx_api_read_snapshot_expiry ON t_api_read_snapshot(expires_at, snapshot_id);
