-- ===================================================================
-- V129: NF-09 資産・外部アカウント・ライセンス管理（DDL）
-- ===================================================================

CREATE TABLE IF NOT EXISTS m_asset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_tag VARCHAR(64) NOT NULL UNIQUE COMMENT '全社一意資産管理番号 (例: AST-PC-2026-0001)',
    serial_no VARCHAR(128) COMMENT '製造番号/シリアルNo',
    asset_name VARCHAR(128) NOT NULL COMMENT '資産名称 (例: ThinkPad T14 Gen4)',
    category VARCHAR(32) NOT NULL COMMENT '資産区分: PC, MONITOR, SMARTPHONE, SECURITY_KEY, TABLET, OTHER',
    owner_company_id BIGINT COMMENT '所有法人ID (m_organization_unit.legal_entity_id)',
    status VARCHAR(32) NOT NULL DEFAULT 'IN_STOCK' COMMENT 'IN_STOCK, ASSIGNED, UNDER_MAINTENANCE, DISPOSED, LOST',
    location VARCHAR(128) COMMENT '保管場所/拠点',
    purchase_date DATE COMMENT '取得日',
    purchase_price DECIMAL(12, 2) COMMENT '取得価格 (円)',
    warranty_expiry DATE COMMENT 'メーカー保証満了日',
    lease_expiry DATE COMMENT 'リース満了日',
    note VARCHAR(1000) COMMENT '備考',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック用バージョン',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_asset_status (status),
    INDEX idx_asset_owner (owner_company_id),
    INDEX idx_asset_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資産台帳';

