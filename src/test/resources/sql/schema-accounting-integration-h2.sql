-- テスト用(冪等): V106__accounting_payment_integration.sql のDDL相当を共有インメモリH2へ適用する。
-- MySQL固有DDL(ENGINE/COLLATE/COMMENT)はH2方言へ読み替える（platform-invariants §4.3）。
-- 共有H2は複数contextでschema-locationsを再実行するため、冪等に再構築する。

DROP TABLE IF EXISTS t_integration_job_event CASCADE;
DROP TABLE IF EXISTS t_integration_job CASCADE;
DROP TABLE IF EXISTS m_external_mapping CASCADE;
DROP TABLE IF EXISTS m_integration_connection CASCADE;

CREATE TABLE m_integration_connection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default',
    legal_entity_id BIGINT,
    provider VARCHAR(32) NOT NULL,
    product VARCHAR(32) NOT NULL,
    external_company_id BIGINT,
    company_name VARCHAR(255),
    encrypted_tokens TEXT,
    expires_at DATETIME,
    status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED',
    connected_by BIGINT,
    connected_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_int_conn ON m_integration_connection (tenant_id, legal_entity_id, provider, product, deleted_flag);

CREATE TABLE m_external_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id BIGINT NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    internal_id BIGINT,
    internal_code VARCHAR(64) NOT NULL,
    external_id VARCHAR(64) NOT NULL,
    external_code VARCHAR(64),
    payload_snapshot TEXT,
    verified_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_ext_mapping ON m_external_mapping (connection_id, object_type, internal_code, deleted_flag);
CREATE INDEX idx_ext_mapping_conn ON m_external_mapping (connection_id, object_type);

CREATE TABLE t_integration_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id BIGINT NOT NULL,
    job_type VARCHAR(64) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 5,
    next_retry_at DATETIME,
    external_id VARCHAR(128),
    provider_request_id VARCHAR(128),
    error_code VARCHAR(64),
    error_message_safe VARCHAR(500),
    sent_at DATETIME,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0
);
CREATE UNIQUE INDEX uk_int_job_idempotency ON t_integration_job (idempotency_key, deleted_flag);
CREATE INDEX idx_int_job_status ON t_integration_job (status, next_retry_at);
CREATE INDEX idx_int_job_target ON t_integration_job (target_type, target_id);

CREATE TABLE t_integration_job_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    occurred_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    safe_detail VARCHAR(1000)
);
CREATE INDEX idx_job_event_job_id ON t_integration_job_event (job_id);

-- 初期 connection シード
INSERT INTO m_integration_connection (
    tenant_id, legal_entity_id, provider, product, external_company_id,
    company_name, encrypted_tokens, expires_at, status
)
SELECT 'default', NULL, 'freee', 'accounting', NULL, NULL, NULL, NULL, 'DISCONNECTED'
WHERE NOT EXISTS (
    SELECT 1 FROM m_integration_connection WHERE provider = 'freee' AND product = 'accounting' AND deleted_flag = 0
);

-- menu seed（accounting-integration）
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'accounting-integration', '会計・支払連携', '/accounting/integration', '/api/accounting', 98
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'accounting-integration');

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'accounting-integration'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu tr WHERE tr.role = r.role AND tr.menu_id = m.id);
