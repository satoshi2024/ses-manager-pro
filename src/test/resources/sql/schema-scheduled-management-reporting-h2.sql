-- テスト用: V112__scheduled_management_reporting.sql のH2相当。
-- MySQL固有DDLを混ぜず、共有H2で再実行可能な形にする。

DROP TABLE IF EXISTS t_report_delivery CASCADE;
DROP TABLE IF EXISTS t_report_section_attempt CASCADE;
DROP TABLE IF EXISTS t_report_section_snapshot CASCADE;
DROP TABLE IF EXISTS t_report_run CASCADE;
DROP TABLE IF EXISTS m_report_schedule CASCADE;
DROP TABLE IF EXISTS m_report_template_version CASCADE;
DROP TABLE IF EXISTS m_report_template CASCADE;

CREATE TABLE m_report_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    template_key VARCHAR(100) NOT NULL,
    template_name VARCHAR(200) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_report_template_key ON m_report_template(tenant_id, template_key);
CREATE INDEX idx_report_template_status ON m_report_template(tenant_id, status, deleted_flag);

CREATE TABLE m_report_template_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    template_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    section_config_json CLOB NOT NULL,
    format_config_json CLOB NOT NULL,
    recipient_config_json CLOB NOT NULL,
    scope_config_json CLOB NOT NULL,
    timezone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Tokyo',
    retention_years INT NOT NULL DEFAULT 7,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP,
    deleted_flag TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_report_template_version ON m_report_template_version(template_id, version_no);
CREATE INDEX idx_report_template_version_status ON m_report_template_version(tenant_id, template_id, status, deleted_flag);

CREATE TABLE m_report_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    template_version_id BIGINT NOT NULL,
    cron_expression VARCHAR(100) NOT NULL,
    timezone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Tokyo',
    enabled TINYINT NOT NULL DEFAULT 0,
    lock_key VARCHAR(190) NOT NULL,
    next_run_at TIMESTAMP,
    last_run_at TIMESTAMP,
    scope_owner_type VARCHAR(30),
    scope_owner_id BIGINT,
    organization_scope_json CLOB,
    scope_policy_version VARCHAR(100),
    scope_hash VARCHAR(128),
    retry_scheduled_at TIMESTAMP,
    processing_logical_run_at TIMESTAMP,
    processing_claimed_at TIMESTAMP,
    failure_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(100),
    last_error_message VARCHAR(500),
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by BIGINT,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_report_schedule_lock_key ON m_report_schedule(tenant_id, lock_key);
CREATE INDEX idx_report_schedule_due ON m_report_schedule(enabled, next_run_at, deleted_flag);
CREATE INDEX idx_report_schedule_processing ON m_report_schedule(enabled, processing_claimed_at, deleted_flag);

CREATE TABLE t_report_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    run_key VARCHAR(190) NOT NULL,
    template_id BIGINT NOT NULL,
    template_version_id BIGINT NOT NULL,
    schedule_id BIGINT,
    regeneration_of_run_id BIGINT,
    snapshot_version INT NOT NULL DEFAULT 1,
    principal_type VARCHAR(30) NOT NULL,
    principal_user_id BIGINT,
    scope_owner_type VARCHAR(30) NOT NULL,
    scope_owner_id BIGINT,
    organization_scope_json CLOB NOT NULL,
    scope_policy_version VARCHAR(100) NOT NULL,
    scope_hash VARCHAR(128) NOT NULL,
    period_from DATE NOT NULL,
    period_to DATE NOT NULL,
    cutoff_kind VARCHAR(30) NOT NULL,
    as_of_at TIMESTAMP NOT NULL,
    timezone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Tokyo',
    data_as_of_at TIMESTAMP,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    snapshot_schema_version VARCHAR(30) NOT NULL DEFAULT 'report-1.0',
    source_policy_hash VARCHAR(128),
    failure_code VARCHAR(100),
    failure_message VARCHAR(500),
    generated_at TIMESTAMP,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_report_run_key ON t_report_run(tenant_id, run_key);
CREATE INDEX idx_report_run_history ON t_report_run(tenant_id, period_from, period_to, status);
CREATE INDEX idx_report_run_template ON t_report_run(template_id, template_version_id);

