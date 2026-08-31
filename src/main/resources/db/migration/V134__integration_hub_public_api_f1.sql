-- ===================================================================
-- V129: NF-05 F1 Integration Hub 公開API persistence基盤
--
-- 既存の t_notification_outbox / t_integration_job は変更・再利用しない。
-- t_api_delivery がNF-05専用のevent/delivery ledgerであり、外部HTTPはこのDDLの責務外である。
-- ===================================================================

CREATE TABLE IF NOT EXISTS m_api_client (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    client_id             VARCHAR(100) NOT NULL COMMENT '外部service account識別子',
    owner_ref             VARCHAR(100) NOT NULL COMMENT '所有者参照（実名ではない）',
    tenant_id             VARCHAR(64) NOT NULL COMMENT 'server-side tenant binding',
    legal_entity_id       BIGINT NULL COMMENT 'server-side legal entity binding',
    data_scope_json       LONGTEXT NOT NULL COMMENT 'allow-listed data scopeの正規化JSON',
    allowed_cidrs         TEXT NOT NULL COMMENT 'client CIDR allow-list（秘密情報を含めない）',
    client_tier           VARCHAR(32) NOT NULL DEFAULT 'STANDARD' COMMENT 'bounded metrics tier',
    status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/SUSPENDED/REVOKED',
    expires_at            DATETIME NULL COMMENT 'client binding expiry',
    revoked_at            DATETIME NULL COMMENT '即時revoke時刻',
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_api_client_client_id UNIQUE (client_id),
    CONSTRAINT chk_api_client_status CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    CONSTRAINT chk_api_client_tier CHECK (client_tier IN ('STANDARD', 'PREMIUM', 'INTERNAL_TEST'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 B2B client binding';

CREATE TABLE IF NOT EXISTS m_api_client_scope (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    api_client_id         BIGINT NOT NULL,
    scope_code            VARCHAR(100) NOT NULL COMMENT '公開resource scope',
    operation_code        VARCHAR(100) NOT NULL DEFAULT 'READ' COMMENT 'operation/command permission',
    data_scope_json       LONGTEXT NOT NULL COMMENT 'scope固有のallow-list predicate',
    status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED',
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_api_client_scope_operation UNIQUE (api_client_id, scope_code, operation_code),
    CONSTRAINT fk_api_client_scope_client FOREIGN KEY (api_client_id) REFERENCES m_api_client (id),
    CONSTRAINT chk_api_client_scope_status CHECK (status IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 client scope and command permission';

CREATE TABLE IF NOT EXISTS t_credential_version (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    api_client_id         BIGINT NOT NULL,
    credential_version    INT NOT NULL COMMENT '署名credentialの世代',
    key_id                VARCHAR(100) NOT NULL COMMENT 'credential key ID',
    encrypted_secret      LONGTEXT NOT NULL COMMENT 'AES-256-GCM envelopeのみ。平文禁止',
    secret_hash           CHAR(64) NOT NULL COMMENT '平文secretのSHA-256 hex',
    crypto_key_version    VARCHAR(64) NOT NULL COMMENT '環境keyringの世代',
    cipher_format         VARCHAR(16) NOT NULL DEFAULT 'IHG1',
    status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/OVERLAP/REVOKED/EXPIRED',
    issued_at             DATETIME NOT NULL,
    expires_at            DATETIME NOT NULL,
    overlap_until         DATETIME NULL,
    revoked_at            DATETIME NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_credential_client_version UNIQUE (api_client_id, credential_version),
    CONSTRAINT uk_credential_client_key_id UNIQUE (api_client_id, key_id),
    CONSTRAINT fk_credential_client FOREIGN KEY (api_client_id) REFERENCES m_api_client (id),
    CONSTRAINT chk_credential_status CHECK (status IN ('ACTIVE', 'OVERLAP', 'REVOKED', 'EXPIRED')),
    CONSTRAINT chk_credential_cipher_format CHECK (cipher_format = 'IHG1')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 credential version';

CREATE TABLE IF NOT EXISTS t_api_idempotency_record (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    client_id             VARCHAR(100) NOT NULL,
    route_template        VARCHAR(255) NOT NULL COMMENT '正規化route template。raw path禁止',
    idempotency_key       VARCHAR(200) NOT NULL COMMENT 'client supplied idempotency key',
    request_digest        CHAR(64) NOT NULL COMMENT 'canonical request SHA-256のみ',
    status                VARCHAR(16) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'IN_PROGRESS/SUCCEEDED/FAILED/CONFLICT',
    response_status       SMALLINT NULL,
    safe_response_snapshot LONGTEXT NULL COMMENT 'allow-listed safe responseのみ',
    retention_class       VARCHAR(32) NULL COMMENT 'SUCCEEDED_PAYLOAD_30D/FAILED_DLQ_PAYLOAD_90D',
    retention_expires_at  DATETIME NULL,
    terminal_at           DATETIME NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_idempotency_client_route_key UNIQUE (client_id, route_template, idempotency_key),
    CONSTRAINT chk_idempotency_status CHECK (status IN ('IN_PROGRESS', 'SUCCEEDED', 'FAILED', 'CONFLICT')),
    CONSTRAINT chk_idempotency_retention CHECK (retention_class IS NULL OR retention_class IN ('SUCCEEDED_PAYLOAD_30D', 'FAILED_DLQ_PAYLOAD_90D'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 idempotency digest ledger';

CREATE TABLE IF NOT EXISTS m_webhook_subscription (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    client_id             VARCHAR(100) NOT NULL,
    direction             VARCHAR(16) NOT NULL DEFAULT 'OUTBOUND',
    event_type            VARCHAR(100) NOT NULL,
    endpoint_url          VARCHAR(512) NOT NULL COMMENT '検証済みendpoint候補。送信は別scope。最大512文字',
    key_id                VARCHAR(100) NOT NULL,
    encrypted_signing_secret LONGTEXT NOT NULL COMMENT 'AES-256-GCM envelopeのみ。平文禁止',
    crypto_key_version    VARCHAR(64) NOT NULL,
    data_scope_json       LONGTEXT NOT NULL COMMENT 'subscription scopeのallow-list JSON',
    status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/REVOKED',
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_webhook_subscription UNIQUE (client_id, direction, event_type, endpoint_url),
    CONSTRAINT chk_webhook_direction CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    CONSTRAINT chk_webhook_status CHECK (status IN ('ACTIVE', 'REVOKED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 webhook subscription persistence';

CREATE TABLE IF NOT EXISTS t_api_delivery (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    event_id              VARCHAR(128) NOT NULL COMMENT 'opaque domain event ID',
    subscription_id       BIGINT NOT NULL,
    delivery_generation   INT NOT NULL DEFAULT 1,
    client_id             VARCHAR(100) NOT NULL,
    scope_code            VARCHAR(100) NOT NULL,
    tenant_id             VARCHAR(64) NOT NULL,
    event_type            VARCHAR(100) NOT NULL,
    schema_version        VARCHAR(32) NOT NULL,
    correlation_id        VARCHAR(128) NULL COMMENT 'safe trace metadata。metrics label禁止',
    provider_idempotency_key VARCHAR(128) NOT NULL COMMENT 'event/generationから決定的に生成。外部副作用の重複を防ぐ',
    external_dto_snapshot LONGTEXT NOT NULL COMMENT 'approved external DTO snapshotのみ',
    payload_hash          CHAR(64) NOT NULL,
    status                VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/CLAIMED/RETRYABLE/SUCCEEDED/FAILED/DLQ',
    attempt_count         INT NOT NULL DEFAULT 0,
    max_attempts          INT NOT NULL DEFAULT 8,
    next_attempt_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_token           VARCHAR(128) NULL,
    lease_expires_at      DATETIME NULL,
    provider_request_id   VARCHAR(128) NULL COMMENT 'safe provider request ID',
    last_error_code       VARCHAR(64) NULL COMMENT 'safe bounded error code only',
    terminal_at           DATETIME NULL,
    retention_class       VARCHAR(32) NULL,
    retention_expires_at  DATETIME NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_api_delivery_event_generation UNIQUE (event_id, subscription_id, delivery_generation),
    CONSTRAINT fk_api_delivery_subscription FOREIGN KEY (subscription_id) REFERENCES m_webhook_subscription (id),
    CONSTRAINT chk_api_delivery_status CHECK (status IN ('PENDING', 'CLAIMED', 'RETRYABLE', 'SUCCEEDED', 'FAILED', 'DLQ')),
    CONSTRAINT chk_api_delivery_retention CHECK (retention_class IS NULL OR retention_class IN ('SUCCEEDED_PAYLOAD_30D', 'FAILED_DLQ_PAYLOAD_90D'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 dedicated webhook delivery ledger';

CREATE TABLE IF NOT EXISTS t_inbound_event (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    client_id             VARCHAR(100) NOT NULL,
    provider_name         VARCHAR(100) NOT NULL,
    provider_event_id     VARCHAR(160) NOT NULL,
    raw_body_hash         CHAR(64) NOT NULL COMMENT 'raw bytesは永続化しない',
    signed_timestamp      DATETIME NOT NULL,
    parsed_fields_snapshot LONGTEXT NULL COMMENT 'allow-listed parsed fieldsのみ',
    signature_valid       TINYINT(1) NOT NULL DEFAULT 0,
    status                VARCHAR(16) NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/PROCESSING/PROCESSED/DUPLICATE/CONFLICT/DLQ',
    result_code           VARCHAR(64) NULL COMMENT 'safe bounded result code',
    received_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at          DATETIME NULL,
    terminal_at           DATETIME NULL,
    retention_class       VARCHAR(32) NULL,
    retention_expires_at  DATETIME NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version               INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_inbound_provider_event UNIQUE (client_id, provider_name, provider_event_id),
    CONSTRAINT chk_inbound_status CHECK (status IN ('RECEIVED', 'PROCESSING', 'PROCESSED', 'DUPLICATE', 'CONFLICT', 'DLQ')),
    CONSTRAINT chk_inbound_retention CHECK (retention_class IS NULL OR retention_class IN ('SUCCEEDED_PAYLOAD_30D', 'FAILED_DLQ_PAYLOAD_90D'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 inbound replay ledger';

CREATE TABLE IF NOT EXISTS t_api_usage_bucket (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    client_id             VARCHAR(100) NOT NULL,
    scope_code            VARCHAR(100) NOT NULL,
    tenant_id             VARCHAR(64) NOT NULL,
    route_template        VARCHAR(255) NOT NULL COMMENT '正規化route template。raw path/IP禁止',
    minute_window_start   DATETIME NOT NULL,
    minute_count          INT NOT NULL DEFAULT 0,
    day_window_start      DATETIME NOT NULL,
    day_count             INT NOT NULL DEFAULT 0,
    burst_tokens          INT NOT NULL DEFAULT 20,
    burst_last_refill_at  DATETIME NOT NULL,
    version               INT NOT NULL DEFAULT 0,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_usage_subject UNIQUE (client_id, scope_code, tenant_id, route_template),
    CONSTRAINT chk_api_usage_minute CHECK (minute_count >= 0 AND minute_count <= 60),
    CONSTRAINT chk_api_usage_day CHECK (day_count >= 0 AND day_count <= 50000),
    CONSTRAINT chk_api_usage_burst CHECK (burst_tokens >= 0 AND burst_tokens <= 20)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 client/scope/tenant/route quota bucket';

CREATE TABLE IF NOT EXISTS t_api_nonce_replay (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    client_id             VARCHAR(100) NOT NULL,
    credential_version    INT NOT NULL,
    nonce_hash            CHAR(64) NOT NULL COMMENT 'canonical nonce bytes SHA-256',
    accepted_at           DATETIME NOT NULL,
    expires_at            DATETIME NOT NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_nonce_client_hash UNIQUE (client_id, nonce_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 nonce replay prevention ledger';

CREATE TABLE IF NOT EXISTS t_api_retention_hold (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    record_kind           VARCHAR(16) NOT NULL COMMENT 'IDEMPOTENCY/DELIVERY/INBOUND/AUDIT',
    record_id             BIGINT NOT NULL COMMENT '対象rowの内部IDのみ',
    status                VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RELEASED',
    hold_generation       INT NOT NULL DEFAULT 1,
    reason_code           VARCHAR(64) NOT NULL COMMENT 'safe bounded reason code',
    version               INT NOT NULL DEFAULT 0,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    released_at           DATETIME NULL,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_retention_hold_record UNIQUE (record_kind, record_id),
    CONSTRAINT chk_api_retention_hold_kind CHECK (record_kind IN ('IDEMPOTENCY', 'DELIVERY', 'INBOUND', 'AUDIT')),
    CONSTRAINT chk_api_retention_hold_status CHECK (status IN ('ACTIVE', 'RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 legal hold metadata';

CREATE TABLE IF NOT EXISTS t_api_purge_checkpoint (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '内部参照ID（外部へ返さない）',
    record_kind           VARCHAR(16) NOT NULL,
    retention_class       VARCHAR(32) NOT NULL,
    restore_epoch         BIGINT NOT NULL DEFAULT 0 COMMENT 'restore cutoverごとに増加',
    last_expires_at       DATETIME NULL,
    last_record_id        BIGINT NULL,
    run_status             VARCHAR(16) NOT NULL DEFAULT 'READY' COMMENT 'READY/RUNNING/FAILED/COMPLETE',
    started_at             DATETIME NULL,
    completed_at           DATETIME NULL,
    version               INT NOT NULL DEFAULT 0,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_api_purge_checkpoint UNIQUE (record_kind, retention_class),
    CONSTRAINT chk_api_purge_checkpoint_kind CHECK (record_kind IN ('IDEMPOTENCY', 'DELIVERY', 'INBOUND', 'AUDIT')),
    CONSTRAINT chk_api_purge_checkpoint_class CHECK (retention_class IN ('SUCCEEDED_PAYLOAD_30D', 'FAILED_DLQ_PAYLOAD_90D', 'AUDIT_METADATA_1Y')),
    CONSTRAINT chk_api_purge_checkpoint_status CHECK (run_status IN ('READY', 'RUNNING', 'FAILED', 'COMPLETE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF-05 purge resume checkpoint';

CREATE INDEX idx_api_client_scope_status ON m_api_client_scope (api_client_id, status, scope_code);
CREATE INDEX idx_credential_active ON t_credential_version (api_client_id, status, expires_at);
CREATE INDEX idx_idempotency_expiry ON t_api_idempotency_record (status, retention_expires_at, id);
CREATE INDEX idx_api_delivery_due ON t_api_delivery (status, next_attempt_at, lease_expires_at, id);
CREATE INDEX idx_api_delivery_expiry ON t_api_delivery (status, retention_expires_at, id);
CREATE INDEX idx_inbound_expiry ON t_inbound_event (status, retention_expires_at, id);
CREATE INDEX idx_api_nonce_expiry ON t_api_nonce_replay (expires_at, id);
CREATE INDEX idx_retention_hold_status ON t_api_retention_hold (record_kind, status, record_id);

-- ROLLBACK EVIDENCE (手動運用のみ。Flyway適用済みmigrationの編集・再実行は禁止):
-- 1) 新規受付とworker/purgeを停止し、backup/restore計画を承認する。
-- 2) 子表の順に t_api_purge_checkpoint, t_api_retention_hold, t_api_nonce_replay,
--    t_inbound_event, t_api_delivery, t_api_idempotency_record, t_credential_version,
--    m_api_client_scope, m_webhook_subscription, t_api_usage_bucket, m_api_client を検証後にDROPする。
-- 3) 実行後はFlyway schema history、H2 schema、backup/restore検証を再確認する。
-- DROP文は事故防止のためmigrationへ実行可能な形では記載しない。
