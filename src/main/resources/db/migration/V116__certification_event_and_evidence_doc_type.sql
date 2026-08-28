-- ===================================================================
-- V116: NF-03 F1-2 資格event履歴と証憑文書種別
-- ===================================================================

CREATE TABLE IF NOT EXISTS t_certification_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    certification_record_id BIGINT NOT NULL COMMENT 't_engineer_certification.id',
    event_type VARCHAR(50) NOT NULL COMMENT 'SUBMIT,VERIFY,CORRECT,CANCEL,RENEW,...',
    supersedes_event_id BIGINT NULL COMMENT '訂正・取消の直前event',
    reason VARCHAR(2000) NULL COMMENT 'CORRECT/CANCEL時必須',
    actor_user_id BIGINT NULL,
    actor_role_snapshot VARCHAR(50) NULL,
    occurred_at DATETIME NOT NULL COMMENT '発生日時',
    effective_record_state VARCHAR(30) NULL COMMENT 'event反映後のrecord_state',
    effective_acquired_on DATE NULL,
    effective_expires_on DATE NULL,
    evidence_document_id BIGINT NULL COMMENT 't_document.id',
    evidence_document_version_id BIGINT NULL COMMENT 't_document_version.id（exact version）',
    evidence_document_hash VARCHAR(64) NULL COMMENT 'SHA-256 hex',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_cert_event_record (tenant_id, certification_record_id, occurred_at),
    INDEX idx_cert_event_type (tenant_id, event_type, occurred_at),
    CONSTRAINT fk_cert_event_record FOREIGN KEY (certification_record_id) REFERENCES t_engineer_certification(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資格取得recordのappend-only event';

INSERT IGNORE INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported) VALUES
  ('CERTIFICATION_EVIDENCE', '資格証憑', 'INCOMING', 7, 'TRANSACTION_DATE', 1);
