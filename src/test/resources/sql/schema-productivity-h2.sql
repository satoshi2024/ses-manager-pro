-- schema-productivity-h2.sql
-- H2 schema for productivity tasks and saved views

CREATE TABLE IF NOT EXISTS t_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) DEFAULT NULL,
    title VARCHAR(255) NOT NULL,
    description TEXT DEFAULT NULL,
    assignee_user_id BIGINT NOT NULL,
    requester_user_id BIGINT NOT NULL,
    due_date DATE DEFAULT NULL,
    priority VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(32) NOT NULL DEFAULT 'NOT_STARTED',
    target_type VARCHAR(64) DEFAULT NULL,
    target_id BIGINT DEFAULT NULL,
    completed_at TIMESTAMP DEFAULT NULL,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag TINYINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_task_assignee ON t_task(assignee_user_id);
CREATE INDEX IF NOT EXISTS idx_task_requester ON t_task(requester_user_id);
CREATE INDEX IF NOT EXISTS idx_task_status_due ON t_task(status, due_date);

CREATE TABLE IF NOT EXISTS m_saved_view (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(64) DEFAULT NULL,
    owner_user_id BIGINT DEFAULT NULL,
    page_key VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    filter_json TEXT DEFAULT NULL,
    sort_json TEXT DEFAULT NULL,
    columns_json TEXT DEFAULT NULL,
    page_size INT DEFAULT 20,
    shared_flag TINYINT NOT NULL DEFAULT 0,
    version INT NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_flag TINYINT NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_saved_view_page_owner ON m_saved_view(page_key, owner_user_id);

CREATE TABLE IF NOT EXISTS t_task_notification_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id VARCHAR(100) NOT NULL DEFAULT 'default',
    task_id BIGINT NOT NULL,
    notify_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(20) NOT NULL DEFAULT 'RETRY',
    sent_at TIMESTAMP,
    last_error VARCHAR(255),
    CONSTRAINT uk_task_notify_tenant_date UNIQUE (tenant_id, task_id, notify_date)
);

-- V1が先に同名の旧テーブルを作るため、H2でもforward migration相当を適用する。
ALTER TABLE t_task_notification_log ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(100) NOT NULL DEFAULT 'default';
ALTER TABLE t_task_notification_log ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'RETRY';
ALTER TABLE t_task_notification_log ADD COLUMN IF NOT EXISTS sent_at TIMESTAMP;
ALTER TABLE t_task_notification_log ADD COLUMN IF NOT EXISTS last_error VARCHAR(255);
DROP INDEX IF EXISTS uk_task_notify_date;
CREATE UNIQUE INDEX IF NOT EXISTS uk_task_notify_tenant_date
    ON t_task_notification_log(tenant_id, task_id, notify_date);
CREATE INDEX IF NOT EXISTS idx_task_notify_retry
    ON t_task_notification_log(tenant_id, status, notify_date);
