-- NF-05 F1用H2 schema。MySQL V129の制約をH2/MySQL modeで再現する。
-- 共有H2 contextへ複数回投入されるため、依存関係を逆順にdropする。
DROP TABLE IF EXISTS t_api_purge_checkpoint CASCADE;
DROP TABLE IF EXISTS t_api_retention_hold CASCADE;
DROP TABLE IF EXISTS t_api_nonce_replay CASCADE;
DROP TABLE IF EXISTS t_inbound_event CASCADE;
DROP TABLE IF EXISTS t_api_delivery_replay_audit CASCADE;
DROP TABLE IF EXISTS t_api_delivery CASCADE;
DROP TABLE IF EXISTS m_webhook_subscription CASCADE;
DROP TABLE IF EXISTS t_api_idempotency_record CASCADE;
DROP TABLE IF EXISTS t_credential_version CASCADE;
DROP TABLE IF EXISTS m_api_client_scope CASCADE;
DROP TABLE IF EXISTS t_api_usage_bucket CASCADE;
DROP TABLE IF EXISTS m_api_client CASCADE;

CREATE TABLE m_api_client (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    owner_ref VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    legal_entity_id BIGINT,
    data_scope_json TEXT NOT NULL,
    allowed_cidrs TEXT NOT NULL,
    client_tier VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    expires_at TIMESTAMP,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_api_client_client_id UNIQUE (client_id),
    CONSTRAINT chk_api_client_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT chk_api_client_tier CHECK (client_tier IN ('STANDARD', 'PREMIUM', 'INTERNAL_TEST'))
);

CREATE TABLE m_api_client_scope (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_client_id BIGINT NOT NULL,
    scope_code VARCHAR(100) NOT NULL,
    operation_code VARCHAR(100) NOT NULL DEFAULT 'READ',
    data_scope_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_api_client_scope_operation UNIQUE (api_client_id, scope_code, operation_code),
    CONSTRAINT fk_api_client_scope_client FOREIGN KEY (api_client_id) REFERENCES m_api_client (id),
    CONSTRAINT chk_api_client_scope_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE TABLE t_credential_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    api_client_id BIGINT NOT NULL,
    credential_version INT NOT NULL,
    key_id VARCHAR(100) NOT NULL,
    encrypted_secret TEXT NOT NULL,
    secret_hash CHAR(64) NOT NULL,
    crypto_key_version VARCHAR(64) NOT NULL,
    cipher_format VARCHAR(16) NOT NULL DEFAULT 'IHG1',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    issued_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    overlap_until TIMESTAMP,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_credential_client_version UNIQUE (api_client_id, credential_version),
    CONSTRAINT uk_credential_client_key_id UNIQUE (api_client_id, key_id),
    CONSTRAINT fk_credential_client FOREIGN KEY (api_client_id) REFERENCES m_api_client (id),
    CONSTRAINT chk_credential_status CHECK (status IN ('ACTIVE', 'OVERLAP', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_credential_cipher_format CHECK (cipher_format = 'IHG1')
);

CREATE TABLE t_api_idempotency_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    route_template VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS',
    response_status SMALLINT,
    safe_response_snapshot TEXT,
    retention_class VARCHAR(32),
    retention_expires_at TIMESTAMP,
    terminal_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_idempotency_client_route_key UNIQUE (client_id, route_template, idempotency_key),
    CONSTRAINT chk_idempotency_status CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'CONFLICT')),
    CONSTRAINT chk_idempotency_retention CHECK (retention_class IS NULL OR retention_class IN ('SUCCEEDED_PAYLOAD_30D', 'FAILED_DLQ_PAYLOAD_90D'))
);