CREATE TABLE t_report_section_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    run_id BIGINT NOT NULL,
    section_key VARCHAR(100) NOT NULL,
    section_status VARCHAR(30) NOT NULL,
    fact_type VARCHAR(20) NOT NULL,
    confirmation VARCHAR(20) NOT NULL,
    period_from DATE,
    period_to DATE,
    cutoff_kind VARCHAR(30),
    as_of_at TIMESTAMP,
    data_as_of_at TIMESTAMP,
    freshness_status VARCHAR(20),
    canonical_service VARCHAR(200),
    canonical_dto VARCHAR(200),
    adapter_version VARCHAR(100),
    source_row_count BIGINT,
    source_hash VARCHAR(128),
    value_json CLOB,
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    snapshot_hash VARCHAR(128) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_report_section_snapshot ON t_report_section_snapshot(run_id, section_key);
CREATE INDEX idx_report_section_snapshot_status ON t_report_section_snapshot(tenant_id, section_status);
CREATE INDEX idx_report_section_snapshot_hash ON t_report_section_snapshot(snapshot_hash);

CREATE TABLE t_report_section_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    run_id BIGINT NOT NULL,
    section_key VARCHAR(100) NOT NULL,
    attempt_no INT NOT NULL,
    section_status VARCHAR(30) NOT NULL,
    fact_type VARCHAR(20),
    confirmation VARCHAR(20),
    period_from DATE,
    period_to DATE,
    cutoff_kind VARCHAR(30),
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NOT NULL,
    data_as_of_at TIMESTAMP,
    freshness_status VARCHAR(20),
    canonical_service VARCHAR(200),
    canonical_dto VARCHAR(200),
    source_row_count BIGINT,
    source_hash VARCHAR(128),
    value_json CLOB,
    error_code VARCHAR(100),
    error_message VARCHAR(500),
    snapshot_hash VARCHAR(128),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE INDEX idx_report_section_attempt_run ON t_report_section_attempt(run_id, section_key, attempt_no);
CREATE INDEX idx_report_section_attempt_status ON t_report_section_attempt(tenant_id, section_status);

CREATE TABLE t_report_delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    run_id BIGINT NOT NULL,
    document_id BIGINT,
    document_version_no INT,
    notification_outbox_id BIGINT,
    recipient_user_id BIGINT NOT NULL,
    organization_id BIGINT,
    recipient_scope_json CLOB NOT NULL,
    recipient_scope_hash VARCHAR(128) NOT NULL,
    preview_status VARCHAR(30) NOT NULL,
    previewed_at TIMESTAMP,
    scope_decision VARCHAR(20) NOT NULL,
    delivery_channel VARCHAR(50) NOT NULL DEFAULT 'IN_APP_LINK',
    delivery_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    notification_dedupe_key VARCHAR(190) NOT NULL,
    link_token_hash VARCHAR(128),
    link_expires_at TIMESTAMP,
    reauth_required TINYINT NOT NULL DEFAULT 1,
    reauthenticated_at TIMESTAMP,
    attempt_count INT NOT NULL DEFAULT 0,
    downloaded_at TIMESTAMP,
    last_error_code VARCHAR(100),
    last_error_message VARCHAR(500),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_report_delivery_recipient ON t_report_delivery(run_id, recipient_user_id);
CREATE UNIQUE INDEX uk_report_delivery_dedupe ON t_report_delivery(tenant_id, notification_dedupe_key);
CREATE INDEX idx_report_delivery_status ON t_report_delivery(tenant_id, delivery_status, link_expires_at);
CREATE INDEX idx_report_delivery_document ON t_report_delivery(document_id);
CREATE INDEX idx_report_delivery_outbox ON t_report_delivery(notification_outbox_id);

INSERT INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
SELECT 'MANAGEMENT_REPORT', '月次管理レポート', 'INTERNAL', 7, 'TRANSACTION_DATE', 1
WHERE NOT EXISTS (SELECT 1 FROM m_document_type WHERE code = 'MANAGEMENT_REPORT');

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'management-report', '定期管理レポート', '/management-reports', '/api/management-reports', 96
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'management-report');

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'management-report'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu x WHERE x.role = r.role AND x.menu_id = m.id);
