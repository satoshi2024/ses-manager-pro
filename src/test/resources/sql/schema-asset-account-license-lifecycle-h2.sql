-- ===================================================================
-- NF-09 資産・外部アカウント・ライセンス管理（H2 テストスキーマ）
-- ===================================================================

CREATE TABLE IF NOT EXISTS m_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_tag VARCHAR(64) NOT NULL UNIQUE,
    serial_no VARCHAR(128),
    asset_name VARCHAR(128) NOT NULL,
    category VARCHAR(32) NOT NULL,
    owner_company_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_STOCK',
    location VARCHAR(128),
    purchase_date DATE,
    purchase_price DECIMAL(12, 2),
    warranty_expiry DATE,
    lease_expiry DATE,
    note VARCHAR(1000),
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_asset_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    assignee_type VARCHAR(32) NOT NULL,
    assignee_id BIGINT NOT NULL,
    start_date DATE NOT NULL,
    expected_return_date DATE,
    actual_return_date DATE,
    handover_evidence_doc_id BIGINT,
    return_evidence_doc_id BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    note VARCHAR(500),
    version INT NOT NULL DEFAULT 0,
    created_by BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_asset_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    event_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_user_id BIGINT,
    assignee_type VARCHAR(32),
    assignee_id BIGINT,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    evidence_doc_id BIGINT,
    event_summary VARCHAR(255) NOT NULL,
    details_json TEXT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_asset_inventory_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inventory_code VARCHAR(64) NOT NULL UNIQUE,
    title VARCHAR(128) NOT NULL,
    target_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS',
    total_assets INT NOT NULL DEFAULT 0,
    matched_count INT NOT NULL DEFAULT 0,
    discrepancy_count INT NOT NULL DEFAULT 0,
    missing_count INT NOT NULL DEFAULT 0,
    conducted_by BIGINT,
    completed_at DATETIME,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_asset_inventory_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inventory_run_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    expected_status VARCHAR(32) NOT NULL,
    expected_location VARCHAR(128),
    observed_status VARCHAR(32),
    observed_location VARCHAR(128),
    discrepancy_type VARCHAR(32) NOT NULL DEFAULT 'UNCHECKED',
    discrepancy_reason VARCHAR(500),
    resolution_action VARCHAR(500),
    checked_by BIGINT,
    checked_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (inventory_run_id, asset_id)
);

CREATE TABLE IF NOT EXISTS m_external_account_system (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    system_code VARCHAR(64) NOT NULL UNIQUE,
    system_name VARCHAR(128) NOT NULL,
    system_type VARCHAR(32) NOT NULL,
    auth_type VARCHAR(32) NOT NULL DEFAULT 'SAML_OIDC',
    is_active INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_external_account_reference (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    system_id BIGINT NOT NULL,
    account_identifier VARCHAR(255) NOT NULL,
    assignee_type VARCHAR(32) NOT NULL,
    assignee_id BIGINT NOT NULL,
    permission_level VARCHAR(64),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    provisioned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoke_requested_at DATETIME,
    revoke_confirmed_at DATETIME,
    revoke_confirmed_by BIGINT,
    external_sync_status VARCHAR(32) DEFAULT 'NONE',
    sync_error_message VARCHAR(500),
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS m_license_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_code VARCHAR(64) NOT NULL UNIQUE,
    plan_name VARCHAR(128) NOT NULL,
    system_id BIGINT,
    seat_limit INT NOT NULL,
    allocated_count INT NOT NULL DEFAULT 0,
    cost_per_seat DECIMAL(12, 2),
    cost_center_id BIGINT,
    expiry_date DATE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS t_license_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL,
    assignee_type VARCHAR(32) NOT NULL,
    assignee_id BIGINT NOT NULL,
    account_reference_id BIGINT,
    assigned_date DATE NOT NULL,
    released_date DATE,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);
