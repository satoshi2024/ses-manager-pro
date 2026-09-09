-- V162: 通知outboxをtenant単位で分離する。適用済みmigrationの履歴は変更しない。
ALTER TABLE t_notification_outbox
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;

UPDATE t_notification_outbox o
JOIN t_notification n ON n.id = o.notification_id
SET o.tenant_id = n.tenant_id
WHERE o.tenant_id IS NULL AND n.tenant_id IS NOT NULL AND n.tenant_id <> '';

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_check_v162_notification_outbox_tenant $$
CREATE PROCEDURE __ses_check_v162_notification_outbox_tenant()
BEGIN
    DECLARE missing_count BIGINT DEFAULT 0;
    SELECT COUNT(*) INTO missing_count FROM t_notification_outbox
     WHERE tenant_id IS NULL OR tenant_id = '';
    IF missing_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V162: tenantを特定できない通知outbox履歴があるため移行を停止しました';
    END IF;
END $$
DELIMITER ;
CALL __ses_check_v162_notification_outbox_tenant();
DROP PROCEDURE IF EXISTS __ses_check_v162_notification_outbox_tenant;

ALTER TABLE t_notification_outbox
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    DROP INDEX uk_notification_outbox_dedupe,
    ADD UNIQUE KEY uk_notification_outbox_tenant_dedupe (tenant_id, dedupe_key),
    ADD INDEX idx_notification_outbox_tenant_due (tenant_id, status, next_attempt_at);
