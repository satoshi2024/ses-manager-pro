-- V165: 承認workflowのtenant境界。適用済みV162/V163/V164は変更しない。
-- applicant/from_userを唯一の既存データ根拠として回填し、特定不能な履歴は移行を停止する。

ALTER TABLE t_approval_request
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;
ALTER TABLE t_approval_action
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;
ALTER TABLE t_approval_participant
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;
ALTER TABLE t_approval_delegation
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;
ALTER TABLE t_approval_delegation_type
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER delegation_id;

-- 既存route/responsibilityの旧数値tenant(1)を、現在の認証tenant表現へ正規化する。
ALTER TABLE m_approval_route
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL;
ALTER TABLE t_approval_responsibility
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL;
UPDATE m_approval_route SET tenant_id = 'default' WHERE tenant_id = '1';
UPDATE t_approval_responsibility SET tenant_id = 'default' WHERE tenant_id = '1';

UPDATE t_approval_request r
JOIN sys_user u ON u.id = r.applicant_id
SET r.tenant_id = u.tenant_id
WHERE r.tenant_id IS NULL;

UPDATE t_approval_action a
JOIN t_approval_request r ON r.id = a.request_id
SET a.tenant_id = r.tenant_id
WHERE a.tenant_id IS NULL;

UPDATE t_approval_participant p
JOIN t_approval_request r ON r.id = p.request_id
SET p.tenant_id = r.tenant_id
WHERE p.tenant_id IS NULL;

UPDATE t_approval_delegation d
JOIN sys_user u ON u.id = d.from_user_id
SET d.tenant_id = u.tenant_id
WHERE d.tenant_id IS NULL;

UPDATE t_approval_delegation_type dt
JOIN t_approval_delegation d ON d.id = dt.delegation_id
SET dt.tenant_id = d.tenant_id
WHERE dt.tenant_id IS NULL;

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_check_v165_approval_tenant $$
CREATE PROCEDURE __ses_check_v165_approval_tenant()
BEGIN
    DECLARE missing_count BIGINT DEFAULT 0;
    SELECT COUNT(*) INTO missing_count FROM t_approval_request WHERE tenant_id IS NULL OR tenant_id = '';
    SELECT COUNT(*) + missing_count INTO missing_count FROM t_approval_action WHERE tenant_id IS NULL OR tenant_id = '';
    SELECT COUNT(*) + missing_count INTO missing_count FROM t_approval_participant WHERE tenant_id IS NULL OR tenant_id = '';
    SELECT COUNT(*) + missing_count INTO missing_count FROM t_approval_delegation WHERE tenant_id IS NULL OR tenant_id = '';
    SELECT COUNT(*) + missing_count INTO missing_count FROM t_approval_delegation_type WHERE tenant_id IS NULL OR tenant_id = '';
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_request r JOIN sys_user u ON u.id = r.applicant_id
     WHERE r.tenant_id <> u.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_request r JOIN sys_user u ON u.id = r.created_by
     WHERE r.created_by IS NOT NULL AND r.tenant_id <> u.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_action a JOIN t_approval_request r ON r.id = a.request_id
     WHERE a.tenant_id <> r.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_action a JOIN sys_user u ON u.id = a.approver_user_id
     WHERE a.tenant_id <> u.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_action a JOIN sys_user u ON u.id = a.approver_slot_user_id
     WHERE a.tenant_id <> u.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_action a JOIN sys_user u ON u.id = a.delegated_from
     WHERE a.delegated_from IS NOT NULL AND a.tenant_id <> u.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_participant p JOIN t_approval_request r ON r.id = p.request_id
     WHERE p.tenant_id <> r.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_participant p JOIN sys_user u ON u.id = p.user_id
     WHERE p.tenant_id <> u.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_delegation d JOIN sys_user u ON u.id = d.from_user_id
     WHERE d.tenant_id <> u.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_delegation d JOIN sys_user u ON u.id = d.to_user_id
     WHERE d.tenant_id <> u.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_delegation d JOIN sys_user u ON u.id = d.approved_by
     WHERE d.approved_by IS NOT NULL AND d.tenant_id <> u.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_delegation d JOIN sys_user u ON u.id = d.created_by
     WHERE d.created_by IS NOT NULL AND d.tenant_id <> u.tenant_id;
    SELECT COUNT(*) + missing_count INTO missing_count
      FROM t_approval_delegation_type dt JOIN t_approval_delegation d ON d.id = dt.delegation_id
     WHERE dt.tenant_id <> d.tenant_id;
    IF missing_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V165: tenantを特定できない承認履歴があるため移行を停止しました';
    END IF;
END $$
DELIMITER ;
CALL __ses_check_v165_approval_tenant();
DROP PROCEDURE IF EXISTS __ses_check_v165_approval_tenant;

-- 旧child FKはrequest_id/delegation_id先頭の旧indexを参照しているため、
-- index差替えより先に解除する。後段でtenant付き複合FKを再作成する。
ALTER TABLE t_approval_action
    DROP FOREIGN KEY fk_approval_action_request,
    DROP FOREIGN KEY fk_approval_action_approver,
    DROP FOREIGN KEY fk_approval_action_delegated_from;
ALTER TABLE t_approval_participant
    DROP FOREIGN KEY fk_participant_request;
ALTER TABLE t_approval_delegation
    DROP FOREIGN KEY fk_approval_delegation_from,
    DROP FOREIGN KEY fk_approval_delegation_to,
    DROP FOREIGN KEY fk_approval_delegation_approved_by;
