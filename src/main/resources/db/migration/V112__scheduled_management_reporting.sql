-- ============================================================
-- V112: 定期管理レポート（NF-10 / DG-10）
--
-- 利用者=管理者・マネージャー、管理者=全社、マネージャー=許可組織
-- 月次 / Asia/Tokyo / snapshot・document 7年保持
-- 速報/確定、recipient preview、outbox delivery、期限付きlink
--
-- report側は集計式を持たず、section valueを不変snapshotとして保存する。
-- retryは(run_id, section_key)の一意制約へ収束させ、明示的な再生成だけ新run/versionを作る。
-- ============================================================

CREATE TABLE IF NOT EXISTS m_report_template (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'レポートテンプレートID',
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    template_key VARCHAR(100) NOT NULL COMMENT 'テンプレート業務キー',
    template_name VARCHAR(200) NOT NULL COMMENT 'テンプレート名',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/ACTIVE/ARCHIVED',
    created_by BIGINT NULL COMMENT '作成者',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    updated_by BIGINT NULL COMMENT '更新者',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック',
    UNIQUE KEY uk_report_template_key (tenant_id, template_key),
    INDEX idx_report_template_status (tenant_id, status, deleted_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理レポートテンプレート';

CREATE TABLE IF NOT EXISTS m_report_template_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'テンプレート版ID',
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    template_id BIGINT NOT NULL COMMENT 'm_report_template.id',
    version_no INT NOT NULL COMMENT 'テンプレート版番号',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT/PUBLISHED/ARCHIVED',
    section_config_json LONGTEXT NOT NULL COMMENT 'section定義JSON',
    format_config_json LONGTEXT NOT NULL COMMENT 'format定義JSON',
    recipient_config_json LONGTEXT NOT NULL COMMENT 'recipient定義JSON',
    scope_config_json LONGTEXT NOT NULL COMMENT 'scope定義JSON',
    timezone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Tokyo' COMMENT '業務timezone',
    retention_years INT NOT NULL DEFAULT 7 COMMENT '保存年数',
    created_by BIGINT NULL COMMENT '作成者',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    published_at DATETIME NULL COMMENT '公開日時',
    deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック',
    UNIQUE KEY uk_report_template_version (template_id, version_no),
    INDEX idx_report_template_version_status (tenant_id, template_id, status, deleted_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理レポートテンプレート版';

CREATE TABLE IF NOT EXISTS m_report_schedule (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'レポートschedule ID',
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    template_version_id BIGINT NOT NULL COMMENT 'm_report_template_version.id',
    cron_expression VARCHAR(100) NOT NULL COMMENT '実行cron',
    timezone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Tokyo' COMMENT 'schedule timezone',
    enabled TINYINT NOT NULL DEFAULT 0 COMMENT '管理者が有効化したか',
    lock_key VARCHAR(190) NOT NULL COMMENT 'scheduler冪等lock key',
    next_run_at DATETIME NULL COMMENT '次回実行予定',
    last_run_at DATETIME NULL COMMENT '最終実行日時',
    scope_owner_type VARCHAR(30) NULL COMMENT '保存済みscope owner種別',
    scope_owner_id BIGINT NULL COMMENT '保存済みscope owner',
    organization_scope_json LONGTEXT NULL COMMENT 'schedule作成時scope',
    scope_policy_version VARCHAR(100) NULL COMMENT 'schedule作成時policy',
    scope_hash VARCHAR(128) NULL COMMENT 'schedule作成時scope hash',
    retry_scheduled_at DATETIME NULL COMMENT '失敗した論理実行時刻',
    failure_count INT NOT NULL DEFAULT 0 COMMENT 'schedule生成失敗回数',
    last_error_code VARCHAR(100) NULL COMMENT '安全化済み失敗分類',
    last_error_message VARCHAR(500) NULL COMMENT '安全化済み失敗message',
    created_by BIGINT NULL COMMENT '作成者',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    updated_by BIGINT NULL COMMENT '更新者',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック',
    UNIQUE KEY uk_report_schedule_lock_key (tenant_id, lock_key),
    INDEX idx_report_schedule_due (enabled, next_run_at, deleted_flag)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理レポートschedule';

CREATE TABLE IF NOT EXISTS t_report_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'レポートrun ID',
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    run_key VARCHAR(190) NOT NULL COMMENT '同一対象のrun冪等キー',
    template_id BIGINT NOT NULL COMMENT 'template ID',
    template_version_id BIGINT NOT NULL COMMENT 'template version ID',
    schedule_id BIGINT NULL COMMENT 'schedule ID',
    regeneration_of_run_id BIGINT NULL COMMENT '明示再生成元run',
    snapshot_version INT NOT NULL DEFAULT 1 COMMENT '同一対象のsnapshot版',
    principal_type VARCHAR(30) NOT NULL COMMENT 'SYSTEM_PRINCIPAL',
    principal_user_id BIGINT NULL COMMENT '明示system principalの監査ID',
    scope_owner_type VARCHAR(30) NOT NULL COMMENT 'COMPANY/ORGANIZATION',
    scope_owner_id BIGINT NULL COMMENT '組織scope owner',
    organization_scope_json LONGTEXT NOT NULL COMMENT '保存済み組織scope',
    scope_policy_version VARCHAR(100) NOT NULL COMMENT 'scope policy version',
    scope_hash VARCHAR(128) NOT NULL COMMENT 'scope hash',
    period_from DATE NOT NULL COMMENT '対象期間開始（両端含む）',
    period_to DATE NOT NULL COMMENT '対象期間終了（両端含む）',
    cutoff_kind VARCHAR(30) NOT NULL COMMENT 'MONTHLY_CLOSING/GENERATED_AT',
    as_of_at DATETIME NOT NULL COMMENT '集計as-of',
    timezone_id VARCHAR(64) NOT NULL DEFAULT 'Asia/Tokyo' COMMENT '業務timezone',
    data_as_of_at DATETIME NULL COMMENT 'データ基準時刻',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/RUNNING/SUCCEEDED/PARTIAL/FAILED',
    snapshot_schema_version VARCHAR(30) NOT NULL DEFAULT 'report-1.0' COMMENT 'snapshot schema version',
    source_policy_hash VARCHAR(128) NULL COMMENT '正本・policy hash',
    failure_code VARCHAR(100) NULL COMMENT 'PIIを含まない失敗分類',
    failure_message VARCHAR(500) NULL COMMENT '安全化済み失敗メッセージ',
    generated_at DATETIME NULL COMMENT '生成完了日時',
    created_by BIGINT NULL COMMENT '作成者/system principal',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除',
    version INT NOT NULL DEFAULT 0 COMMENT '状態CAS用楽観ロック',
    UNIQUE KEY uk_report_run_key (tenant_id, run_key),
    INDEX idx_report_run_history (tenant_id, period_from, period_to, status),
    INDEX idx_report_run_template (template_id, template_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理レポートrun';

CREATE TABLE IF NOT EXISTS t_report_section_snapshot (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'section snapshot ID',
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    run_id BIGINT NOT NULL COMMENT 't_report_run.id',
    section_key VARCHAR(100) NOT NULL COMMENT 'section key',
    section_status VARCHAR(30) NOT NULL COMMENT 'SUCCEEDED/FAILED/UNAVAILABLE',
    fact_type VARCHAR(20) NOT NULL COMMENT '実績/予測',
    confirmation VARCHAR(20) NOT NULL COMMENT '確定/速報',
    period_from DATE NULL COMMENT 'section期間開始',
    period_to DATE NULL COMMENT 'section期間終了',
    cutoff_kind VARCHAR(30) NULL COMMENT 'cutoff種別',
    as_of_at DATETIME NULL COMMENT 'section as-of',
    data_as_of_at DATETIME NULL COMMENT 'section data freshness基準',
    freshness_status VARCHAR(20) NULL COMMENT 'FRESH/STALE/UNKNOWN',
    canonical_service VARCHAR(200) NULL COMMENT '正本service',
    canonical_dto VARCHAR(200) NULL COMMENT '正本DTO',
    adapter_version VARCHAR(100) NULL COMMENT 'adapter version',
    source_row_count BIGINT NULL COMMENT '参照行数',
    source_hash VARCHAR(128) NULL COMMENT '正本入力hash',
    value_json LONGTEXT NULL COMMENT 'snapshot value JSON',
    error_code VARCHAR(100) NULL COMMENT '安全化済みsection失敗code',
    error_message VARCHAR(500) NULL COMMENT '安全化済みsection失敗message',
    snapshot_hash VARCHAR(128) NOT NULL COMMENT 'section snapshot hash',
    attempt_count INT NOT NULL DEFAULT 1 COMMENT '生成attempt数',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック',
    UNIQUE KEY uk_report_section_snapshot (run_id, section_key),
    INDEX idx_report_section_snapshot_status (tenant_id, section_status),
    INDEX idx_report_section_snapshot_hash (snapshot_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理レポートsection snapshot';

CREATE TABLE IF NOT EXISTS t_report_delivery (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'report delivery ID',
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    run_id BIGINT NOT NULL COMMENT 't_report_run.id',
    document_id BIGINT NULL COMMENT 't_document.id',
    document_version_no INT NULL COMMENT 't_document_version.version_no',
    recipient_user_id BIGINT NOT NULL COMMENT 'recipient user ID',
    organization_id BIGINT NULL COMMENT 'recipient organization snapshot',
    recipient_scope_json LONGTEXT NOT NULL COMMENT 'recipient scope snapshot',
    recipient_scope_hash VARCHAR(128) NOT NULL COMMENT 'recipient scope hash',
    preview_status VARCHAR(30) NOT NULL COMMENT 'PENDING/ALLOWED/DENIED',
    previewed_at DATETIME NULL COMMENT 'preview日時',
    scope_decision VARCHAR(20) NOT NULL COMMENT 'ALLOW/DENY',
    delivery_channel VARCHAR(50) NOT NULL DEFAULT 'IN_APP_LINK' COMMENT 'アプリ内通知＋link',
    delivery_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/SENT/RETRY/FAILED',
    notification_dedupe_key VARCHAR(190) NOT NULL COMMENT 'notification/outbox冪等キー',
    link_token_hash VARCHAR(128) NULL COMMENT '期限付きlink token hash',
    link_expires_at DATETIME NULL COMMENT 'link期限',
    reauth_required TINYINT NOT NULL DEFAULT 1 COMMENT 'download再認証必須',
    reauthenticated_at DATETIME NULL COMMENT '直近再認証日時',
    attempt_count INT NOT NULL DEFAULT 0 COMMENT 'delivery attempt数',
    downloaded_at DATETIME NULL COMMENT 'download日時',
    last_error_code VARCHAR(100) NULL COMMENT '安全化済み失敗code',
    last_error_message VARCHAR(500) NULL COMMENT '安全化済み失敗message',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除',
    version INT NOT NULL DEFAULT 0 COMMENT '状態CAS用楽観ロック',
    UNIQUE KEY uk_report_delivery_recipient (run_id, recipient_user_id),
    UNIQUE KEY uk_report_delivery_dedupe (tenant_id, notification_dedupe_key),
    INDEX idx_report_delivery_status (tenant_id, delivery_status, link_expires_at),
    INDEX idx_report_delivery_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理レポート配布';

INSERT IGNORE INTO m_document_type
    (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
VALUES ('MANAGEMENT_REPORT', '月次管理レポート', 'INTERNAL', 7, 'TRANSACTION_DATE', 1);

INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('management-report', '定期管理レポート', '/management-reports', '/api/management-reports', 96);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'management-report';

-- V66.1以降のfail-closed action permissionへ、マネージャーのreport APIを追加する。
-- 管理者は既存のrole-admin全action許可を使用する。
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, 'management-report.*', 0
FROM m_permission_group g
WHERE g.tenant_id = 'default'
  AND g.group_key = 'role-manager'
  AND g.enabled = 1;
