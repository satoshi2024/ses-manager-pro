-- ===================================================================
-- NF-09 資産・外部アカウント・ライセンス管理（H2 テストスキーマ）
-- ===================================================================

CREATE TABLE IF NOT EXISTS t_asset_offboarding_waiver (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    engineer_id BIGINT NOT NULL,
    lifecycle_case_id BIGINT,
    lifecycle_task_id BIGINT,
    approval_request_id BIGINT NOT NULL UNIQUE,
    reason VARCHAR(1000) NOT NULL,
    approved_by BIGINT,
    approved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
);

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

CREATE TABLE IF NOT EXISTS t_asset_lost_incident (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL,
    reported_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reported_by BIGINT,
    incident_details VARCHAR(2000),
    remote_wipe_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    remote_wipe_requested_at TIMESTAMP,
    remote_wipe_executed_at TIMESTAMP,
    remote_wipe_confirmed_at TIMESTAMP,
    police_report_number VARCHAR(128),
    insurance_claim_status VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLIED',
    insurance_claimed_at TIMESTAMP,
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE (asset_id)
);

CREATE TABLE IF NOT EXISTS t_asset_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT,
    reference_type VARCHAR(64),
    reference_id BIGINT,
    event_type VARCHAR(64) NOT NULL,
    event_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_user_id BIGINT,
    actor_type VARCHAR(32),
    confirmation_source VARCHAR(32),
    human_user_id BIGINT,
    assignee_type VARCHAR(32),
    assignee_id BIGINT,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    evidence_doc_id BIGINT,
    event_summary VARCHAR(255) NOT NULL,
    details_json TEXT,
    correlation_id VARCHAR(128),
    idempotency_key VARCHAR(128),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_asset_event_actor_type CHECK (actor_type IS NULL OR actor_type IN ('HUMAN', 'SYSTEM', 'PROVIDER', 'LEGACY_UNRESOLVED')),
    CONSTRAINT ck_asset_event_confirmation_source CHECK (confirmation_source IS NULL OR confirmation_source IN ('MANUAL_API', 'SCHEDULER_POLL', 'PROVIDER_SYNC', 'PROVIDER_CALLBACK', 'LEGACY_UNRESOLVED')),
    CONSTRAINT ck_asset_event_actor_pair CHECK (COALESCE((
        actor_type IS NULL AND confirmation_source IS NULL AND human_user_id IS NULL
        OR actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0 AND actor_user_id IS NOT NULL AND actor_user_id = human_user_id
        OR actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL AND actor_user_id IS NULL
        OR actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL AND actor_user_id IS NULL
        OR actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL AND actor_user_id IS NULL
    ), FALSE)),
    INDEX idx_event_asset (asset_id, event_time),
    INDEX idx_event_reference (reference_type, reference_id)
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
    provisioned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    idempotency_key VARCHAR(128) UNIQUE,
    retry_count INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP,
    last_error_message VARCHAR(500),
    revoke_requested_at TIMESTAMP,
    revoke_requested_by BIGINT,
    revoke_confirmed_at TIMESTAMP,
    revoke_confirmed_by BIGINT,
    actor_type VARCHAR(32),
    confirmation_source VARCHAR(32),
    revoke_confirmed_source VARCHAR(32),
    external_sync_status VARCHAR(32) DEFAULT 'NONE',
    sync_error_message VARCHAR(500),
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    CONSTRAINT ck_ext_revoke_actor_type CHECK (actor_type IS NULL OR actor_type IN ('HUMAN', 'SYSTEM', 'PROVIDER', 'LEGACY_UNRESOLVED')),
    CONSTRAINT ck_ext_revoke_confirmation_source CHECK (confirmation_source IS NULL OR confirmation_source IN ('MANUAL_API', 'SCHEDULER_POLL', 'PROVIDER_SYNC', 'PROVIDER_CALLBACK', 'LEGACY_UNRESOLVED')),
    CONSTRAINT ck_ext_revoke_attribution CHECK (COALESCE((
        revoke_confirmed_at IS NULL AND actor_type IS NULL AND confirmation_source IS NULL AND revoke_confirmed_by IS NULL AND revoke_confirmed_source IS NULL
        OR revoke_confirmed_at IS NOT NULL AND (
            actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND revoke_confirmed_by IS NOT NULL AND revoke_confirmed_by > 0
            OR actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND revoke_confirmed_by IS NULL
            OR actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND revoke_confirmed_by IS NULL
            OR actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND revoke_confirmed_by IS NULL
        ) AND revoke_confirmed_source = confirmation_source
    ), FALSE)),
    CONSTRAINT ck_ext_revoke_status_attribution CHECK (COALESCE(status <> 'REVOKED' OR (revoke_confirmed_at IS NOT NULL AND actor_type IS NOT NULL AND confirmation_source IS NOT NULL), FALSE))
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
