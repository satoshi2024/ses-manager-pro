-- H2 Schema for JP PINT Digital Invoice (T103)

CREATE TABLE IF NOT EXISTS t_peppol_participant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    UNIQUE KEY uk_peppol_participant_owner (owner_type, owner_id)
);

CREATE TABLE IF NOT EXISTS t_digital_invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
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
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(50) NULL,
    deleted_flag TINYINT(1) DEFAULT 0,
    UNIQUE KEY uk_digital_invoice_message (message_id),
    UNIQUE KEY uk_digital_invoice_send (invoice_id, direction, specification_version)
);

CREATE TABLE IF NOT EXISTS t_digital_invoice_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    digital_invoice_id BIGINT NOT NULL,
    provider_event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_at DATETIME NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    signature_valid TINYINT(1) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    UNIQUE KEY uk_digital_invoice_event_provider (provider_event_id)
);
