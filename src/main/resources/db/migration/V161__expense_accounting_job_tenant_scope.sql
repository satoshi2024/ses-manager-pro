-- V161: 経費会計outboxのtenant境界。経費本体にtenant列がない旧schemaでは、
-- 要員account linkとsys_userのtenantを信頼してjobへ固定する。
ALTER TABLE t_expense_accounting_job
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;
UPDATE t_expense_accounting_job j
JOIN t_expense_request e ON e.id = j.expense_request_id
JOIN t_engineer_account_link l ON l.engineer_id = e.engineer_id
JOIN sys_user u ON u.id = l.sys_user_id
SET j.tenant_id = u.tenant_id
WHERE j.tenant_id IS NULL AND u.tenant_id IS NOT NULL AND u.tenant_id <> '';

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_check_v161_expense_job_tenant $$
CREATE PROCEDURE __ses_check_v161_expense_job_tenant()
BEGIN
    DECLARE missing_count BIGINT DEFAULT 0;
    SELECT COUNT(*) INTO missing_count FROM t_expense_accounting_job
     WHERE tenant_id IS NULL OR tenant_id = '';
    IF missing_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V161: tenantを特定できない経費会計jobがあるため移行を停止しました';
    END IF;
END $$
DELIMITER ;
CALL __ses_check_v161_expense_job_tenant();
DROP PROCEDURE IF EXISTS __ses_check_v161_expense_job_tenant;

ALTER TABLE t_expense_accounting_job
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    -- 旧のexpense_request_id UNIQUEは既存FKの参照indexでもあるため保持する。
    -- tenant境界の複合冪等キーを追加し、既存データとの互換性を維持する。
    ADD UNIQUE KEY uk_expense_job_tenant_request (tenant_id, expense_request_id),
    ADD INDEX idx_expense_job_tenant_status (tenant_id, status, next_attempt_at);
