-- V106: 会計・支払連携基盤 (accounting-payment-integration / S15)
-- connection / mapping / job / job_event DDL 及び既存接続移行

CREATE TABLE IF NOT EXISTS m_integration_connection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    legal_entity_id BIGINT NULL COMMENT '法人ID (NULL=共通/全社)',
    provider VARCHAR(32) NOT NULL COMMENT 'プロバイダ (freee / csv / mock)',
    product VARCHAR(32) NOT NULL COMMENT 'プロダクト種別 (accounting / payroll)',
    external_company_id BIGINT NULL COMMENT '外部事業所ID (freee company_id等)',
    company_name VARCHAR(255) NULL COMMENT '外部事業所/会社名',
    encrypted_tokens TEXT NULL COMMENT '暗号化されたアクセストークン/リフレッシュトークンJSON',
    expires_at DATETIME NULL COMMENT 'トークン有効期限',
    status VARCHAR(32) NOT NULL DEFAULT 'CONNECTED' COMMENT '接続状態 (CONNECTED / REAUTH_REQUIRED / DISCONNECTED)',
    connected_by BIGINT NULL COMMENT '接続実行ユーザーID',
    connected_at DATETIME NULL COMMENT '接続日時',
    last_refreshed_at DATETIME NULL COMMENT 'トークン最終リフレッシュ日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_int_conn (tenant_id, legal_entity_id, provider, product, deleted_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部サービス連携接続マスタ';

CREATE TABLE IF NOT EXISTS m_external_mapping (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id BIGINT NOT NULL COMMENT '接続ID (m_integration_connection.id)',
    object_type VARCHAR(64) NOT NULL COMMENT 'マッピング対象種別 (CUSTOMER_PARTNER, BP_PARTNER, ACCOUNT_SALES, TAX_SALES_10 等)',
    internal_id BIGINT NULL COMMENT '内部エンティティID',
    internal_code VARCHAR(64) NOT NULL COMMENT '内部コード/キー',
    external_id VARCHAR(64) NOT NULL COMMENT '外部システムID',
    external_code VARCHAR(64) NULL COMMENT '外部システムコード',
    payload_snapshot TEXT NULL COMMENT '検証時点の外部マスタスナップショットJSON',
    verified_at DATETIME NULL COMMENT '検証日時 (NULL=未検証、送信不可)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ext_mapping (connection_id, object_type, internal_code, deleted_flag),
    INDEX idx_ext_mapping_conn (connection_id, object_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部マスタマッピング';

CREATE TABLE IF NOT EXISTS t_integration_job (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id BIGINT NOT NULL COMMENT '接続ID',
    job_type VARCHAR(64) NOT NULL COMMENT 'ジョブ種別 (SALES_INVOICE_SYNC, SALES_INVOICE_CANCEL, PURCHASE_DEAL_SYNC, EXPENSE_DEAL_SYNC, PAYMENT_SYNC)',
    target_type VARCHAR(64) NOT NULL COMMENT '対象種別 (INVOICE, BP_PAYMENT, EXPENSE_REQUEST, PAYMENT)',
    target_id BIGINT NOT NULL COMMENT '対象エンティティID',
    idempotency_key VARCHAR(128) NOT NULL COMMENT '冪等性キー',
    payload_hash VARCHAR(64) NOT NULL COMMENT '送信ペイロードSHA-256ハッシュ',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状態 (PENDING / RUNNING / SUCCEEDED / RETRYABLE / FAILED / CANCELLED)',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT '試行回数',
    max_attempts INT NOT NULL DEFAULT 5 COMMENT '最大試行回数',
    next_retry_at DATETIME NULL COMMENT '次回再試行予定日時',
    external_id VARCHAR(128) NULL COMMENT '外部取引/伝票ID',
    provider_request_id VARCHAR(128) NULL COMMENT '外部リクエストID (X-Freee-Request-ID等)',
    error_code VARCHAR(64) NULL COMMENT '分類エラーコード',
    error_message_safe VARCHAR(500) NULL COMMENT '安全なエラー要約 (PII/Secret除外)',
    sent_at DATETIME NULL COMMENT '送信成功日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 0,
    UNIQUE KEY uk_int_job_idempotency (idempotency_key, deleted_flag),
    INDEX idx_int_job_status (status, next_retry_at),
    INDEX idx_int_job_target (target_type, target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部連携非同期ジョブ (Outbox)';

CREATE TABLE IF NOT EXISTS t_integration_job_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id BIGINT NOT NULL COMMENT 'ジョブID',
    from_status VARCHAR(32) NULL COMMENT '遷移前状態',
    to_status VARCHAR(32) NOT NULL COMMENT '遷移後状態',
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '発生日時',
    safe_detail VARCHAR(1000) NULL COMMENT '安全な詳細情報 (PII/Secret除外)',
    INDEX idx_job_event_job_id (job_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='連携ジョブ状態遷移履歴';

-- 既存 t_freee_connection からの段階的移行
INSERT INTO m_integration_connection (
    tenant_id, legal_entity_id, provider, product, external_company_id,
    company_name, encrypted_tokens, expires_at, status, connected_by, connected_at
)
SELECT
    'default', NULL, 'freee', 'payroll', c.company_id,
    c.company_name,
    JSON_OBJECT('accessToken', c.access_token_encrypted, 'refreshToken', c.refresh_token_encrypted),
    c.token_expires_at,
    COALESCE(c.connection_status, 'CONNECTED'),
    c.connected_by,
    c.created_at
FROM t_freee_connection c
WHERE c.deleted_flag = 0
  AND NOT EXISTS (
      SELECT 1 FROM m_integration_connection ic
      WHERE ic.provider = 'freee' AND ic.product = 'payroll' AND ic.deleted_flag = 0
  );

-- freee accounting 用の初期レコード（未接続時でも管理・設定可能にするため、なければ mock/初期 connection を作成）
INSERT INTO m_integration_connection (
    tenant_id, legal_entity_id, provider, product, external_company_id,
    company_name, encrypted_tokens, expires_at, status
)
SELECT 'default', NULL, 'freee', 'accounting', NULL, NULL, NULL, NULL, 'DISCONNECTED'
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM m_integration_connection WHERE provider = 'freee' AND product = 'accounting' AND deleted_flag = 0
);

-- m_menu seed（accounting-integration。管理者・マネージャー向け）
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('accounting-integration', '会計・支払連携', '/accounting/integration', '/api/accounting', 98)
ON DUPLICATE KEY UPDATE menu_name = VALUES(menu_name);

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'accounting-integration'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu tr WHERE tr.role = r.role AND tr.menu_id = m.id);

-- accounting resourceの権限seed
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (SELECT 'accounting.*' AS action_key) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('ADMIN', 'EXECUTIVE', 'MANAGER', 'role-admin', 'role-manager');
