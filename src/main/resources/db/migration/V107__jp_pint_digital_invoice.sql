-- ============================================================
-- SES Manager Pro - JP PINT Digital Invoice (T103)
-- ファイル: V107__jp_pint_digital_invoice.sql
-- 説明: 顧客のPeppol参加者情報、デジタルインボイス送受信履歴、Webhookイベント記録
-- ============================================================

-- m_customer に送付方法を追加
ALTER TABLE m_customer
  ADD COLUMN delivery_preference VARCHAR(20) NOT NULL DEFAULT 'PDF' COMMENT '送付方法(PDF/EMAIL/PEPPOL)';

-- ============================================================
-- t_peppol_participant (Peppol参加者情報)
-- ============================================================
CREATE TABLE t_peppol_participant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_type VARCHAR(50) NOT NULL COMMENT 'CUSTOMER or ORGANIZATION',
    owner_id BIGINT NOT NULL,
    scheme_id VARCHAR(50) NOT NULL,
    participant_id VARCHAR(100) NOT NULL,
    provider VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL,
    verified_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(50) NULL,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_peppol_participant_owner (owner_type, owner_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Peppol参加者情報';

-- ============================================================
-- t_digital_invoice (デジタルインボイス送受信管理)
-- ============================================================
CREATE TABLE t_digital_invoice (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    invoice_id BIGINT NULL COMMENT '既存請求書ID。NULLの場合は受信インボイス',
    direction VARCHAR(20) NOT NULL COMMENT 'SEND or RECEIVE',
    profile VARCHAR(50) NOT NULL COMMENT 'JP PINT Profile (e.g. Standard)',
    specification_version VARCHAR(20) NOT NULL COMMENT '使用したJP PINT specification version',
    message_id VARCHAR(100) NOT NULL,
    provider_message_id VARCHAR(100) NULL,
    xml_document_id BIGINT NULL COMMENT '生成されたXMLのDocumentArchive ID',
    validation_document_id BIGINT NULL COMMENT 'Validation reportのDocumentArchive ID',
    status VARCHAR(20) NOT NULL COMMENT 'QUEUED, SENT, DELIVERED, REJECTED, FAILED, CANCELLED, RECEIVED',
    sent_at DATETIME NULL,
    received_at DATETIME NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    updated_by VARCHAR(50) NULL,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_digital_invoice_message (message_id),
    UNIQUE KEY uk_digital_invoice_send (invoice_id, direction, specification_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='デジタルインボイス送受信管理';

-- ============================================================
-- t_digital_invoice_event (デジタルインボイスWebhookイベント)
-- ============================================================
CREATE TABLE t_digital_invoice_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    digital_invoice_id BIGINT NOT NULL,
    provider_event_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    event_at DATETIME NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    signature_valid TINYINT(1) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(50) NULL,
    UNIQUE KEY uk_digital_invoice_event_provider (provider_event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='デジタルインボイスWebhookイベント';