CREATE TABLE m_webhook_subscription (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    direction VARCHAR(16) NOT NULL DEFAULT 'OUTBOUND',
    event_type VARCHAR(100) NOT NULL,
    endpoint_url VARCHAR(512) NOT NULL,
    key_id VARCHAR(100) NOT NULL,
    signing_credential_version INT NOT NULL DEFAULT 1,
    encrypted_signing_secret TEXT NOT NULL,
    crypto_key_version VARCHAR(64) NOT NULL,
    data_scope_json TEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_webhook_subscription UNIQUE (client_id, direction, event_type, endpoint_url),
    CONSTRAINT chk_webhook_direction CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    CONSTRAINT chk_webhook_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE TABLE t_api_delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(128) NOT NULL,
    subscription_id BIGINT NOT NULL,
    delivery_generation INT NOT NULL DEFAULT 1,
    client_id VARCHAR(100) NOT NULL,
    scope_code VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    scope_digest CHAR(64) NOT NULL DEFAULT '0000000000000000000000000000000000000000000000000000000000000000',
    primary_resource_type VARCHAR(64),
    primary_resource_id BIGINT,
    event_type VARCHAR(100) NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    correlation_id VARCHAR(128),
    provider_idempotency_key VARCHAR(128) NOT NULL,
    external_dto_snapshot TEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 8,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token VARCHAR(128),
    lease_expires_at TIMESTAMP,
    provider_request_id VARCHAR(128),
    last_error_code VARCHAR(64),
    terminal_at TIMESTAMP,
    retention_class VARCHAR(32),
    retention_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_api_delivery_event_generation UNIQUE (event_id, subscription_id, delivery_generation),
    CONSTRAINT fk_api_delivery_subscription FOREIGN KEY (subscription_id) REFERENCES m_webhook_subscription (id),
    CONSTRAINT chk_api_delivery_primary_resource CHECK (
        (primary_resource_type IS NULL AND primary_resource_id IS NULL)
        OR (primary_resource_type IN ('engineer-availability', 'project', 'contract-status', 'invoice-status')
            AND primary_resource_id IS NOT NULL AND primary_resource_id > 0)),
    CONSTRAINT chk_api_delivery_status CHECK (status IN ('PENDING', 'CLAIMED', 'RETRYABLE', 'SUCCEEDED', 'FAILED', 'DLQ')),
    CONSTRAINT chk_api_delivery_retention CHECK (retention_class IS NULL OR retention_class IN ('SUCCEEDED_PAYLOAD_30D', 'FAILED_DLQ_PAYLOAD_90D'))
);

CREATE TABLE t_inbound_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    provider_name VARCHAR(100) NOT NULL,
    provider_event_id VARCHAR(160) NOT NULL,
    raw_body_hash CHAR(64) NOT NULL,
    signed_timestamp TIMESTAMP NOT NULL,
    parsed_fields_snapshot TEXT,
    signature_valid BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL DEFAULT 'RECEIVED',
    result_code VARCHAR(64),
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP,
    terminal_at TIMESTAMP,
    retention_class VARCHAR(32),
    retention_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_inbound_provider_event UNIQUE (client_id, provider_name, provider_event_id),
    CONSTRAINT chk_inbound_status CHECK (status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'DUPLICATE', 'CONFLICT', 'DLQ')),
    CONSTRAINT chk_inbound_retention CHECK (retention_class IS NULL OR retention_class IN ('SUCCEEDED_PAYLOAD_30D', 'FAILED_DLQ_PAYLOAD_90D'))
);

CREATE TABLE t_api_usage_bucket (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    scope_code VARCHAR(100) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    route_template VARCHAR(255) NOT NULL,
    minute_window_start TIMESTAMP NOT NULL,
    minute_count INT NOT NULL DEFAULT 0,
    day_window_start TIMESTAMP NOT NULL,
    day_count INT NOT NULL DEFAULT 0,
    burst_tokens INT NOT NULL DEFAULT 20,
    burst_last_refill_at TIMESTAMP NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_usage_subject UNIQUE (client_id, scope_code, tenant_id, route_template),
    CONSTRAINT chk_api_usage_minute CHECK (minute_count >= 0 AND minute_count <= 60),
    CONSTRAINT chk_api_usage_day CHECK (day_count >= 0 AND day_count <= 50000),
    CONSTRAINT chk_api_usage_burst CHECK (burst_tokens >= 0 AND burst_tokens <= 20)
);

