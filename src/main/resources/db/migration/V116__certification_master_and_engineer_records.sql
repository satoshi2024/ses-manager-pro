-- ===================================================================
-- V116: NF-03 F1-1 資格master・engineer取得record（PII: AES-256-GCM）
-- ===================================================================

CREATE TABLE IF NOT EXISTS m_certification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    issuer_key VARCHAR(200) NOT NULL COMMENT '正規化issuer',
    external_code_key VARCHAR(200) NULL COMMENT '正規化外部コード',
    name_key VARCHAR(200) NOT NULL COMMENT '正規化名称',
    identity_key VARCHAR(128) NOT NULL COMMENT 'issuer+codeまたはissuer+name hash',
    display_name VARCHAR(200) NOT NULL COMMENT '表示名',
    issuer_display VARCHAR(200) NULL COMMENT '発行元表示名',
    external_code VARCHAR(100) NULL COMMENT '外部資格コード（業務データ）',
    expiry_type VARCHAR(30) NOT NULL DEFAULT 'NONE' COMMENT 'NONE, FIXED_MONTHS, EXPLICIT_DATE',
    expiry_months INT NULL COMMENT 'FIXED_MONTHS時の月数',
    rule_version INT NOT NULL DEFAULT 1 COMMENT '期限規則版',
    active_flag TINYINT NOT NULL DEFAULT 1 COMMENT '1=有効master',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_cert_tenant_identity (tenant_id, identity_key, deleted_flag),
    INDEX idx_cert_tenant_active (tenant_id, active_flag, deleted_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資格master';

CREATE TABLE IF NOT EXISTS m_certification_alias (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    certification_id BIGINT NOT NULL COMMENT 'm_certification.id',
    alias_issuer_key VARCHAR(200) NULL,
    alias_name_key VARCHAR(200) NOT NULL,
    normalized_key VARCHAR(256) NOT NULL,
    valid_from DATE NULL,
    valid_to DATE NULL,
    approved_by BIGINT NULL COMMENT 'HR/admin承認者',
    approved_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_cert_alias_norm (tenant_id, normalized_key, deleted_flag),
    INDEX idx_cert_alias_cert (certification_id, deleted_flag),
    CONSTRAINT fk_cert_alias_cert FOREIGN KEY (certification_id) REFERENCES m_certification(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資格名称alias';

CREATE TABLE IF NOT EXISTS t_engineer_certification (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    engineer_id BIGINT NOT NULL,
    certification_id BIGINT NOT NULL,
    continuity_group_id BIGINT NOT NULL COMMENT 'renew履歴グループ',
    acquired_on DATE NOT NULL,
    expires_on DATE NULL,
    expiry_rule_version INT NOT NULL DEFAULT 1,
    certificate_number_encrypted VARBINARY(512) NULL COMMENT 'AES-256-GCM ciphertext+tag（IVは別管理しないCNF1バイナリ）',
    certificate_number_key_version VARCHAR(64) NULL,
    certificate_number_cipher_format VARCHAR(16) NULL COMMENT 'CNF1',
    certificate_number_masked VARCHAR(64) NULL COMMENT '復号なし表示用mask',
    record_state VARCHAR(30) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT,SUBMITTED,VERIFIED,ACTIVE,CANCELLED,SUPERSEDED',
    current_flag TINYINT NOT NULL DEFAULT 0 COMMENT '1=continuity group内current',
    current_holder_key BIGINT NULL COMMENT 'current_flag=1時continuity_group_id、他NULL。一意current用',
    revision INT NOT NULL DEFAULT 1,
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_eng_cert_current_holder (tenant_id, engineer_id, certification_id, current_holder_key),
    UNIQUE KEY uk_eng_cert_active_acquisition (tenant_id, engineer_id, certification_id, acquired_on, record_state, deleted_flag),
    INDEX idx_eng_cert_engineer (tenant_id, engineer_id, deleted_flag),
    INDEX idx_eng_cert_expires (tenant_id, expires_on, record_state, deleted_flag),
    CONSTRAINT fk_eng_cert_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
    CONSTRAINT fk_eng_cert_cert FOREIGN KEY (certification_id) REFERENCES m_certification(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='engineer資格取得record';
