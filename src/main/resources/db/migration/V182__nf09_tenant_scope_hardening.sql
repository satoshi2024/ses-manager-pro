-- NF09: 電子インボイスおよび外部アカウント参照のtenant・法人境界を固定する。
-- 判定不能なlegacy行をdefaultへ推測補完せず、修復キューへ隔離する。

ALTER TABLE t_peppol_participant
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id,
    ADD COLUMN legal_entity_id BIGINT NULL AFTER tenant_id;

ALTER TABLE t_digital_invoice
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id,
    ADD COLUMN legal_entity_id BIGINT NULL AFTER tenant_id;

ALTER TABLE t_digital_invoice_event
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id,
    ADD COLUMN legal_entity_id BIGINT NULL AFTER tenant_id;

ALTER TABLE t_external_account_reference
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id,
    ADD COLUMN legal_entity_id BIGINT NULL AFTER tenant_id;

CREATE TABLE t_nf09_scope_repair_queue (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    entity_type VARCHAR(40) NOT NULL,
    entity_id BIGINT NOT NULL,
    reason VARCHAR(80) NOT NULL,
    candidate_tenant_id VARCHAR(100) NULL,
    candidate_legal_entity_id BIGINT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    version INT NOT NULL DEFAULT 0,
    resolved_at DATETIME NULL,
    resolved_by BIGINT NULL,
    resolution_note VARCHAR(500) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_nf09_scope_repair_entity (entity_type, entity_id),
    INDEX idx_nf09_scope_repair_status (status, entity_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='NF09 tenant・法人帰属修復キュー';

-- 電子インボイスは、信頼できる関連行が示すscopeが一意のときだけ回填する。
CREATE TEMPORARY TABLE __ses_v182_digital_scope_candidates (
    digital_invoice_id BIGINT NOT NULL,
    tenant_id VARCHAR(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL,
    legal_entity_id BIGINT NOT NULL,
    PRIMARY KEY (digital_invoice_id, tenant_id, legal_entity_id)
);

INSERT IGNORE INTO __ses_v182_digital_scope_candidates
    (digital_invoice_id, tenant_id, legal_entity_id)
SELECT d.id, c.tenant_id, c.legal_entity_id
  FROM t_digital_invoice d
  JOIN t_invoice i ON i.id = d.invoice_id AND i.deleted_flag = 0
  JOIN m_customer c ON c.id = i.customer_id AND c.deleted_flag = 0
 WHERE c.tenant_id IS NOT NULL AND c.tenant_id <> ''
   AND c.legal_entity_id IS NOT NULL
   AND i.legal_entity_id = c.legal_entity_id;

INSERT IGNORE INTO __ses_v182_digital_scope_candidates
    (digital_invoice_id, tenant_id, legal_entity_id)
SELECT d.id, o.tenant_id, o.legal_entity_id
  FROM t_digital_invoice d
  JOIN t_sales_order o ON o.id = d.purchase_order_id AND o.deleted_flag = 0
 WHERE o.tenant_id IS NOT NULL AND o.tenant_id <> '' AND o.legal_entity_id IS NOT NULL;

INSERT IGNORE INTO __ses_v182_digital_scope_candidates
    (digital_invoice_id, tenant_id, legal_entity_id)
SELECT d.id, c.tenant_id, c.legal_entity_id
  FROM t_digital_invoice d
  JOIN t_contract c ON c.id = d.contract_id AND c.deleted_flag = 0
 WHERE c.tenant_id IS NOT NULL AND c.tenant_id <> '' AND c.legal_entity_id IS NOT NULL;

UPDATE t_digital_invoice d
JOIN (
    SELECT digital_invoice_id, MIN(tenant_id) AS tenant_id, MIN(legal_entity_id) AS legal_entity_id
      FROM __ses_v182_digital_scope_candidates
     GROUP BY digital_invoice_id
    HAVING COUNT(*) = 1
) resolved ON resolved.digital_invoice_id = d.id
SET d.tenant_id = resolved.tenant_id,
    d.legal_entity_id = resolved.legal_entity_id
WHERE d.tenant_id IS NULL AND d.legal_entity_id IS NULL;

INSERT INTO t_nf09_scope_repair_queue
    (entity_type, entity_id, reason, candidate_tenant_id, candidate_legal_entity_id)
SELECT 'DIGITAL_INVOICE', d.id,
       CASE WHEN COUNT(c.digital_invoice_id) = 0 THEN 'SCOPE_UNRESOLVED' ELSE 'SCOPE_CONFLICT' END,
       CASE WHEN COUNT(c.digital_invoice_id) = 1 THEN MIN(c.tenant_id) ELSE NULL END,
       CASE WHEN COUNT(c.digital_invoice_id) = 1 THEN MIN(c.legal_entity_id) ELSE NULL END
  FROM t_digital_invoice d
  LEFT JOIN __ses_v182_digital_scope_candidates c ON c.digital_invoice_id = d.id
 WHERE d.tenant_id IS NULL OR d.legal_entity_id IS NULL
 GROUP BY d.id;

DROP TEMPORARY TABLE __ses_v182_digital_scope_candidates;

-- Eventのscopeは親行以外から推測しない。
UPDATE t_digital_invoice_event e
JOIN t_digital_invoice d ON d.id = e.digital_invoice_id
SET e.tenant_id = d.tenant_id,
    e.legal_entity_id = d.legal_entity_id
WHERE d.tenant_id IS NOT NULL AND d.legal_entity_id IS NOT NULL;

INSERT INTO t_nf09_scope_repair_queue (entity_type, entity_id, reason)
SELECT 'DIGITAL_INVOICE_EVENT', e.id, 'PARENT_SCOPE_UNRESOLVED'
  FROM t_digital_invoice_event e
 WHERE e.tenant_id IS NULL OR e.legal_entity_id IS NULL;

-- Customer所有の参加者だけを顧客ownershipから回填する。
UPDATE t_peppol_participant p
JOIN m_customer c ON p.owner_type = 'CUSTOMER' AND p.owner_id = c.id AND c.deleted_flag = 0
SET p.tenant_id = c.tenant_id,
    p.legal_entity_id = c.legal_entity_id
WHERE c.tenant_id IS NOT NULL AND c.tenant_id <> '' AND c.legal_entity_id IS NOT NULL;

INSERT INTO t_nf09_scope_repair_queue (entity_type, entity_id, reason)
SELECT 'PEPPOL_PARTICIPANT', p.id, 'SCOPE_UNRESOLVED'
  FROM t_peppol_participant p
 WHERE p.tenant_id IS NULL OR p.legal_entity_id IS NULL;

-- 同じcallback identityを複数ownerがVERIFIEDで共有しているlegacy行は、
-- 一意制約追加前に全件をfail-closedへ隔離する。任意の1件を勝者に選ばない。
CREATE TEMPORARY TABLE __ses_v182_peppol_identity_conflicts (
    provider VARCHAR(50) NOT NULL,
    scheme_id VARCHAR(50) NOT NULL,
    participant_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (provider, scheme_id, participant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO __ses_v182_peppol_identity_conflicts (provider, scheme_id, participant_id)
SELECT provider, scheme_id, participant_id
  FROM t_peppol_participant
 WHERE deleted_flag = 0 AND status = 'VERIFIED'
 GROUP BY provider, scheme_id, participant_id
HAVING COUNT(*) > 1;

INSERT INTO t_nf09_scope_repair_queue (entity_type, entity_id, reason)
SELECT 'PEPPOL_PARTICIPANT', p.id, 'CALLBACK_IDENTITY_CONFLICT'
  FROM t_peppol_participant p
  JOIN __ses_v182_peppol_identity_conflicts conflict
    ON conflict.provider = p.provider
   AND conflict.scheme_id = p.scheme_id
   AND conflict.participant_id = p.participant_id
 WHERE p.deleted_flag = 0 AND p.status = 'VERIFIED'
ON DUPLICATE KEY UPDATE reason = 'CALLBACK_IDENTITY_CONFLICT';

UPDATE t_peppol_participant p
JOIN __ses_v182_peppol_identity_conflicts conflict
  ON conflict.provider = p.provider
 AND conflict.scheme_id = p.scheme_id
 AND conflict.participant_id = p.participant_id
SET p.status = 'CONFLICT_REPAIR',
    p.verified_at = NULL
WHERE p.deleted_flag = 0 AND p.status = 'VERIFIED';

DROP TEMPORARY TABLE __ses_v182_peppol_identity_conflicts;

-- ExternalAccountはEngineer所有だけを明示ownershipから回填する。USER legacyは組織曖昧性があるため隔離する。
UPDATE t_external_account_reference a
JOIN t_engineer e ON a.assignee_type = 'ENGINEER' AND a.assignee_id = e.id AND e.deleted_flag = 0
SET a.tenant_id = e.tenant_id,
    a.legal_entity_id = e.legal_entity_id
WHERE e.tenant_id IS NOT NULL AND e.tenant_id <> '' AND e.legal_entity_id IS NOT NULL;

INSERT INTO t_nf09_scope_repair_queue (entity_type, entity_id, reason)
SELECT 'EXTERNAL_ACCOUNT', a.id, 'SCOPE_UNRESOLVED'
  FROM t_external_account_reference a
 WHERE a.tenant_id IS NULL OR a.legal_entity_id IS NULL;

-- 既存デジタルインボイスの監査主体をclosed pairへ正規化する。
UPDATE t_digital_invoice
SET actor_type = 'LEGACY_UNRESOLVED',
    confirmation_source = 'LEGACY_UNRESOLVED',
    human_user_id = NULL
WHERE actor_type IS NULL OR confirmation_source IS NULL
   OR NOT (
       (actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0)
       OR (actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL)
       OR (actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL)
       OR (actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL)
   );

UPDATE t_digital_invoice_event
SET actor_type = 'LEGACY_UNRESOLVED',
    confirmation_source = 'LEGACY_UNRESOLVED',
    human_user_id = NULL
WHERE actor_type IS NULL OR confirmation_source IS NULL
   OR NOT (
       (actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0)
       OR (actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL)
       OR (actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL)
       OR (actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL)
   );

ALTER TABLE t_digital_invoice
    DROP INDEX uk_digital_invoice_message,
    DROP INDEX uk_digital_invoice_send,
    ADD UNIQUE KEY uk_digital_invoice_scope_message (tenant_id, legal_entity_id, message_id),
    ADD UNIQUE KEY uk_digital_invoice_scope_send (
        tenant_id, legal_entity_id, invoice_id, direction, profile, specification_version, send_active_slot),
    ADD UNIQUE KEY uk_digital_invoice_scope_id (tenant_id, legal_entity_id, id),
    ADD INDEX idx_digital_invoice_scope_status (tenant_id, legal_entity_id, direction, status, id),
    ADD INDEX idx_digital_invoice_scope_invoice (tenant_id, legal_entity_id, invoice_id, direction, profile, id),
    ADD CONSTRAINT ck_digital_invoice_actor_pair CHECK (
        actor_type IS NOT NULL AND confirmation_source IS NOT NULL AND (
            (actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0)
            OR (actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL)
            OR (actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL)
            OR (actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL)
        )
    );

ALTER TABLE t_digital_invoice_event
    ADD INDEX idx_digital_invoice_event_scope (tenant_id, legal_entity_id, digital_invoice_id, event_at, id),
    ADD CONSTRAINT fk_digital_invoice_event_scope
        FOREIGN KEY (tenant_id, legal_entity_id, digital_invoice_id)
        REFERENCES t_digital_invoice (tenant_id, legal_entity_id, id)
        ON UPDATE RESTRICT ON DELETE RESTRICT,
    ADD CONSTRAINT ck_digital_invoice_event_actor_pair CHECK (
        actor_type IS NOT NULL AND confirmation_source IS NOT NULL AND (
            (actor_type = 'HUMAN' AND confirmation_source = 'MANUAL_API' AND human_user_id IS NOT NULL AND human_user_id > 0)
            OR (actor_type = 'SYSTEM' AND confirmation_source = 'SCHEDULER_POLL' AND human_user_id IS NULL)
            OR (actor_type = 'PROVIDER' AND confirmation_source IN ('PROVIDER_SYNC', 'PROVIDER_CALLBACK') AND human_user_id IS NULL)
            OR (actor_type = 'LEGACY_UNRESOLVED' AND confirmation_source = 'LEGACY_UNRESOLVED' AND human_user_id IS NULL)
        )
    );

ALTER TABLE t_peppol_participant
    DROP INDEX uk_peppol_participant_owner,
    ADD COLUMN callback_active_slot TINYINT
        AS (CASE WHEN deleted_flag = 0 AND status = 'VERIFIED' THEN 1 ELSE NULL END),
    ADD UNIQUE KEY uk_peppol_scope_owner (tenant_id, legal_entity_id, owner_type, owner_id),
    ADD UNIQUE KEY uk_peppol_callback_active (
        provider, scheme_id, participant_id, callback_active_slot),
    ADD INDEX idx_peppol_scope_owner (tenant_id, legal_entity_id, owner_type, owner_id, deleted_flag),
    ADD INDEX idx_peppol_callback_identity (provider, scheme_id, participant_id, status, deleted_flag);

ALTER TABLE t_external_account_reference
    DROP INDEX uq_ext_idempotency,
    ADD UNIQUE KEY uq_ext_scope_idempotency (tenant_id, legal_entity_id, idempotency_key),
    ADD INDEX idx_external_account_scope_target (tenant_id, legal_entity_id, assignee_type, assignee_id, status),
    ADD INDEX idx_external_account_scope_retry (tenant_id, legal_entity_id, status, next_retry_at);