CREATE TABLE t_api_nonce_replay (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    client_id VARCHAR(100) NOT NULL,
    credential_version INT NOT NULL,
    nonce_hash CHAR(64) NOT NULL,
    accepted_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_nonce_client_hash UNIQUE (client_id, nonce_hash)
);

CREATE TABLE t_api_retention_hold (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_kind VARCHAR(16) NOT NULL,
    record_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    hold_generation INT NOT NULL DEFAULT 1,
    reason_code VARCHAR(64) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_retention_hold_record UNIQUE (record_kind, record_id),
    CONSTRAINT chk_api_retention_hold_kind CHECK (record_kind IN ('IDEMPOTENCY', 'DELIVERY', 'INBOUND', 'AUDIT')),
    CONSTRAINT chk_api_retention_hold_status CHECK (status IN ('ACTIVE', 'RELEASED'))
);

CREATE TABLE t_api_purge_checkpoint (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_kind VARCHAR(16) NOT NULL,
    retention_class VARCHAR(32) NOT NULL,
    restore_epoch BIGINT NOT NULL DEFAULT 0,
    last_expires_at TIMESTAMP,
    last_record_id BIGINT,
    run_status VARCHAR(16) NOT NULL DEFAULT 'READY',
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_purge_checkpoint UNIQUE (record_kind, retention_class),
    CONSTRAINT chk_api_purge_checkpoint_kind CHECK (record_kind IN ('IDEMPOTENCY', 'DELIVERY', 'INBOUND', 'AUDIT')),
    CONSTRAINT chk_api_purge_checkpoint_class CHECK (retention_class IN ('SUCCEEDED_PAYLOAD_30D', 'FAILED_DLQ_PAYLOAD_90D', 'AUDIT_METADATA_1Y')),
    CONSTRAINT chk_api_purge_checkpoint_status CHECK (run_status IN ('READY', 'RUNNING', 'FAILED', 'COMPLETE'))
);

CREATE INDEX idx_api_client_scope_status ON m_api_client_scope (api_client_id, status, scope_code);
CREATE INDEX idx_credential_active ON t_credential_version (api_client_id, status, expires_at);
CREATE INDEX idx_idempotency_expiry ON t_api_idempotency_record (status, retention_expires_at, id);
CREATE INDEX idx_api_delivery_due ON t_api_delivery (status, next_attempt_at, lease_expires_at, id);
CREATE INDEX idx_api_delivery_expiry ON t_api_delivery (status, retention_expires_at, id);
CREATE INDEX idx_api_delivery_primary_resource ON t_api_delivery (primary_resource_type, primary_resource_id, status, id);
CREATE INDEX idx_inbound_expiry ON t_inbound_event (status, retention_expires_at, id);
CREATE INDEX idx_api_nonce_expiry ON t_api_nonce_replay (expires_at, id);
CREATE INDEX idx_retention_hold_status ON t_api_retention_hold (record_kind, status, record_id);

CREATE TABLE t_api_delivery_replay_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    delivery_id BIGINT,
    event_id VARCHAR(128) NOT NULL,
    replay_generation INT NOT NULL,
    operator_ref VARCHAR(128) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    scope_digest CHAR(64) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    retention_class VARCHAR(32) NOT NULL DEFAULT 'AUDIT_METADATA_1Y',
    retention_expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_delivery_replay_generation UNIQUE (delivery_id, replay_generation),
    CONSTRAINT fk_api_delivery_replay_delivery FOREIGN KEY (delivery_id) REFERENCES t_api_delivery (id) ON DELETE SET NULL,
    CONSTRAINT chk_api_delivery_replay_audit_retention CHECK (retention_class = 'AUDIT_METADATA_1Y')
);
CREATE INDEX idx_api_delivery_replay_event ON t_api_delivery_replay_audit (event_id, created_at, id);
CREATE INDEX idx_api_delivery_replay_expiry ON t_api_delivery_replay_audit (retention_class, retention_expires_at, id);