ALTER TABLE t_approval_delegation_type
    DROP FOREIGN KEY fk_delegation_type;

ALTER TABLE t_approval_request
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    DROP INDEX uk_approval_request_no,
    DROP INDEX uk_approval_request_idempotency,
    ADD UNIQUE KEY uk_approval_request_tenant_id (tenant_id, id),
    ADD UNIQUE KEY uk_approval_request_tenant_no (tenant_id, request_no),
    ADD UNIQUE KEY uk_approval_request_tenant_idempotency (tenant_id, idempotency_key),
    ADD INDEX idx_approval_request_tenant_applicant (tenant_id, applicant_id, status),
    ADD INDEX idx_approval_request_tenant_target (tenant_id, target_type, target_id),
    ADD INDEX idx_approval_request_tenant_status (tenant_id, status, current_step);

ALTER TABLE t_approval_action
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    DROP INDEX uk_approval_action_slot,
    -- 既存のindex名を維持しつつ、tenantを含む複合UNIQUEへ置換する。
    ADD UNIQUE KEY uk_approval_action_slot
        (tenant_id, request_id, round_no, step_no, approver_slot_user_id),
    ADD INDEX idx_approval_action_tenant_request (tenant_id, request_id, round_no, step_no);

ALTER TABLE t_approval_participant
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    DROP INDEX uk_participant,
    -- 既存のindex名を維持しつつ、tenantを含む複合UNIQUEへ置換する。
    ADD UNIQUE KEY uk_participant (tenant_id, request_id, round_no, user_id, participant_role),
    ADD INDEX idx_participant_tenant_user (tenant_id, user_id, participant_role);

ALTER TABLE t_approval_delegation
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    ADD UNIQUE KEY uk_approval_delegation_tenant_id (tenant_id, id),
    ADD INDEX idx_approval_delegation_tenant_lookup (tenant_id, from_user_id, valid_from, valid_to);

ALTER TABLE t_approval_delegation_type
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    DROP PRIMARY KEY,
    ADD PRIMARY KEY (tenant_id, delegation_id, request_type);

-- tenant付き複合FKで、アプリケーションの条件漏れがあっても異なるtenantの関連付けを拒否する。
ALTER TABLE sys_user
    ADD UNIQUE KEY uk_sys_user_tenant_id (tenant_id, id);
ALTER TABLE t_approval_request
    DROP FOREIGN KEY fk_approval_request_applicant,
    ADD CONSTRAINT fk_approval_request_applicant_tenant
        FOREIGN KEY (tenant_id, applicant_id) REFERENCES sys_user (tenant_id, id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    ADD CONSTRAINT fk_approval_request_created_by_tenant
        FOREIGN KEY (tenant_id, created_by) REFERENCES sys_user (tenant_id, id)
        ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE t_approval_action
    ADD CONSTRAINT fk_approval_action_request_tenant
        FOREIGN KEY (tenant_id, request_id) REFERENCES t_approval_request (tenant_id, id)
        ON UPDATE CASCADE ON DELETE CASCADE,
    ADD CONSTRAINT fk_approval_action_approver_tenant
        FOREIGN KEY (tenant_id, approver_user_id) REFERENCES sys_user (tenant_id, id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    ADD CONSTRAINT fk_approval_action_slot_tenant
        FOREIGN KEY (tenant_id, approver_slot_user_id) REFERENCES sys_user (tenant_id, id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    ADD CONSTRAINT fk_approval_action_delegated_from_tenant
        FOREIGN KEY (tenant_id, delegated_from) REFERENCES sys_user (tenant_id, id)
        ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE t_approval_participant
    ADD CONSTRAINT fk_participant_request_tenant
        FOREIGN KEY (tenant_id, request_id) REFERENCES t_approval_request (tenant_id, id)
        ON DELETE CASCADE,
    ADD CONSTRAINT fk_participant_user_tenant
        FOREIGN KEY (tenant_id, user_id) REFERENCES sys_user (tenant_id, id)
        ON UPDATE CASCADE ON DELETE RESTRICT;
-- MySQL 8では、CHECKが参照する列に対する外部キーの参照アクションを追加する際、
-- 既存CHECKを一時的に解除する必要がある。複合FK作成後に同じ業務制約を戻す。
ALTER TABLE t_approval_delegation
    DROP CHECK chk_approval_delegation_not_self;
ALTER TABLE t_approval_delegation
    ADD CONSTRAINT fk_approval_delegation_from_tenant
        FOREIGN KEY (tenant_id, from_user_id) REFERENCES sys_user (tenant_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT fk_approval_delegation_to_tenant
        FOREIGN KEY (tenant_id, to_user_id) REFERENCES sys_user (tenant_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT fk_approval_delegation_approved_by_tenant
        FOREIGN KEY (tenant_id, approved_by) REFERENCES sys_user (tenant_id, id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    ADD CONSTRAINT fk_approval_delegation_created_by_tenant
        FOREIGN KEY (tenant_id, created_by) REFERENCES sys_user (tenant_id, id)
        ON UPDATE CASCADE ON DELETE RESTRICT;
ALTER TABLE t_approval_delegation
    ADD CONSTRAINT chk_approval_delegation_not_self CHECK (from_user_id <> to_user_id);
ALTER TABLE t_approval_delegation_type
    ADD CONSTRAINT fk_delegation_type_tenant
        FOREIGN KEY (tenant_id, delegation_id) REFERENCES t_approval_delegation (tenant_id, id)
        ON UPDATE CASCADE ON DELETE CASCADE;
