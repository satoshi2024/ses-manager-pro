-- V160: タスク期限通知をtenant単位の再試行可能state machineへ移行する。
-- 既存ログのtenantはtask、次にassigneeのsys_userからのみ回復し、特定不能なら停止する。
ALTER TABLE t_task_notification_log
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id,
    ADD COLUMN status VARCHAR(20) NULL AFTER created_at,
    ADD COLUMN sent_at DATETIME NULL AFTER status,
    ADD COLUMN last_error VARCHAR(255) NULL AFTER sent_at,
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0 AFTER last_error,
    ADD COLUMN next_retry_at DATETIME NULL AFTER attempt_count;

UPDATE t_task_notification_log l
JOIN t_task t ON t.id = l.task_id
SET l.tenant_id = t.tenant_id
WHERE l.tenant_id IS NULL AND t.tenant_id IS NOT NULL AND t.tenant_id <> '';
UPDATE t_task_notification_log l
JOIN t_task t ON t.id = l.task_id
JOIN sys_user u ON u.id = t.assignee_user_id
SET l.tenant_id = u.tenant_id
WHERE l.tenant_id IS NULL AND u.tenant_id IS NOT NULL AND u.tenant_id <> '';
-- 旧ログには配送成功の証跡がない。status NULL/CLAIMEDをSENTへ補完すると、
-- 未送信通知を永続的に取りこぼすため、未知状態はすべて再試行へ戻す。
-- SENTを維持できるのは、既に記録されたsent_atという配送確定証跡がある行だけ。
UPDATE t_task_notification_log
SET status = 'RETRY', next_retry_at = COALESCE(next_retry_at, CURRENT_TIMESTAMP)
WHERE status IS NULL
   OR status <> 'SENT'
   OR (status = 'SENT' AND sent_at IS NULL);

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_check_v160_task_notification_tenant $$
CREATE PROCEDURE __ses_check_v160_task_notification_tenant()
BEGIN
    DECLARE missing_count BIGINT DEFAULT 0;
    SELECT COUNT(*) INTO missing_count
      FROM t_task_notification_log
     WHERE tenant_id IS NULL OR tenant_id = '';
    IF missing_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V160: tenantを特定できないタスク期限通知履歴があるため移行を停止しました';
    END IF;
END $$
DELIMITER ;
CALL __ses_check_v160_task_notification_tenant();
DROP PROCEDURE IF EXISTS __ses_check_v160_task_notification_tenant;

ALTER TABLE t_task_notification_log
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'RETRY',
    DROP INDEX uk_task_notify_date,
    ADD UNIQUE KEY uk_task_notify_tenant_date (tenant_id, task_id, notify_date),
    ADD INDEX idx_task_notify_retry (tenant_id, status, next_retry_at, notify_date);
