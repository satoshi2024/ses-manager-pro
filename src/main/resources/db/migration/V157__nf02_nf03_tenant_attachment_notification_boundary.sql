-- V157: NF02/NF03 review-v2。既存migrationの履歴は変更しない。

-- サービスデスク添付の業務リンク自身にもtenantを保存し、DB一意制約を最終防線にする。
ALTER TABLE t_service_attachment_link
    ADD COLUMN tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' AFTER id;
ALTER TABLE t_service_attachment_link
    ADD COLUMN business_key VARCHAR(512) NULL AFTER visibility;
UPDATE t_service_attachment_link
SET business_key = CONCAT('LEGACY:', id)
WHERE business_key IS NULL;
ALTER TABLE t_service_attachment_link
    MODIFY COLUMN business_key VARCHAR(512) NOT NULL;
CREATE UNIQUE INDEX uk_service_attachment_tenant_business_key
    ON t_service_attachment_link (tenant_id, business_key);
CREATE INDEX idx_service_attachment_tenant_request
    ON t_service_attachment_link (tenant_id, service_request_id, visibility);

-- Storage外・metadata確定後の業務リンク失敗を再試行する台帳。ファイル内容は保存しない。
CREATE TABLE t_service_attachment_compensation (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id           VARCHAR(100) NOT NULL,
    service_request_id  BIGINT NOT NULL,
    comment_id          BIGINT NULL,
    document_id         BIGINT NOT NULL,
    visibility          VARCHAR(20) NOT NULL,
    file_name           VARCHAR(255) NOT NULL,
    file_size           BIGINT NOT NULL,
    business_key        VARCHAR(512) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'RETRY',
    attempt_count       INT NOT NULL DEFAULT 1,
    last_error          VARCHAR(255) NULL,
    next_retry_at       DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at         DATETIME NULL,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_service_attachment_compensation_business (tenant_id, business_key),
    KEY idx_service_attachment_compensation_due (status, next_retry_at),
    CONSTRAINT fk_service_attachment_comp_request FOREIGN KEY (service_request_id)
        REFERENCES t_service_request(id) ON DELETE CASCADE,
    CONSTRAINT fk_service_attachment_comp_document FOREIGN KEY (document_id)
        REFERENCES t_document(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='サービスデスク添付業務リンク補償台帳';

-- 資格期限通知はdispatch時のtenantを監査・検索可能にする。
ALTER TABLE t_notification
    ADD COLUMN tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' AFTER id;
DROP INDEX dedupe_key ON t_notification;
CREATE UNIQUE INDEX uk_notification_tenant_dedupe ON t_notification (tenant_id, dedupe_key);
CREATE INDEX idx_notification_tenant_created ON t_notification (tenant_id, created_at);

-- 通知母集団の元となるaccount/lifecycle/所属/userもdispatch tenantへ固定する。
ALTER TABLE sys_user
    ADD COLUMN tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' AFTER id;
CREATE INDEX idx_sys_user_tenant_role ON sys_user (tenant_id, role, status, deleted_flag);
ALTER TABLE t_engineer_account_link
    ADD COLUMN tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' AFTER id;
CREATE INDEX idx_engineer_account_link_tenant_engineer
    ON t_engineer_account_link (tenant_id, engineer_id);
ALTER TABLE t_user_organization
    ADD COLUMN tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' AFTER id;
CREATE INDEX idx_user_organization_tenant_user
    ON t_user_organization (tenant_id, user_id, valid_from, valid_to);
ALTER TABLE t_lifecycle_case
    ADD COLUMN tenant_id VARCHAR(100) NOT NULL DEFAULT 'default' AFTER id;
CREATE INDEX idx_lifecycle_case_tenant_engineer
    ON t_lifecycle_case (tenant_id, engineer_id, lifecycle_type, status);
