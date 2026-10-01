-- H2 Schema for JP PINT Digital Invoice (T103)

CREATE TABLE IF NOT EXISTS t_peppol_participant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NULL,
    legal_entity_id BIGINT NULL,
    owner_type VARCHAR(50) NOT NULL,
    owner_id BIGINT NOT NULL,
    scheme_id VARCHAR(50) NOT NULL,
    participant_id VARCHAR(100) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    verified_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(50) NULL,
    deleted_flag TINYINT(1) DEFAULT 0,
    callback_active_slot TINYINT AS (CASE WHEN deleted_flag = 0 AND status = 'VERIFIED' THEN 1 ELSE NULL END),
    UNIQUE KEY uk_peppol_scope_owner (tenant_id, legal_entity_id, owner_type, owner_id),
    UNIQUE KEY uk_peppol_callback_active (provider, scheme_id, participant_id, callback_active_slot)
);

CREATE TABLE IF NOT EXISTS t_digital_invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NULL,
    legal_entity_id BIGINT NULL,
    invoice_id BIGINT NULL,
    direction VARCHAR(20) NOT NULL,
    profile VARCHAR(50) NOT NULL,
    specification_version VARCHAR(20) NOT NULL,
    message_id VARCHAR(100) NOT NULL,
    provider_message_id VARCHAR(100) NULL,
    xml_document_id BIGINT NULL,
    validation_document_id BIGINT NULL,
    status VARCHAR(20) NOT NULL,
    sent_at DATETIME NULL,
    received_at DATETIME NULL,
    version BIGINT NOT NULL DEFAULT 0,
    supplier_company_id BIGINT NULL,
    purchase_order_id BIGINT NULL,
    contract_id BIGINT NULL,
    match_status VARCHAR(20) NULL,
    actor_type VARCHAR(20) NULL,
    confirmation_source VARCHAR(40) NULL,
    human_user_id BIGINT NULL,
    correlation_id VARCHAR(128) NULL,
    idempotency_key VARCHAR(190) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(50) NULL,
    deleted_flag TINYINT(1) DEFAULT 0,
    -- V108.3: 有効 SEND の UNIQUE（CANCELLED/REVOKED は NULL スロットで再 Queue 可）
    send_active_slot TINYINT GENERATED ALWAYS AS (
        CASE
            WHEN direction = 'SEND'
             AND deleted_flag = 0
             AND status NOT IN ('CANCELLED', 'REVOKED')
            THEN 1
            ELSE NULL
        END
    ),
    UNIQUE KEY uk_digital_invoice_scope_message (tenant_id, legal_entity_id, message_id),
    UNIQUE KEY uk_digital_invoice_provider_message (provider_message_id),
    UNIQUE KEY uk_digital_invoice_scope_send (tenant_id, legal_entity_id, invoice_id, direction, profile, specification_version, send_active_slot),
    UNIQUE KEY uk_digital_invoice_scope_id (tenant_id, legal_entity_id, id),
    CONSTRAINT ck_digital_invoice_actor_pair CHECK (
        actor_type IS NOT NULL AND confirmation_source IS NOT NULL AND (
            (actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0)
            OR (actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL)
            OR (actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL)
            OR (actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL)
        )
    )
);

CREATE TABLE IF NOT EXISTS t_digital_invoice_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NULL,
    legal_entity_id BIGINT NULL,
    digital_invoice_id BIGINT NOT NULL,
    provider_event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_at DATETIME NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    canonical_payload_hash VARCHAR(64),
    signature_valid TINYINT(1) NOT NULL,
    actor_type VARCHAR(20) NULL,
    confirmation_source VARCHAR(40) NULL,
    human_user_id BIGINT NULL,
    correlation_id VARCHAR(128) NULL,
    idempotency_key VARCHAR(190) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    UNIQUE KEY uk_digital_invoice_event_provider (provider_event_id),
    CONSTRAINT fk_digital_invoice_event_scope
        FOREIGN KEY (tenant_id, legal_entity_id, digital_invoice_id)
        REFERENCES t_digital_invoice (tenant_id, legal_entity_id, id),
    CONSTRAINT ck_digital_invoice_event_actor_pair CHECK (
        actor_type IS NOT NULL AND confirmation_source IS NOT NULL AND (
            (actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0)
            OR (actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL)
            OR (actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL)
            OR (actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL)
        )
    )
);

CREATE TABLE IF NOT EXISTS t_nf09_scope_repair_queue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(40) NOT NULL,
    entity_id BIGINT NOT NULL,
    reason VARCHAR(80) NOT NULL,
    candidate_tenant_id VARCHAR(100) NULL,
    candidate_legal_entity_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    version INT NOT NULL DEFAULT 0,
    resolved_at DATETIME NULL,
    resolved_by BIGINT NULL,
    resolution_note VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_nf09_scope_repair_entity UNIQUE (entity_type, entity_id)
);

ALTER TABLE m_customer ADD COLUMN IF NOT EXISTS delivery_preference VARCHAR(20) NOT NULL DEFAULT 'PDF';
