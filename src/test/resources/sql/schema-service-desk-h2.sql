-- H2 schema for customer-success-service-desk (NF-02)

CREATE TABLE IF NOT EXISTS m_service_sla_policy (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                  VARCHAR(100) NOT NULL,
    priority              VARCHAR(20) NOT NULL,
    response_time_hours   INT NOT NULL,
    resolve_time_hours    INT NOT NULL,
    business_hours_start  TIME NOT NULL DEFAULT '09:00:00',
    business_hours_end    TIME NOT NULL DEFAULT '18:00:00',
    include_holidays      TINYINT(1) NOT NULL DEFAULT 0,
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version               INT NOT NULL DEFAULT 0,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (priority, status)
);

CREATE TABLE IF NOT EXISTS t_service_request (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_no        VARCHAR(64) NOT NULL,
    customer_id       BIGINT NOT NULL,
    contact_id        BIGINT NULL,
    contract_id       BIGINT NULL,
    project_id        BIGINT NULL,
    engineer_id       BIGINT NULL,
    category          VARCHAR(50) NOT NULL,
    priority          VARCHAR(20) NOT NULL,
    channel           VARCHAR(30) NOT NULL,
    subject           VARCHAR(255) NOT NULL,
    description       CLOB NOT NULL,
    owner_user_id     BIGINT NULL,
    status            VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
    first_response_at DATETIME NULL,
    resolved_at       DATETIME NULL,
    closed_at         DATETIME NULL,
    reopened_at       DATETIME NULL,
    reopen_count      INT NOT NULL DEFAULT 0,
    portal_user_id    BIGINT NULL,
    created_by        BIGINT NULL,
    updated_by        BIGINT NULL,
    version           INT NOT NULL DEFAULT 0,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (request_no)
);

CREATE TABLE IF NOT EXISTS t_service_sla_clock (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_request_id  BIGINT NOT NULL,
    round_no            INT NOT NULL DEFAULT 1,
    policy_id           BIGINT NOT NULL,
    response_deadline   DATETIME NOT NULL,
    resolve_deadline    DATETIME NOT NULL,
    first_responded_at  DATETIME NULL,
    response_breached   TINYINT(1) NOT NULL DEFAULT 0,
    resolved_at         DATETIME NULL,
    resolve_breached    TINYINT(1) NOT NULL DEFAULT 0,
    total_pause_minutes INT NOT NULL DEFAULT 0,
    last_paused_at      DATETIME NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'RUNNING',
    version             INT NOT NULL DEFAULT 0,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (service_request_id, round_no)
);

CREATE TABLE IF NOT EXISTS t_service_comment (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_request_id  BIGINT NOT NULL,
    author_type         VARCHAR(20) NOT NULL,
    author_id           BIGINT NOT NULL,
    author_name         VARCHAR(100) NOT NULL,
    visibility          VARCHAR(20) NOT NULL DEFAULT 'PORTAL_VISIBLE',
    comment_text        CLOB NOT NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_service_attachment_link (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_request_id  BIGINT NOT NULL,
    comment_id          BIGINT NULL,
    document_id         BIGINT NOT NULL,
    visibility          VARCHAR(20) NOT NULL DEFAULT 'PORTAL_VISIBLE',
    file_name           VARCHAR(255) NOT NULL,
    file_size           BIGINT NOT NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_service_state_event (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_request_id  BIGINT NOT NULL,
    round_no            INT NOT NULL DEFAULT 1,
    from_status         VARCHAR(30) NULL,
    to_status           VARCHAR(30) NOT NULL,
    reason              VARCHAR(255) NULL,
    actor_type          VARCHAR(20) NOT NULL,
    actor_id            BIGINT NOT NULL,
    actor_name          VARCHAR(100) NOT NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_customer_csat (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    service_request_id  BIGINT NOT NULL,
    customer_id         BIGINT NOT NULL,
    portal_user_id      BIGINT NOT NULL,
    score               INT NOT NULL,
    feedback_comment    CLOB NULL,
    answered_at         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (service_request_id)
);

CREATE TABLE IF NOT EXISTS t_customer_qbr (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id         BIGINT NOT NULL,
    title               VARCHAR(255) NOT NULL,
    meeting_date        DATE NOT NULL,
    attendees           CLOB NULL,
    agenda              CLOB NULL,
    discussion          CLOB NULL,
    decisions           CLOB NULL,
    next_meeting_date   DATE NULL,
    created_by          BIGINT NULL,
    updated_by          BIGINT NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_customer_qbr_action (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    qbr_id              BIGINT NOT NULL,
    title               VARCHAR(255) NOT NULL,
    description         CLOB NULL,
    owner_user_id       BIGINT NULL,
    due_date            DATE NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'OPEN',
    completed_at        DATETIME NULL,
    version             INT NOT NULL DEFAULT 0,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_customer_health_snapshot (
    id                          BIGINT AUTO_INCREMENT PRIMARY KEY,
    customer_id                 BIGINT NOT NULL,
    snapshot_date               DATE NOT NULL,
    health_status               VARCHAR(20) NOT NULL,
    total_score                 INT NOT NULL,
    open_critical_issues_count  INT NOT NULL DEFAULT 0,
    sla_breach_count_30d        INT NOT NULL DEFAULT 0,
    avg_csat_score              DECIMAL(3,2) NULL,
    ar_overdue_flag             TINYINT(1) NOT NULL DEFAULT 0,
    missing_inputs_json         CLOB NULL,
    factors_explanation         CLOB NULL,
    created_at                  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (customer_id, snapshot_date)
);

-- 初期マスタデータ
MERGE INTO m_service_sla_policy (priority, name, response_time_hours, resolve_time_hours, business_hours_start, business_hours_end, include_holidays, status, version)
KEY(priority, status)
VALUES
('P0', '緊急 (P0)', 1, 4, '09:00:00', '18:00:00', 0, 'ACTIVE', 0),
('P1', '高 (P1)',   2, 8, '09:00:00', '18:00:00', 0, 'ACTIVE', 0),
('P2', '中 (P2)',   4, 24, '09:00:00', '18:00:00', 0, 'ACTIVE', 0),
('P3', '低 (P3)',   8, 48, '09:00:00', '18:00:00', 0, 'ACTIVE', 0);

MERGE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
KEY(menu_key)
VALUES ('service-desk', 'サービスデスク', '/service-desk', '/api/service-desk', 11);

MERGE INTO t_role_menu (role, menu_id)
KEY(role, menu_id)
SELECT r.role, m.id
FROM m_menu m
CROSS JOIN (SELECT '管理者' AS role UNION ALL SELECT '営業' UNION ALL SELECT 'マネージャー') r
WHERE m.menu_key = 'service-desk';

MERGE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
KEY(tenant_id, group_id, action_key)
SELECT 'default', g.id, a.action_key, 0
FROM m_permission_group g
CROSS JOIN (
    SELECT 'service-desk.*' AS action_key UNION ALL
    SELECT 'customer-health.*' AS action_key UNION ALL
    SELECT 'customer-qbr.*' AS action_key
) a
WHERE g.tenant_id = 'default'
  AND g.enabled = 1
  AND g.group_key IN ('role-sales', 'role-manager', 'role-admin');