CREATE TABLE IF NOT EXISTS t_asset_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL COMMENT '対象資産ID',
    assignee_type VARCHAR(32) NOT NULL COMMENT '貸与先区分: ENGINEER, USER',
    assignee_id BIGINT NOT NULL COMMENT '要員IDまたはユーザーID',
    start_date DATE NOT NULL COMMENT '貸与開始日',
    expected_return_date DATE COMMENT '返却予定日',
    actual_return_date DATE COMMENT '実際の返却日 (NULL=現在貸与中)',
    handover_evidence_doc_id BIGINT COMMENT '受渡し証跡文書ID (t_document.id)',
    return_evidence_doc_id BIGINT COMMENT '返却証跡文書ID (t_document.id)',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, RETURNED, OVERDUE, WAIVED',
    note VARCHAR(500) COMMENT '貸与メモ',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック用バージョン',
    created_by BIGINT COMMENT '登録者ユーザーID',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_assign_asset (asset_id),
    INDEX idx_assign_target (assignee_type, assignee_id),
    INDEX idx_assign_status (status),
    INDEX idx_assign_dates (start_date, actual_return_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資産貸与履歴台帳';

CREATE TABLE IF NOT EXISTS t_asset_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    asset_id BIGINT NOT NULL COMMENT '資産ID',
    event_type VARCHAR(64) NOT NULL COMMENT 'CREATED, ASSIGNED, RETURNED, TRANSFERRED, REPAIRED, REPORTED_LOST, REMOTE_WIPED, DISPOSED, INVENTORIED',
    event_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'イベント発生日時',
    actor_user_id BIGINT COMMENT '操作者ユーザーID',
    assignee_type VARCHAR(32) COMMENT '貸与先区分',
    assignee_id BIGINT COMMENT '貸与先ID',
    from_status VARCHAR(32) COMMENT '変更前ステータス',
    to_status VARCHAR(32) COMMENT '変更後ステータス',
    evidence_doc_id BIGINT COMMENT '関連証跡文書ID',
    event_summary VARCHAR(255) NOT NULL COMMENT 'イベント要約',
    details_json TEXT COMMENT '追加メタデータJSON (PII/Secret非含有)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_event_asset (asset_id, event_time),
    INDEX idx_event_type (event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='資産不変イベント台帳';

CREATE TABLE IF NOT EXISTS t_asset_inventory_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inventory_code VARCHAR(64) NOT NULL UNIQUE COMMENT '棚卸しコード (例: INV-2026-H1)',
    title VARCHAR(128) NOT NULL COMMENT '棚卸し名称',
    target_date DATE NOT NULL COMMENT '基準日',
    status VARCHAR(32) NOT NULL DEFAULT 'IN_PROGRESS' COMMENT 'DRAFT, IN_PROGRESS, COMPLETED, CANCELLED',
    total_assets INT NOT NULL DEFAULT 0,
    matched_count INT NOT NULL DEFAULT 0,
    discrepancy_count INT NOT NULL DEFAULT 0,
    missing_count INT NOT NULL DEFAULT 0,
    conducted_by BIGINT COMMENT '実施責任者ユーザーID',
    completed_at DATETIME COMMENT '完了日時',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='棚卸し実施台帳';

CREATE TABLE IF NOT EXISTS t_asset_inventory_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inventory_run_id BIGINT NOT NULL COMMENT '棚卸し実施ID',
    asset_id BIGINT NOT NULL COMMENT '対象資産ID',
    expected_status VARCHAR(32) NOT NULL COMMENT '台帳上ステータス',
    expected_location VARCHAR(128) COMMENT '台帳上保管場所/貸与先',
    observed_status VARCHAR(32) COMMENT '実地確認ステータス',
    observed_location VARCHAR(128) COMMENT '実地確認場所',
    discrepancy_type VARCHAR(32) NOT NULL DEFAULT 'UNCHECKED' COMMENT 'UNCHECKED, MATCH, DISCREPANCY, MISSING, UNREGISTERED',
    discrepancy_reason VARCHAR(500) COMMENT '差異理由',
    resolution_action VARCHAR(500) COMMENT '是正措置',
    checked_by BIGINT COMMENT '確認者ユーザーID',
    checked_at DATETIME COMMENT '確認日時',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_inv_item (inventory_run_id, asset_id),
    INDEX idx_inv_item_status (discrepancy_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='棚卸し明細台帳';

CREATE TABLE IF NOT EXISTS m_external_account_system (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    system_code VARCHAR(64) NOT NULL UNIQUE COMMENT 'システムコード (例: GOOGLE_WORKSPACE, GITHUB, SLACK, AWS_IAM)',
    system_name VARCHAR(128) NOT NULL COMMENT 'システム名称',
    system_type VARCHAR(32) NOT NULL COMMENT 'IDP, SAAS_MAIL, SAAS_COLLAB, CLOUD_INFRA, MDM',
    auth_type VARCHAR(32) NOT NULL DEFAULT 'SAML_OIDC' COMMENT 'SAML_OIDC, OAUTH2, DIRECT_PROVISION',
    is_active INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部アカウントシステムマスタ';

CREATE TABLE IF NOT EXISTS t_external_account_reference (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    system_id BIGINT NOT NULL COMMENT '外部システムID (m_external_account_system.id)',
    account_identifier VARCHAR(255) NOT NULL COMMENT '外部識別子 (メールアドレス、アカウントID等 - PII扱い)',
    assignee_type VARCHAR(32) NOT NULL COMMENT 'ENGINEER, USER',
    assignee_id BIGINT NOT NULL COMMENT '要員IDまたはユーザーID',
    permission_level VARCHAR(64) COMMENT '権限区分: ADMIN, DEVELOPER, MEMBER, READONLY',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, SUSPENDED, REVOKED, PENDING_CONFIRMATION, UNKNOWN, EXCEPTION_HOLD',
    provisioned_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '発行/割当日時',
    idempotency_key VARCHAR(128) COMMENT '失効要求冪等性キー',
    retry_count INT NOT NULL DEFAULT 0 COMMENT 'ポーリング/リトライ回数',
    next_retry_at DATETIME COMMENT '次回ポーリング予定日時',
    last_error_message VARCHAR(500) COMMENT '直近エラー要約 (秘密値非含有)',
    revoke_requested_at DATETIME COMMENT '失効要求送信日時',
    revoke_confirmed_at DATETIME COMMENT '失効完了確認日時 (NULL=失効未確認)',
    revoke_confirmed_by BIGINT COMMENT '失効確認者ユーザーID',
    external_sync_status VARCHAR(32) DEFAULT 'NONE' COMMENT 'NONE, SYNC_PENDING, SYNC_SUCCESS, SYNC_FAILED, TIMEOUT',
    sync_error_message VARCHAR(500) COMMENT '外部連携エラー要約 (秘密値非含有)',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    UNIQUE KEY uq_ext_idempotency (idempotency_key),
    INDEX idx_ext_acc_target (assignee_type, assignee_id),
    INDEX idx_ext_acc_system (system_id, status),
    INDEX idx_ext_acc_status (status, revoke_confirmed_at),
    INDEX idx_ext_acc_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='外部アカウント参照台帳 (秘密非保存)';

CREATE TABLE IF NOT EXISTS m_license_plan (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_code VARCHAR(64) NOT NULL UNIQUE COMMENT 'プランコード (例: LIC-M365-E5, LIC-GITHUB-ENT)',
    plan_name VARCHAR(128) NOT NULL COMMENT 'プラン名',
    system_id BIGINT COMMENT '外部システムID (m_external_account_system.id)',
    seat_limit INT NOT NULL COMMENT '購入ライセンス席数上限',
    allocated_count INT NOT NULL DEFAULT 0 COMMENT '現在割当数 (CAS保護)',
    cost_per_seat DECIMAL(12, 2) COMMENT '1席あたり月額単価 (円)',
    cost_center_id BIGINT COMMENT '費用負担組織/Cost Center ID',
    expiry_date DATE COMMENT 'ライセンス契約満了日',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, EXPIRED, TERMINATED',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='有償ライセンスプランマスタ';

CREATE TABLE IF NOT EXISTS t_license_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id BIGINT NOT NULL COMMENT 'ライセンスプランID',
    assignee_type VARCHAR(32) NOT NULL COMMENT 'ENGINEER, USER',
    assignee_id BIGINT NOT NULL COMMENT '要員IDまたはユーザーID',
    account_reference_id BIGINT COMMENT '関連外部アカウント参照ID',
    assigned_date DATE NOT NULL COMMENT '割当開始日',
    released_date DATE COMMENT '割当解除日 (NULL=現在割当中)',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, RELEASED',
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag INT NOT NULL DEFAULT 0,
    INDEX idx_lic_assign_plan (plan_id, status),
    INDEX idx_lic_assign_target (assignee_type, assignee_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='ライセンス割当台帳';
