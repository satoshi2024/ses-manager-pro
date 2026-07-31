-- H2 Schema for BP Company Master and Procurement Compliance

CREATE TABLE IF NOT EXISTS m_bp_company (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    legal_name VARCHAR(255) NOT NULL,
    name_kana VARCHAR(255),
    normalized_name VARCHAR(255),
    entity_type VARCHAR(50) NOT NULL,
    corporate_number VARCHAR(13),
    invoice_registration_number VARCHAR(14),
    capital_band VARCHAR(50),
    employee_band VARCHAR(50),
    address VARCHAR(500),
    representative VARCHAR(100),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    rating INT DEFAULT 0,
    primary_sales_user_id BIGINT,
    compliance_applicability VARCHAR(50),
    applicability_checked_by BIGINT,
    applicability_checked_at TIMESTAMP,
    applicability_note TEXT,
    version INT NOT NULL DEFAULT 1,
    created_by BIGINT,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, normalized_name)
);

CREATE TABLE IF NOT EXISTS t_bp_contact (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    bp_company_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    department VARCHAR(100),
    role VARCHAR(100),
    email VARCHAR(255),
    phone VARCHAR(50),
    primary_flag INT NOT NULL DEFAULT 0,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_bp_bank_account (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    bp_company_id BIGINT NOT NULL,
    bank_name VARCHAR(100),
    branch_name VARCHAR(100),
    account_type VARCHAR(20) DEFAULT 'ORDINARY',
    encrypted_account_number VARCHAR(500),
    account_holder VARCHAR(100),
    masked_label VARCHAR(100) NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    approval_status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    approved_by BIGINT,
    approved_at TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_bp_terms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    bp_company_id BIGINT NOT NULL,
    effective_from DATE NOT NULL,
    effective_to DATE,
    closing_day INT NOT NULL DEFAULT 31,
    payment_month_offset INT NOT NULL DEFAULT 1,
    payment_day INT NOT NULL DEFAULT 30,
    fee_bearer VARCHAR(20) NOT NULL DEFAULT 'PAYEE',
    payment_method VARCHAR(50) NOT NULL DEFAULT 'BANK_TRANSFER',
    fee_bearer_exception_reason VARCHAR(500),
    fee_bearer_approved_by BIGINT,
    fee_bearer_approved_at TIMESTAMP,
    max_payment_days INT NOT NULL DEFAULT 60,
    version INT NOT NULL DEFAULT 1,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_engineer_bp_affiliation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    engineer_id BIGINT NOT NULL,
    bp_company_id BIGINT NOT NULL,
    valid_from DATE NOT NULL,
    valid_to DATE,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_bp_evaluation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    bp_company_id BIGINT NOT NULL,
    period VARCHAR(20) NOT NULL,
    quality_score INT DEFAULT 0,
    response_score INT DEFAULT 0,
    retention_score INT DEFAULT 0,
    compliance_score INT DEFAULT 0,
    billing_accuracy_score INT DEFAULT 0,
    comment TEXT,
    evaluated_by BIGINT,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_bp_price_negotiation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL DEFAULT 1,
    bp_company_id BIGINT NOT NULL,
    requested_at DATE NOT NULL,
    responded_at DATE,
    status VARCHAR(50) NOT NULL DEFAULT 'REQUESTED',
    requested_amount DECIMAL(15, 2),
    agreed_amount DECIMAL(15, 2),
    summary TEXT,
    document_id BIGINT,
    deleted_flag INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE t_bp_availability ADD COLUMN IF NOT EXISTS bp_company_id BIGINT;
ALTER TABLE t_bp_payment ADD COLUMN IF NOT EXISTS bp_company_id BIGINT;
ALTER TABLE t_bp_payment ADD COLUMN IF NOT EXISTS bp_company_name_snapshot VARCHAR(255);
ALTER TABLE t_bp_payment ADD COLUMN IF NOT EXISTS terms_snapshot_json TEXT;

ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS contract_date DATE;
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS job_description VARCHAR(2000);
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS work_location VARCHAR(500);
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS inspection_due_date DATE;
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS payment_due_date DATE;
ALTER TABLE t_contract ADD COLUMN IF NOT EXISTS payment_method VARCHAR(50);

MERGE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order) KEY(menu_key)
VALUES ('bp-company', 'BP会社管理', '/bp-company', '/api/bp-companies', 45);

INSERT INTO t_role_menu (role, menu_id)
SELECT '管理者', id FROM m_menu WHERE menu_key = 'bp-company'
ON DUPLICATE KEY UPDATE role = VALUES(role);

INSERT INTO t_role_menu (role, menu_id)
SELECT '営業', id FROM m_menu WHERE menu_key = 'bp-company'
ON DUPLICATE KEY UPDATE role = VALUES(role);

INSERT INTO t_role_menu (role, menu_id)
SELECT 'マネージャー', id FROM m_menu WHERE menu_key = 'bp-company'
ON DUPLICATE KEY UPDATE role = VALUES(role);

MERGE INTO m_system_config (config_key, config_value, description) KEY(config_key)
VALUES ('procurement.payment-max-days', '60', '発注支払期日の法務設定上限（受領日からの日数）');
