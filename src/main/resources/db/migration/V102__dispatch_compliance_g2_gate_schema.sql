-- ============================================================
-- V102: dispatch compliance G2 gate schema (R19-P1-01)
-- V1 fresh shapeと同じG2 shapeをlegacy DBへforward適用する。
-- V84/V85/V101は変更しない。専門家typeの業務値seedも作成しない。
-- ============================================================

CREATE TABLE IF NOT EXISTS m_compliance_mapping_version (
  id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id          VARCHAR(100) NOT NULL DEFAULT 'default',
  mapping_code       VARCHAR(100) NOT NULL,
  mapping_version    VARCHAR(50) NOT NULL,
  mapping_hash       CHAR(64) NOT NULL,
  review_policy_hash CHAR(64) NOT NULL,
  effective_from     DATE NOT NULL,
  effective_to       DATE,
  status             VARCHAR(30) NOT NULL,
  active_slot        TINYINT,
  future_slot        TINYINT,
  activated_at       DATETIME(6),
  activated_by       BIGINT,
  version            INT NOT NULL DEFAULT 0,
  created_by         BIGINT,
  created_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by         BIGINT,
  updated_at         DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag       TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_mapping_version UNIQUE (tenant_id, mapping_version),
  CONSTRAINT uk_g2_mapping_active_slot UNIQUE (tenant_id, mapping_code, active_slot),
  CONSTRAINT uk_g2_mapping_future_slot UNIQUE (tenant_id, mapping_code, future_slot),
  CONSTRAINT uk_g2_mapping_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_mapping_status CHECK (status IN ('DRAFT', 'PROVISIONAL_REVIEWED', 'ACTIVE', 'SUPERSEDED')),
  CONSTRAINT chk_g2_mapping_active_slot CHECK ((status = 'ACTIVE' AND active_slot = 1) OR (status <> 'ACTIVE' AND active_slot IS NULL)),
  CONSTRAINT chk_g2_mapping_future_slot CHECK (future_slot IS NULL OR future_slot = 1),
  CONSTRAINT fk_g2_mapping_activated_by FOREIGN KEY (activated_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 compliance mapping version';

-- 途中失敗後の再実行ではCREATE TABLE IF NOT EXISTSが既存tableを補正しないため、
-- まずcanonical column shapeをmetadataで検証し、不完全なtableをduplicate index errorに進ませず明示fail-closedする。
DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_g2_assert_shape$$
CREATE PROCEDURE __ses_g2_assert_shape(IN p_table VARCHAR(64), IN p_columns TEXT)
BEGIN
  DECLARE v_expected INT;
  DECLARE v_actual INT;
  SET v_expected = 1 + LENGTH(p_columns) - LENGTH(REPLACE(p_columns, ',', ''));
  SELECT COUNT(DISTINCT COLUMN_NAME) INTO v_actual
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table
      AND FIND_IN_SET(COLUMN_NAME, p_columns) > 0;
  IF v_actual <> v_expected THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2_V102_SHAPE_MISMATCH forward repair required';
  END IF;
END$$
DELIMITER ;
CALL __ses_g2_assert_shape('m_compliance_mapping_version',
  'id,tenant_id,mapping_code,mapping_version,mapping_hash,review_policy_hash,effective_from,effective_to,status,active_slot,future_slot,activated_at,activated_by,version,created_by,created_at,updated_by,updated_at,deleted_flag');

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_g2_create_index$$
CREATE PROCEDURE __ses_g2_create_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_sql TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index) THEN
    SET @g2_index_sql = p_sql;
    PREPARE g2_index_stmt FROM @g2_index_sql;
    EXECUTE g2_index_stmt;
    DEALLOCATE PREPARE g2_index_stmt;
  END IF;
END$$
DELIMITER ;
CALL __ses_g2_create_index('m_compliance_mapping_version', 'idx_g2_mapping_effective',
  'CREATE INDEX idx_g2_mapping_effective ON m_compliance_mapping_version (tenant_id, mapping_code, status, effective_from, effective_to)');
-- composite FKの参照側を先にtenant+id uniqueへ補正する。既存global IDだけのuniqueを置換せず、追加uniqueで後方互換を保つ。
CALL __ses_g2_create_index('m_workplace', 'uk_workplace_tenant_id',
  'ALTER TABLE m_workplace ADD UNIQUE KEY uk_workplace_tenant_id (tenant_id, id)');
CALL __ses_g2_create_index('t_document', 'uk_document_tenant_id',
  'ALTER TABLE t_document ADD UNIQUE KEY uk_document_tenant_id (tenant_id, id)');
CALL __ses_g2_create_index('t_document_version', 'uk_document_version_tenant_id',
  'ALTER TABLE t_document_version ADD UNIQUE KEY uk_document_version_tenant_id (tenant_id, id)');
CALL __ses_g2_create_index('t_contract_compliance_snapshot', 'uk_compliance_snapshot_tenant_id',
  'ALTER TABLE t_contract_compliance_snapshot ADD UNIQUE KEY uk_compliance_snapshot_tenant_id (tenant_id, id)');
CALL __ses_g2_create_index('t_contract_compliance_worker_snapshot', 'uk_worker_snapshot_tenant_id',
  'ALTER TABLE t_contract_compliance_worker_snapshot ADD UNIQUE KEY uk_worker_snapshot_tenant_id (tenant_id, id)');

CREATE TABLE IF NOT EXISTS m_compliance_mapping_source (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id      VARCHAR(100) NOT NULL DEFAULT 'default',
  mapping_id     BIGINT NOT NULL,
  source_code    VARCHAR(100) NOT NULL,
  source_url     VARCHAR(1000) NOT NULL,
  source_version VARCHAR(100) NOT NULL,
  confirmed_on   DATE NOT NULL,
  effective_from DATE NOT NULL,
  effective_to   DATE,
  created_by     BIGINT,
  created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by     BIGINT,
  updated_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag   TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_mapping_source UNIQUE (tenant_id, mapping_id, source_code),
  CONSTRAINT uk_g2_mapping_source_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT fk_g2_source_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_source_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_g2_source_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 mapping source';
CALL __ses_g2_create_index('m_compliance_mapping_source', 'idx_g2_mapping_source_lookup',
  'CREATE INDEX idx_g2_mapping_source_lookup ON m_compliance_mapping_source (tenant_id, source_code, confirmed_on)');

CREATE TABLE IF NOT EXISTS m_compliance_external_reviewer_type (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id        VARCHAR(100) NOT NULL DEFAULT 'default',
  type_code        VARCHAR(100) NOT NULL,
  display_name     VARCHAR(200) NOT NULL,
  description      VARCHAR(1000),
  credential_label VARCHAR(200) NOT NULL,
  credential_required TINYINT NOT NULL DEFAULT 0,
  enabled          TINYINT NOT NULL DEFAULT 1,
  sort_order       INT NOT NULL DEFAULT 0,
  version          INT NOT NULL DEFAULT 0,
  created_by       BIGINT,
  created_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by       BIGINT,
  updated_at       DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag     TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_reviewer_type UNIQUE (tenant_id, type_code),
  CONSTRAINT uk_g2_reviewer_type_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_reviewer_credential_required CHECK (credential_required IN (0, 1)),
  CONSTRAINT chk_g2_reviewer_enabled CHECK (enabled IN (0, 1)),
  CONSTRAINT fk_g2_reviewer_type_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_g2_reviewer_type_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 dynamic external reviewer type';
CALL __ses_g2_create_index('m_compliance_external_reviewer_type', 'idx_g2_reviewer_type_enabled',
  'CREATE INDEX idx_g2_reviewer_type_enabled ON m_compliance_external_reviewer_type (tenant_id, enabled, sort_order)');

CREATE TABLE IF NOT EXISTS m_compliance_mapping_review_requirement_group (
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                  VARCHAR(100) NOT NULL DEFAULT 'default',
  mapping_id                 BIGINT NOT NULL,
  requirement_group_code     VARCHAR(100) NOT NULL,
  display_name               VARCHAR(200) NOT NULL,
  minimum_distinct_reviewers INT NOT NULL,
  sort_order                 INT NOT NULL DEFAULT 0,
  version                    INT NOT NULL DEFAULT 0,
  created_by                 BIGINT,
  created_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by                 BIGINT,
  updated_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag               TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_review_group UNIQUE (tenant_id, mapping_id, requirement_group_code),
  CONSTRAINT uk_g2_review_group_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_review_group_minimum CHECK (minimum_distinct_reviewers >= 1),
  CONSTRAINT fk_g2_review_group_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_review_group_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_g2_review_group_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 external review requirement group';
CALL __ses_g2_create_index('m_compliance_mapping_review_requirement_group', 'idx_g2_review_group_mapping',
  'CREATE INDEX idx_g2_review_group_mapping ON m_compliance_mapping_review_requirement_group (tenant_id, mapping_id, sort_order)');

CREATE TABLE IF NOT EXISTS m_compliance_mapping_review_requirement_type (
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                  VARCHAR(100) NOT NULL DEFAULT 'default',
  requirement_group_id       BIGINT NOT NULL,
  reviewer_type_id           BIGINT NOT NULL,
  reviewer_type_code_snapshot VARCHAR(100) NOT NULL,
  reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  credential_label_snapshot  VARCHAR(200) NOT NULL,
  credential_required_snapshot TINYINT NOT NULL,
  created_by                 BIGINT,
  created_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_by                 BIGINT,
  updated_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag               TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_review_type UNIQUE (tenant_id, requirement_group_id, reviewer_type_id),
  CONSTRAINT uk_g2_review_type_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT fk_g2_review_type_group FOREIGN KEY (tenant_id, requirement_group_id)
    REFERENCES m_compliance_mapping_review_requirement_group(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_review_type_reviewer FOREIGN KEY (tenant_id, reviewer_type_id)
    REFERENCES m_compliance_external_reviewer_type(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_review_type_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 frozen reviewer type requirement';
CALL __ses_g2_create_index('m_compliance_mapping_review_requirement_type', 'idx_g2_review_type_reviewer',
  'CREATE INDEX idx_g2_review_type_reviewer ON m_compliance_mapping_review_requirement_type (tenant_id, reviewer_type_id)');

CREATE TABLE IF NOT EXISTS t_compliance_responsible_assignment (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     VARCHAR(100) NOT NULL DEFAULT 'default',
  workplace_id  BIGINT NOT NULL,
  user_id       BIGINT NOT NULL,
  role_code     VARCHAR(40) NOT NULL DEFAULT 'COMPLIANCE_RESPONSIBLE',
  effective_from DATETIME(6) NOT NULL,
  effective_to  DATETIME(6),
  active_slot   TINYINT,
  assigned_by   BIGINT NOT NULL,
  ended_by      BIGINT,
  end_reason    VARCHAR(500),
  version       INT NOT NULL DEFAULT 0,
  created_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at    DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag  TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_assignment_active_slot UNIQUE (tenant_id, workplace_id, active_slot),
  CONSTRAINT uk_g2_assignment_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_assignment_role CHECK (role_code = 'COMPLIANCE_RESPONSIBLE'),
  CONSTRAINT chk_g2_assignment_period CHECK (effective_to IS NULL OR effective_from < effective_to),
  CONSTRAINT chk_g2_assignment_open_fields CHECK (
    (effective_to IS NULL AND active_slot = 1 AND ended_by IS NULL AND end_reason IS NULL)
    OR (effective_to IS NOT NULL AND active_slot IS NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL)
  ),
  CONSTRAINT fk_g2_assignment_workplace FOREIGN KEY (tenant_id, workplace_id) REFERENCES m_workplace(tenant_id, id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_assignment_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_assignment_assigned_by FOREIGN KEY (assigned_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_assignment_ended_by FOREIGN KEY (ended_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 workplace compliance responsible assignment';
CALL __ses_g2_create_index('t_compliance_responsible_assignment', 'idx_g2_assignment_period',
  'CREATE INDEX idx_g2_assignment_period ON t_compliance_responsible_assignment (tenant_id, workplace_id, effective_from, effective_to)');
CALL __ses_g2_create_index('t_compliance_responsible_assignment', 'idx_g2_assignment_user_period',
  'CREATE INDEX idx_g2_assignment_user_period ON t_compliance_responsible_assignment (tenant_id, user_id, effective_from, effective_to)');

CREATE TABLE IF NOT EXISTS t_compliance_mapping_approval_event (
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                  VARCHAR(100) NOT NULL DEFAULT 'default',
  mapping_id                 BIGINT NOT NULL,
  mapping_version            VARCHAR(50) NOT NULL,
  mapping_hash               CHAR(64) NOT NULL,
  review_policy_hash         CHAR(64) NOT NULL,
  assignment_id              BIGINT NOT NULL,
  workplace_id_snapshot      BIGINT NOT NULL,
  actor_id                   BIGINT NOT NULL,
  actor_display_name_snapshot VARCHAR(200) NOT NULL,
  actor_role_snapshot        VARCHAR(50) NOT NULL,
  action                     VARCHAR(20) NOT NULL,
  event_chain_id             VARCHAR(36) NOT NULL,
  target_event_id            BIGINT,
  supersedes_event_id        BIGINT,
  occurred_at                DATETIME(6) NOT NULL,
  reason                     VARCHAR(1000),
  evidence_document_id       BIGINT,
  evidence_document_version_id BIGINT,
  evidence_document_version  VARCHAR(100),
  evidence_document_hash     CHAR(64),
  operation_id               VARCHAR(36) NOT NULL,
  correlation_id             VARCHAR(100) NOT NULL,
  idempotency_key            VARCHAR(200) NOT NULL,
  created_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_g2_approval_idempotency UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT uk_g2_approval_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_approval_action CHECK (action IN ('APPROVE', 'REJECT', 'REVOKE')),
  CONSTRAINT fk_g2_approval_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_assignment FOREIGN KEY (tenant_id, assignment_id) REFERENCES t_compliance_responsible_assignment(tenant_id, id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_workplace FOREIGN KEY (tenant_id, workplace_id_snapshot) REFERENCES m_workplace(tenant_id, id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_target FOREIGN KEY (tenant_id, target_event_id) REFERENCES t_compliance_mapping_approval_event(tenant_id, id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_supersedes FOREIGN KEY (tenant_id, supersedes_event_id) REFERENCES t_compliance_mapping_approval_event(tenant_id, id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_evidence_document FOREIGN KEY (evidence_document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_evidence_version FOREIGN KEY (evidence_document_version_id) REFERENCES t_document_version(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 internal approval append-only event';
CALL __ses_g2_create_index('t_compliance_mapping_approval_event', 'idx_g2_approval_scope',
  'CREATE INDEX idx_g2_approval_scope ON t_compliance_mapping_approval_event (tenant_id, mapping_id, workplace_id_snapshot, assignment_id, occurred_at, id)');
CALL __ses_g2_create_index('t_compliance_mapping_approval_event', 'idx_g2_approval_chain',
  'CREATE INDEX idx_g2_approval_chain ON t_compliance_mapping_approval_event (tenant_id, event_chain_id, occurred_at, id)');

CREATE TABLE IF NOT EXISTS t_compliance_external_review_event (
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                  VARCHAR(100) NOT NULL DEFAULT 'default',
  mapping_id                 BIGINT NOT NULL,
  mapping_version            VARCHAR(50) NOT NULL,
  mapping_hash               CHAR(64) NOT NULL,
  review_policy_hash         CHAR(64) NOT NULL,
  requirement_group_id       BIGINT NOT NULL,
  requirement_group_code_snapshot VARCHAR(100) NOT NULL,
  reviewer_type_id           BIGINT NOT NULL,
  reviewer_type_code_snapshot VARCHAR(100) NOT NULL,
  reviewer_type_name_snapshot VARCHAR(200) NOT NULL,
  reviewer_name_snapshot     VARCHAR(200) NOT NULL,
  organization_snapshot      VARCHAR(255),
  credential_snapshot_encrypted TEXT,
  credential_key_version     VARCHAR(64),
  credential_cipher_format   VARCHAR(20),
  credential_masked_snapshot VARCHAR(255),
  reviewer_identity_hash     CHAR(64) NOT NULL,
  action                     VARCHAR(20) NOT NULL,
  review_chain_id            VARCHAR(36) NOT NULL,
  target_event_id            BIGINT,
  supersedes_event_id        BIGINT,
  reviewed_at                DATETIME(6) NOT NULL,
  valid_until                DATETIME(6),
  recorded_at                DATETIME(6) NOT NULL,
  evidence_document_id       BIGINT,
  evidence_document_version_id BIGINT,
  evidence_document_version  VARCHAR(100),
  evidence_document_hash     CHAR(64),
  recorded_by                BIGINT NOT NULL,
  operation_id               VARCHAR(36) NOT NULL,
  correlation_id             VARCHAR(100) NOT NULL,
  idempotency_key            VARCHAR(200) NOT NULL,
  created_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_g2_external_review_idempotency UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT uk_g2_external_review_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_external_review_action CHECK (action IN ('APPROVED', 'REJECTED', 'REVOKED')),
  CONSTRAINT chk_g2_external_credential_pair CHECK (
    (credential_snapshot_encrypted IS NULL AND credential_key_version IS NULL
      AND credential_cipher_format IS NULL AND credential_masked_snapshot IS NULL)
    OR (credential_snapshot_encrypted IS NOT NULL AND credential_key_version IS NOT NULL
      AND credential_cipher_format IS NOT NULL AND credential_masked_snapshot IS NOT NULL)
  ),
  CONSTRAINT fk_g2_external_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_group FOREIGN KEY (tenant_id, requirement_group_id)
    REFERENCES m_compliance_mapping_review_requirement_group(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_reviewer_type FOREIGN KEY (tenant_id, reviewer_type_id)
    REFERENCES m_compliance_external_reviewer_type(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_target FOREIGN KEY (tenant_id, target_event_id)
    REFERENCES t_compliance_external_review_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_supersedes FOREIGN KEY (tenant_id, supersedes_event_id)
    REFERENCES t_compliance_external_review_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_evidence_document FOREIGN KEY (evidence_document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_evidence_version FOREIGN KEY (evidence_document_version_id) REFERENCES t_document_version(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_recorded_by FOREIGN KEY (recorded_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 external review append-only event';
CALL __ses_g2_create_index('t_compliance_external_review_event', 'idx_g2_external_review_scope',
  'CREATE INDEX idx_g2_external_review_scope ON t_compliance_external_review_event (tenant_id, mapping_id, requirement_group_id, reviewer_identity_hash, recorded_at, id)');
CALL __ses_g2_create_index('t_compliance_external_review_event', 'idx_g2_external_review_chain',
  'CREATE INDEX idx_g2_external_review_chain ON t_compliance_external_review_event (tenant_id, review_chain_id, recorded_at, id)');
CALL __ses_g2_create_index('t_compliance_external_review_event', 'idx_g2_external_review_valid_until',
  'CREATE INDEX idx_g2_external_review_valid_until ON t_compliance_external_review_event (tenant_id, valid_until)');

CREATE TABLE IF NOT EXISTS t_compliance_mapping_status_event (
  id                         BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                  VARCHAR(100) NOT NULL DEFAULT 'default',
  mapping_id                 BIGINT NOT NULL,
  mapping_version            VARCHAR(50) NOT NULL,
  mapping_hash               CHAR(64) NOT NULL,
  review_policy_hash         CHAR(64) NOT NULL,
  before_status              VARCHAR(30),
  after_status               VARCHAR(30) NOT NULL,
  actor_id                   BIGINT NOT NULL,
  actor_display_name_snapshot VARCHAR(200) NOT NULL,
  actor_role_snapshot        VARCHAR(50) NOT NULL,
  occurred_at                DATETIME(6) NOT NULL,
  expected_version           INT NOT NULL,
  gate_snapshot_hash         CHAR(64),
  operation_id               VARCHAR(36) NOT NULL,
  correlation_id             VARCHAR(100) NOT NULL,
  reason                     VARCHAR(1000),
  created_at                 DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_g2_status_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_g2_status_after CHECK (after_status IN ('DRAFT', 'PROVISIONAL_REVIEWED', 'ACTIVE', 'SUPERSEDED')),
  CONSTRAINT fk_g2_status_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_status_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 mapping status append-only event';
CALL __ses_g2_create_index('t_compliance_mapping_status_event', 'idx_g2_status_mapping',
  'CREATE INDEX idx_g2_status_mapping ON t_compliance_mapping_status_event (tenant_id, mapping_id, occurred_at, id)');
CALL __ses_g2_create_index('t_compliance_mapping_status_event', 'idx_g2_status_correlation',
  'CREATE INDEX idx_g2_status_correlation ON t_compliance_mapping_status_event (tenant_id, correlation_id)');

CREATE TABLE IF NOT EXISTS t_compliance_operation_ledger (
  id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                VARCHAR(100) NOT NULL DEFAULT 'default',
  operation_id             VARCHAR(36) NOT NULL,
  operation_type           VARCHAR(60) NOT NULL,
  idempotency_key          VARCHAR(200) NOT NULL,
  request_hash             CHAR(64) NOT NULL,
  state                    VARCHAR(20) NOT NULL,
  retryable_flag           TINYINT NOT NULL DEFAULT 0,
  attempt_count            INT NOT NULL DEFAULT 1,
  started_at               DATETIME(6) NOT NULL,
  lease_until              DATETIME(6),
  finished_at              DATETIME(6),
  result_reference_type    VARCHAR(80),
  result_reference_id      BIGINT,
  result_reference_version VARCHAR(100),
  result_summary_canonical TEXT,
  result_http_status       INT,
  result_hash              CHAR(64),
  failure_code             VARCHAR(100),
  correlation_id           VARCHAR(100) NOT NULL,
  expires_at               DATETIME(6),
  version                  INT NOT NULL DEFAULT 0,
  created_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at               DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  deleted_flag             TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_g2_operation_key UNIQUE (tenant_id, operation_type, idempotency_key),
  CONSTRAINT uk_g2_operation_id UNIQUE (tenant_id, operation_id),
  CONSTRAINT chk_g2_operation_type CHECK (operation_type IN (
    'MAPPING_DRAFT_UPSERT', 'MAPPING_PROVISIONAL_REVIEW', 'ASSIGNMENT_CREATE', 'ASSIGNMENT_END',
    'MAPPING_ACTIVE', 'MAPPING_SUPERSEDE', 'INTERNAL_APPROVAL', 'EXTERNAL_REVIEW',
    'EXTERNAL_REVIEW_REVOKE', 'DELIVERY_GENERATE', 'REVIEWER_TYPE_CREATE', 'REVIEWER_TYPE_UPDATE',
    'REVIEWER_TYPE_DISABLE', 'REVIEW_REQUIREMENT_UPDATE')),
  CONSTRAINT chk_g2_operation_state CHECK (state IN ('PROCESSING', 'SUCCEEDED', 'FAILED')),
  CONSTRAINT chk_g2_operation_retryable CHECK (retryable_flag IN (0, 1)),
  CONSTRAINT chk_g2_operation_result CHECK (
    (state = 'SUCCEEDED' AND finished_at IS NOT NULL AND failure_code IS NULL
      AND result_summary_canonical IS NOT NULL AND result_http_status IS NOT NULL AND result_hash IS NOT NULL)
    OR (state = 'PROCESSING' AND finished_at IS NULL AND failure_code IS NULL
      AND result_reference_type IS NULL AND result_reference_id IS NULL AND result_reference_version IS NULL
      AND result_summary_canonical IS NULL AND result_http_status IS NULL AND result_hash IS NULL)
    OR (state = 'FAILED' AND finished_at IS NOT NULL AND failure_code IS NOT NULL
      AND result_reference_type IS NULL AND result_reference_id IS NULL AND result_reference_version IS NULL
      AND result_summary_canonical IS NULL AND result_http_status IS NULL AND result_hash IS NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 state-changing operation idempotency ledger';
CALL __ses_g2_create_index('t_compliance_operation_ledger', 'idx_g2_operation_lease',
  'CREATE INDEX idx_g2_operation_lease ON t_compliance_operation_ledger (tenant_id, state, lease_until)');
CALL __ses_g2_create_index('t_compliance_operation_ledger', 'idx_g2_operation_result',
  'CREATE INDEX idx_g2_operation_result ON t_compliance_operation_ledger (tenant_id, result_reference_type, result_reference_id)');

-- 同名indexが存在するだけではpartial/old-definitionを安全に再開できない。
-- 列順、列数、UNIQUE属性をcanonical manifestと照合し、不一致はforward repairを要求する。
DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_g2_assert_index$$
CREATE PROCEDURE __ses_g2_assert_index(IN p_table VARCHAR(64), IN p_index VARCHAR(64),
                                       IN p_columns TEXT, IN p_non_unique INT)
BEGIN
  DECLARE v_expected_count INT;
  DECLARE v_actual_count INT;
  DECLARE v_actual_non_unique INT;
  DECLARE v_actual_columns TEXT;
  SET v_expected_count = 1 + LENGTH(p_columns) - LENGTH(REPLACE(p_columns, ',', ''));
  SELECT COUNT(*), COALESCE(MAX(NON_UNIQUE), -1),
         COALESCE(GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ','), '')
    INTO v_actual_count, v_actual_non_unique, v_actual_columns
    FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND INDEX_NAME = p_index;
  IF v_actual_count <> v_expected_count OR v_actual_non_unique <> p_non_unique
     OR v_actual_columns <> p_columns THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2_V102_INDEX_SHAPE_MISMATCH forward repair required';
  END IF;
END$$
DELIMITER ;
CALL __ses_g2_assert_index('m_compliance_mapping_version', 'idx_g2_mapping_effective',
  'tenant_id,mapping_code,status,effective_from,effective_to', 1);
CALL __ses_g2_assert_index('m_compliance_mapping_source', 'idx_g2_mapping_source_lookup',
  'tenant_id,source_code,confirmed_on', 1);
CALL __ses_g2_assert_index('m_compliance_external_reviewer_type', 'idx_g2_reviewer_type_enabled',
  'tenant_id,enabled,sort_order', 1);
CALL __ses_g2_assert_index('m_compliance_mapping_review_requirement_group', 'idx_g2_review_group_mapping',
  'tenant_id,mapping_id,sort_order', 1);
CALL __ses_g2_assert_index('m_compliance_mapping_review_requirement_type', 'idx_g2_review_type_reviewer',
  'tenant_id,reviewer_type_id', 1);
CALL __ses_g2_assert_index('t_compliance_responsible_assignment', 'idx_g2_assignment_period',
  'tenant_id,workplace_id,effective_from,effective_to', 1);
CALL __ses_g2_assert_index('t_compliance_responsible_assignment', 'idx_g2_assignment_user_period',
  'tenant_id,user_id,effective_from,effective_to', 1);
CALL __ses_g2_assert_index('t_compliance_mapping_approval_event', 'idx_g2_approval_scope',
  'tenant_id,mapping_id,workplace_id_snapshot,assignment_id,occurred_at,id', 1);
CALL __ses_g2_assert_index('t_compliance_mapping_approval_event', 'idx_g2_approval_chain',
  'tenant_id,event_chain_id,occurred_at,id', 1);
CALL __ses_g2_assert_index('t_compliance_external_review_event', 'idx_g2_external_review_scope',
  'tenant_id,mapping_id,requirement_group_id,reviewer_identity_hash,recorded_at,id', 1);
CALL __ses_g2_assert_index('t_compliance_external_review_event', 'idx_g2_external_review_chain',
  'tenant_id,review_chain_id,recorded_at,id', 1);
CALL __ses_g2_assert_index('t_compliance_external_review_event', 'idx_g2_external_review_valid_until',
  'tenant_id,valid_until', 1);
CALL __ses_g2_assert_index('t_compliance_mapping_status_event', 'idx_g2_status_mapping',
  'tenant_id,mapping_id,occurred_at,id', 1);
CALL __ses_g2_assert_index('t_compliance_mapping_status_event', 'idx_g2_status_correlation',
  'tenant_id,correlation_id', 1);
CALL __ses_g2_assert_index('t_compliance_operation_ledger', 'idx_g2_operation_lease',
  'tenant_id,state,lease_until', 1);
CALL __ses_g2_assert_index('t_compliance_operation_ledger', 'idx_g2_operation_result',
  'tenant_id,result_reference_type,result_reference_id', 1);
CALL __ses_g2_assert_index('m_compliance_mapping_version', 'uk_g2_mapping_version',
  'tenant_id,mapping_version', 0);
CALL __ses_g2_assert_index('m_compliance_mapping_version', 'uk_g2_mapping_active_slot',
  'tenant_id,mapping_code,active_slot', 0);
CALL __ses_g2_assert_index('m_compliance_mapping_version', 'uk_g2_mapping_future_slot',
  'tenant_id,mapping_code,future_slot', 0);
CALL __ses_g2_assert_index('m_compliance_mapping_version', 'uk_g2_mapping_tenant_id',
  'tenant_id,id', 0);
CALL __ses_g2_assert_index('m_compliance_mapping_source', 'uk_g2_mapping_source',
  'tenant_id,mapping_id,source_code', 0);
CALL __ses_g2_assert_index('m_compliance_mapping_source', 'uk_g2_mapping_source_tenant_id',
  'tenant_id,id', 0);
CALL __ses_g2_assert_index('m_compliance_external_reviewer_type', 'uk_g2_reviewer_type',
  'tenant_id,type_code', 0);
CALL __ses_g2_assert_index('m_compliance_external_reviewer_type', 'uk_g2_reviewer_type_tenant_id',
  'tenant_id,id', 0);
CALL __ses_g2_assert_index('m_compliance_mapping_review_requirement_group', 'uk_g2_review_group',
  'tenant_id,mapping_id,requirement_group_code', 0);
CALL __ses_g2_assert_index('m_compliance_mapping_review_requirement_group', 'uk_g2_review_group_tenant_id',
  'tenant_id,id', 0);
CALL __ses_g2_assert_index('m_compliance_mapping_review_requirement_type', 'uk_g2_review_type',
  'tenant_id,requirement_group_id,reviewer_type_id', 0);
CALL __ses_g2_assert_index('m_compliance_mapping_review_requirement_type', 'uk_g2_review_type_tenant_id',
  'tenant_id,id', 0);
CALL __ses_g2_assert_index('t_compliance_responsible_assignment', 'uk_g2_assignment_active_slot',
  'tenant_id,workplace_id,active_slot', 0);
CALL __ses_g2_assert_index('t_compliance_responsible_assignment', 'uk_g2_assignment_tenant_id',
  'tenant_id,id', 0);
CALL __ses_g2_assert_index('t_compliance_mapping_approval_event', 'uk_g2_approval_idempotency',
  'tenant_id,idempotency_key', 0);
CALL __ses_g2_assert_index('t_compliance_mapping_approval_event', 'uk_g2_approval_tenant_id',
  'tenant_id,id', 0);
CALL __ses_g2_assert_index('t_compliance_external_review_event', 'uk_g2_external_review_idempotency',
  'tenant_id,idempotency_key', 0);
CALL __ses_g2_assert_index('t_compliance_external_review_event', 'uk_g2_external_review_tenant_id',
  'tenant_id,id', 0);
CALL __ses_g2_assert_index('t_compliance_mapping_status_event', 'uk_g2_status_tenant_id',
  'tenant_id,id', 0);
CALL __ses_g2_assert_index('t_compliance_operation_ledger', 'uk_g2_operation_key',
  'tenant_id,operation_type,idempotency_key', 0);
CALL __ses_g2_assert_index('t_compliance_operation_ledger', 'uk_g2_operation_id',
  'tenant_id,operation_id', 0);

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_g2_assert_constraint$$
CREATE PROCEDURE __ses_g2_assert_constraint(IN p_table VARCHAR(64), IN p_constraint VARCHAR(64),
                                            IN p_type VARCHAR(30))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
                  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                    AND CONSTRAINT_NAME = p_constraint AND CONSTRAINT_TYPE = p_type) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2_V102_CONSTRAINT_SHAPE_MISMATCH forward repair required';
  END IF;
END$$
DELIMITER ;

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_g2_repair_check$$
CREATE PROCEDURE __ses_g2_repair_check(IN p_table VARCHAR(64), IN p_constraint VARCHAR(64), IN p_sql TEXT)
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.TABLE_CONSTRAINTS
              WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = p_table
                AND CONSTRAINT_NAME = p_constraint AND CONSTRAINT_TYPE = 'CHECK') THEN
    SET @g2_drop_check_sql = CONCAT('ALTER TABLE ', p_table, ' DROP CHECK ', p_constraint);
    PREPARE g2_drop_check_stmt FROM @g2_drop_check_sql;
    EXECUTE g2_drop_check_stmt;
    DEALLOCATE PREPARE g2_drop_check_stmt;
  END IF;
  SET @g2_add_check_sql = p_sql;
  PREPARE g2_add_check_stmt FROM @g2_add_check_sql;
  EXECUTE g2_add_check_stmt;
  DEALLOCATE PREPARE g2_add_check_stmt;
END$$
DELIMITER ;
CALL __ses_g2_repair_check('m_compliance_mapping_version', 'chk_g2_mapping_status',
  'ALTER TABLE m_compliance_mapping_version ADD CONSTRAINT chk_g2_mapping_status CHECK (status IN (''DRAFT'',''PROVISIONAL_REVIEWED'',''ACTIVE'',''SUPERSEDED''))');
CALL __ses_g2_repair_check('m_compliance_mapping_version', 'chk_g2_mapping_active_slot',
  'ALTER TABLE m_compliance_mapping_version ADD CONSTRAINT chk_g2_mapping_active_slot CHECK ((status = ''ACTIVE'' AND active_slot = 1) OR (status <> ''ACTIVE'' AND active_slot IS NULL))');
CALL __ses_g2_repair_check('m_compliance_mapping_version', 'chk_g2_mapping_future_slot',
  'ALTER TABLE m_compliance_mapping_version ADD CONSTRAINT chk_g2_mapping_future_slot CHECK (future_slot IS NULL OR future_slot = 1)');
CALL __ses_g2_repair_check('m_compliance_external_reviewer_type', 'chk_g2_reviewer_credential_required',
  'ALTER TABLE m_compliance_external_reviewer_type ADD CONSTRAINT chk_g2_reviewer_credential_required CHECK (credential_required IN (0,1))');
CALL __ses_g2_repair_check('m_compliance_external_reviewer_type', 'chk_g2_reviewer_enabled',
  'ALTER TABLE m_compliance_external_reviewer_type ADD CONSTRAINT chk_g2_reviewer_enabled CHECK (enabled IN (0,1))');
CALL __ses_g2_repair_check('m_compliance_mapping_review_requirement_group', 'chk_g2_review_group_minimum',
  'ALTER TABLE m_compliance_mapping_review_requirement_group ADD CONSTRAINT chk_g2_review_group_minimum CHECK (minimum_distinct_reviewers >= 1)');
CALL __ses_g2_repair_check('t_compliance_responsible_assignment', 'chk_g2_assignment_role',
  'ALTER TABLE t_compliance_responsible_assignment ADD CONSTRAINT chk_g2_assignment_role CHECK (role_code = ''COMPLIANCE_RESPONSIBLE'')');
CALL __ses_g2_repair_check('t_compliance_responsible_assignment', 'chk_g2_assignment_period',
  'ALTER TABLE t_compliance_responsible_assignment ADD CONSTRAINT chk_g2_assignment_period CHECK (effective_to IS NULL OR effective_from < effective_to)');
CALL __ses_g2_repair_check('t_compliance_responsible_assignment', 'chk_g2_assignment_open_fields',
  'ALTER TABLE t_compliance_responsible_assignment ADD CONSTRAINT chk_g2_assignment_open_fields CHECK ((effective_to IS NULL AND active_slot = 1 AND ended_by IS NULL AND end_reason IS NULL) OR (effective_to IS NOT NULL AND active_slot IS NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL))');
CALL __ses_g2_repair_check('t_compliance_mapping_approval_event', 'chk_g2_approval_action',
  'ALTER TABLE t_compliance_mapping_approval_event ADD CONSTRAINT chk_g2_approval_action CHECK (action IN (''APPROVE'',''REJECT'',''REVOKE''))');
CALL __ses_g2_repair_check('t_compliance_external_review_event', 'chk_g2_external_review_action',
  'ALTER TABLE t_compliance_external_review_event ADD CONSTRAINT chk_g2_external_review_action CHECK (action IN (''APPROVED'',''REJECTED'',''REVOKED''))');
CALL __ses_g2_repair_check('t_compliance_external_review_event', 'chk_g2_external_credential_pair',
  'ALTER TABLE t_compliance_external_review_event ADD CONSTRAINT chk_g2_external_credential_pair CHECK ((credential_snapshot_encrypted IS NULL AND credential_key_version IS NULL AND credential_cipher_format IS NULL AND credential_masked_snapshot IS NULL) OR (credential_snapshot_encrypted IS NOT NULL AND credential_key_version IS NOT NULL AND credential_cipher_format IS NOT NULL AND credential_masked_snapshot IS NOT NULL))');
CALL __ses_g2_repair_check('t_compliance_mapping_status_event', 'chk_g2_status_after',
  'ALTER TABLE t_compliance_mapping_status_event ADD CONSTRAINT chk_g2_status_after CHECK (after_status IN (''DRAFT'',''PROVISIONAL_REVIEWED'',''ACTIVE'',''SUPERSEDED''))');
CALL __ses_g2_repair_check('t_compliance_operation_ledger', 'chk_g2_operation_type',
  'ALTER TABLE t_compliance_operation_ledger ADD CONSTRAINT chk_g2_operation_type CHECK (operation_type IN (''MAPPING_DRAFT_UPSERT'',''MAPPING_PROVISIONAL_REVIEW'',''ASSIGNMENT_CREATE'',''ASSIGNMENT_END'',''MAPPING_ACTIVE'',''MAPPING_SUPERSEDE'',''INTERNAL_APPROVAL'',''EXTERNAL_REVIEW'',''EXTERNAL_REVIEW_REVOKE'',''DELIVERY_GENERATE'',''REVIEWER_TYPE_CREATE'',''REVIEWER_TYPE_UPDATE'',''REVIEWER_TYPE_DISABLE'',''REVIEW_REQUIREMENT_UPDATE''))');
CALL __ses_g2_repair_check('t_compliance_operation_ledger', 'chk_g2_operation_state',
  'ALTER TABLE t_compliance_operation_ledger ADD CONSTRAINT chk_g2_operation_state CHECK (state IN (''PROCESSING'',''SUCCEEDED'',''FAILED''))');
CALL __ses_g2_repair_check('t_compliance_operation_ledger', 'chk_g2_operation_retryable',
  'ALTER TABLE t_compliance_operation_ledger ADD CONSTRAINT chk_g2_operation_retryable CHECK (retryable_flag IN (0,1))');
CALL __ses_g2_repair_check('t_compliance_operation_ledger', 'chk_g2_operation_result',
  'ALTER TABLE t_compliance_operation_ledger ADD CONSTRAINT chk_g2_operation_result CHECK ((state = ''SUCCEEDED'' AND finished_at IS NOT NULL AND failure_code IS NULL AND result_summary_canonical IS NOT NULL AND result_http_status IS NOT NULL AND result_hash IS NOT NULL) OR (state = ''PROCESSING'' AND finished_at IS NULL AND failure_code IS NULL AND result_reference_type IS NULL AND result_reference_id IS NULL AND result_reference_version IS NULL AND result_summary_canonical IS NULL AND result_http_status IS NULL AND result_hash IS NULL) OR (state = ''FAILED'' AND finished_at IS NOT NULL AND failure_code IS NOT NULL AND result_reference_type IS NULL AND result_reference_id IS NULL AND result_reference_version IS NULL AND result_summary_canonical IS NULL AND result_http_status IS NULL AND result_hash IS NULL))');
DROP PROCEDURE IF EXISTS __ses_g2_repair_check;
CALL __ses_g2_assert_constraint('m_compliance_mapping_version', 'uk_g2_mapping_version', 'UNIQUE');
CALL __ses_g2_assert_constraint('m_compliance_mapping_version', 'uk_g2_mapping_active_slot', 'UNIQUE');
CALL __ses_g2_assert_constraint('m_compliance_mapping_version', 'uk_g2_mapping_future_slot', 'UNIQUE');
CALL __ses_g2_assert_constraint('m_compliance_mapping_version', 'chk_g2_mapping_status', 'CHECK');
CALL __ses_g2_assert_constraint('m_compliance_mapping_version', 'chk_g2_mapping_active_slot', 'CHECK');
CALL __ses_g2_assert_constraint('m_compliance_mapping_version', 'chk_g2_mapping_future_slot', 'CHECK');
CALL __ses_g2_assert_constraint('m_compliance_mapping_source', 'uk_g2_mapping_source', 'UNIQUE');
CALL __ses_g2_assert_constraint('m_compliance_external_reviewer_type', 'uk_g2_reviewer_type', 'UNIQUE');
CALL __ses_g2_assert_constraint('m_compliance_mapping_review_requirement_group', 'uk_g2_review_group', 'UNIQUE');
CALL __ses_g2_assert_constraint('m_compliance_mapping_review_requirement_group', 'chk_g2_review_group_minimum', 'CHECK');
CALL __ses_g2_assert_constraint('m_compliance_mapping_review_requirement_type', 'uk_g2_review_type', 'UNIQUE');
CALL __ses_g2_assert_constraint('t_compliance_responsible_assignment', 'uk_g2_assignment_active_slot', 'UNIQUE');
CALL __ses_g2_assert_constraint('t_compliance_responsible_assignment', 'chk_g2_assignment_period', 'CHECK');
CALL __ses_g2_assert_constraint('t_compliance_responsible_assignment', 'chk_g2_assignment_open_fields', 'CHECK');
CALL __ses_g2_assert_constraint('t_compliance_mapping_approval_event', 'uk_g2_approval_idempotency', 'UNIQUE');
CALL __ses_g2_assert_constraint('t_compliance_mapping_approval_event', 'chk_g2_approval_action', 'CHECK');
CALL __ses_g2_assert_constraint('t_compliance_external_review_event', 'uk_g2_external_review_idempotency', 'UNIQUE');
CALL __ses_g2_assert_constraint('t_compliance_external_review_event', 'chk_g2_external_review_action', 'CHECK');
CALL __ses_g2_assert_constraint('t_compliance_external_review_event', 'chk_g2_external_credential_pair', 'CHECK');
CALL __ses_g2_assert_constraint('t_compliance_mapping_status_event', 'chk_g2_status_after', 'CHECK');
CALL __ses_g2_assert_constraint('t_compliance_operation_ledger', 'uk_g2_operation_key', 'UNIQUE');
CALL __ses_g2_assert_constraint('t_compliance_operation_ledger', 'uk_g2_operation_id', 'UNIQUE');
CALL __ses_g2_assert_constraint('t_compliance_operation_ledger', 'chk_g2_operation_type', 'CHECK');
CALL __ses_g2_assert_constraint('t_compliance_operation_ledger', 'chk_g2_operation_state', 'CHECK');
CALL __ses_g2_assert_constraint('t_compliance_operation_ledger', 'chk_g2_operation_retryable', 'CHECK');
CALL __ses_g2_assert_constraint('t_compliance_operation_ledger', 'chk_g2_operation_result', 'CHECK');
DROP PROCEDURE IF EXISTS __ses_g2_assert_constraint;
DROP PROCEDURE IF EXISTS __ses_g2_create_index;
CALL __ses_g2_assert_shape('m_compliance_mapping_source',
  'id,tenant_id,mapping_id,source_code,source_url,source_version,confirmed_on,effective_from,effective_to,created_by,created_at,updated_by,updated_at,deleted_flag');
CALL __ses_g2_assert_shape('m_compliance_external_reviewer_type',
  'id,tenant_id,type_code,display_name,description,credential_label,credential_required,enabled,sort_order,version,created_by,created_at,updated_by,updated_at,deleted_flag');
CALL __ses_g2_assert_shape('m_compliance_mapping_review_requirement_group',
  'id,tenant_id,mapping_id,requirement_group_code,display_name,minimum_distinct_reviewers,sort_order,version,created_by,created_at,updated_by,updated_at,deleted_flag');
CALL __ses_g2_assert_shape('m_compliance_mapping_review_requirement_type',
  'id,tenant_id,requirement_group_id,reviewer_type_id,reviewer_type_code_snapshot,reviewer_type_name_snapshot,credential_label_snapshot,credential_required_snapshot,created_by,created_at,updated_by,updated_at,deleted_flag');
CALL __ses_g2_assert_shape('t_compliance_responsible_assignment',
  'id,tenant_id,workplace_id,user_id,role_code,effective_from,effective_to,active_slot,assigned_by,ended_by,end_reason,version,created_at,updated_at,deleted_flag');
CALL __ses_g2_assert_shape('t_compliance_mapping_approval_event',
  'id,tenant_id,mapping_id,mapping_version,mapping_hash,review_policy_hash,assignment_id,workplace_id_snapshot,actor_id,actor_display_name_snapshot,actor_role_snapshot,action,event_chain_id,target_event_id,supersedes_event_id,occurred_at,reason,evidence_document_id,evidence_document_version_id,evidence_document_version,evidence_document_hash,operation_id,correlation_id,idempotency_key,created_at');
CALL __ses_g2_assert_shape('t_compliance_external_review_event',
  'id,tenant_id,mapping_id,mapping_version,mapping_hash,review_policy_hash,requirement_group_id,requirement_group_code_snapshot,reviewer_type_id,reviewer_type_code_snapshot,reviewer_type_name_snapshot,reviewer_name_snapshot,organization_snapshot,credential_snapshot_encrypted,credential_key_version,credential_cipher_format,credential_masked_snapshot,reviewer_identity_hash,action,review_chain_id,target_event_id,supersedes_event_id,reviewed_at,valid_until,recorded_at,evidence_document_id,evidence_document_version_id,evidence_document_version,evidence_document_hash,recorded_by,operation_id,correlation_id,idempotency_key,created_at');
CALL __ses_g2_assert_shape('t_compliance_mapping_status_event',
  'id,tenant_id,mapping_id,mapping_version,mapping_hash,review_policy_hash,before_status,after_status,actor_id,actor_display_name_snapshot,actor_role_snapshot,occurred_at,expected_version,gate_snapshot_hash,operation_id,correlation_id,reason,created_at');
CALL __ses_g2_assert_shape('t_compliance_operation_ledger',
  'id,tenant_id,operation_id,operation_type,idempotency_key,request_hash,state,retryable_flag,attempt_count,started_at,lease_until,finished_at,result_reference_type,result_reference_id,result_reference_version,result_summary_canonical,result_http_status,result_hash,failure_code,correlation_id,expires_at,version,created_at,updated_at,deleted_flag');
DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_g2_assert_column$$
CREATE PROCEDURE __ses_g2_assert_column(IN p_table VARCHAR(64), IN p_column VARCHAR(64),
                                        IN p_type VARCHAR(32), IN p_precision INT)
BEGIN
  DECLARE v_actual INT;
  SELECT COUNT(*) INTO v_actual
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
      AND LOWER(DATA_TYPE) = LOWER(p_type)
      AND COALESCE(DATETIME_PRECISION, 0) = p_precision;
  IF v_actual <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2_V102_COLUMN_SHAPE_MISMATCH forward repair required';
  END IF;
END$$
DELIMITER ;
CALL __ses_g2_assert_column('t_compliance_responsible_assignment', 'effective_from', 'datetime', 6);
CALL __ses_g2_assert_column('t_compliance_responsible_assignment', 'effective_to', 'datetime', 6);
DROP PROCEDURE IF EXISTS __ses_g2_assert_column;
DROP PROCEDURE IF EXISTS __ses_g2_assert_shape;

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_g2_assert_column_contract$$
CREATE PROCEDURE __ses_g2_assert_column_contract(IN p_table VARCHAR(64), IN p_column VARCHAR(64),
                                                 IN p_type VARCHAR(32), IN p_char_length INT,
                                                 IN p_datetime_precision INT, IN p_nullable VARCHAR(3),
                                                 IN p_default VARCHAR(64))
BEGIN
  DECLARE v_actual INT;
  SELECT COUNT(*) INTO v_actual
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table AND COLUMN_NAME = p_column
     AND LOWER(DATA_TYPE) = LOWER(p_type)
     AND (p_char_length IS NULL OR CHARACTER_MAXIMUM_LENGTH = p_char_length)
     AND (p_datetime_precision IS NULL OR COALESCE(DATETIME_PRECISION, 0) = p_datetime_precision)
     AND (p_nullable IS NULL OR IS_NULLABLE = p_nullable)
     AND (p_default IS NULL OR COALESCE(CAST(COLUMN_DEFAULT AS CHAR), '<NULL>') = p_default);
  IF v_actual <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2_V102_COLUMN_CONTRACT_MISMATCH forward repair required';
  END IF;
END$$
DELIMITER ;
CALL __ses_g2_assert_column_contract('m_compliance_mapping_version', 'mapping_hash', 'char', 64, NULL, 'NO', NULL);
CALL __ses_g2_assert_column_contract('m_compliance_mapping_version', 'review_policy_hash', 'char', 64, NULL, 'NO', NULL);
CALL __ses_g2_assert_column_contract('m_compliance_mapping_version', 'effective_from', 'date', NULL, NULL, 'NO', NULL);
CALL __ses_g2_assert_column_contract('m_compliance_mapping_version', 'status', 'varchar', 30, NULL, 'NO', NULL);
CALL __ses_g2_assert_column_contract('m_compliance_mapping_version', 'active_slot', 'tinyint', NULL, NULL, 'YES', NULL);
CALL __ses_g2_assert_column_contract('m_compliance_mapping_version', 'future_slot', 'tinyint', NULL, NULL, 'YES', NULL);
CALL __ses_g2_assert_column_contract('m_compliance_mapping_source', 'source_url', 'varchar', 1000, NULL, 'NO', NULL);
CALL __ses_g2_assert_column_contract('t_compliance_responsible_assignment', 'effective_from', 'datetime', NULL, 6, 'NO', NULL);
CALL __ses_g2_assert_column_contract('t_compliance_responsible_assignment', 'effective_to', 'datetime', NULL, 6, 'YES', NULL);
CALL __ses_g2_assert_column_contract('t_compliance_responsible_assignment', 'active_slot', 'tinyint', NULL, NULL, 'YES', NULL);
CALL __ses_g2_assert_column_contract('t_compliance_mapping_approval_event', 'occurred_at', 'datetime', NULL, 6, 'NO', NULL);
CALL __ses_g2_assert_column_contract('t_compliance_external_review_event', 'reviewed_at', 'datetime', NULL, 6, 'NO', NULL);
CALL __ses_g2_assert_column_contract('t_compliance_external_review_event', 'valid_until', 'datetime', NULL, 6, 'YES', NULL);
CALL __ses_g2_assert_column_contract('t_compliance_operation_ledger', 'request_hash', 'char', 64, NULL, 'NO', NULL);
CALL __ses_g2_assert_column_contract('t_compliance_operation_ledger', 'state', 'varchar', 20, NULL, 'NO', NULL);
CALL __ses_g2_assert_column_contract('t_compliance_operation_ledger', 'retryable_flag', 'tinyint', NULL, NULL, 'NO', '0');
CALL __ses_g2_assert_column_contract('t_compliance_operation_ledger', 'attempt_count', 'int', NULL, NULL, 'NO', '1');
CALL __ses_g2_assert_column_contract('t_compliance_operation_ledger', 'version', 'int', NULL, NULL, 'NO', '0');
CALL __ses_g2_assert_column_contract('t_compliance_operation_ledger', 'deleted_flag', 'tinyint', NULL, NULL, 'NO', '0');
DROP PROCEDURE IF EXISTS __ses_g2_assert_column_contract;

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_g2_repair_fk$$
CREATE PROCEDURE __ses_g2_repair_fk(IN p_table VARCHAR(64), IN p_constraint VARCHAR(64), IN p_sql TEXT)
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = p_table
      AND CONSTRAINT_NAME = p_constraint AND CONSTRAINT_TYPE = 'FOREIGN KEY') THEN
    SET @g2_drop_fk_sql = CONCAT('ALTER TABLE ', p_table, ' DROP FOREIGN KEY ', p_constraint);
    PREPARE g2_drop_fk_stmt FROM @g2_drop_fk_sql;
    EXECUTE g2_drop_fk_stmt;
    DEALLOCATE PREPARE g2_drop_fk_stmt;
  END IF;
  SET @g2_add_fk_sql = p_sql;
  PREPARE g2_add_fk_stmt FROM @g2_add_fk_sql;
  EXECUTE g2_add_fk_stmt;
  DEALLOCATE PREPARE g2_add_fk_stmt;
END$$
DELIMITER ;

CALL __ses_g2_repair_fk('m_compliance_mapping_source', 'fk_g2_source_mapping',
  'ALTER TABLE m_compliance_mapping_source ADD CONSTRAINT fk_g2_source_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('m_compliance_mapping_review_requirement_group', 'fk_g2_review_group_mapping',
  'ALTER TABLE m_compliance_mapping_review_requirement_group ADD CONSTRAINT fk_g2_review_group_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('m_compliance_mapping_review_requirement_type', 'fk_g2_review_type_group',
  'ALTER TABLE m_compliance_mapping_review_requirement_type ADD CONSTRAINT fk_g2_review_type_group FOREIGN KEY (tenant_id, requirement_group_id) REFERENCES m_compliance_mapping_review_requirement_group(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('m_compliance_mapping_review_requirement_type', 'fk_g2_review_type_reviewer',
  'ALTER TABLE m_compliance_mapping_review_requirement_type ADD CONSTRAINT fk_g2_review_type_reviewer FOREIGN KEY (tenant_id, reviewer_type_id) REFERENCES m_compliance_external_reviewer_type(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_responsible_assignment', 'fk_g2_assignment_workplace',
  'ALTER TABLE t_compliance_responsible_assignment ADD CONSTRAINT fk_g2_assignment_workplace FOREIGN KEY (tenant_id, workplace_id) REFERENCES m_workplace(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_mapping_approval_event', 'fk_g2_approval_mapping',
  'ALTER TABLE t_compliance_mapping_approval_event ADD CONSTRAINT fk_g2_approval_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_mapping_approval_event', 'fk_g2_approval_assignment',
  'ALTER TABLE t_compliance_mapping_approval_event ADD CONSTRAINT fk_g2_approval_assignment FOREIGN KEY (tenant_id, assignment_id) REFERENCES t_compliance_responsible_assignment(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_mapping_approval_event', 'fk_g2_approval_workplace',
  'ALTER TABLE t_compliance_mapping_approval_event ADD CONSTRAINT fk_g2_approval_workplace FOREIGN KEY (tenant_id, workplace_id_snapshot) REFERENCES m_workplace(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_mapping_approval_event', 'fk_g2_approval_target',
  'ALTER TABLE t_compliance_mapping_approval_event ADD CONSTRAINT fk_g2_approval_target FOREIGN KEY (tenant_id, target_event_id) REFERENCES t_compliance_mapping_approval_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_mapping_approval_event', 'fk_g2_approval_supersedes',
  'ALTER TABLE t_compliance_mapping_approval_event ADD CONSTRAINT fk_g2_approval_supersedes FOREIGN KEY (tenant_id, supersedes_event_id) REFERENCES t_compliance_mapping_approval_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_mapping_approval_event', 'fk_g2_approval_evidence_document',
  'ALTER TABLE t_compliance_mapping_approval_event ADD CONSTRAINT fk_g2_approval_evidence_document FOREIGN KEY (tenant_id, evidence_document_id) REFERENCES t_document(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_mapping_approval_event', 'fk_g2_approval_evidence_version',
  'ALTER TABLE t_compliance_mapping_approval_event ADD CONSTRAINT fk_g2_approval_evidence_version FOREIGN KEY (tenant_id, evidence_document_version_id) REFERENCES t_document_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_external_review_event', 'fk_g2_external_mapping',
  'ALTER TABLE t_compliance_external_review_event ADD CONSTRAINT fk_g2_external_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_external_review_event', 'fk_g2_external_group',
  'ALTER TABLE t_compliance_external_review_event ADD CONSTRAINT fk_g2_external_group FOREIGN KEY (tenant_id, requirement_group_id) REFERENCES m_compliance_mapping_review_requirement_group(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_external_review_event', 'fk_g2_external_reviewer_type',
  'ALTER TABLE t_compliance_external_review_event ADD CONSTRAINT fk_g2_external_reviewer_type FOREIGN KEY (tenant_id, reviewer_type_id) REFERENCES m_compliance_external_reviewer_type(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_external_review_event', 'fk_g2_external_target',
  'ALTER TABLE t_compliance_external_review_event ADD CONSTRAINT fk_g2_external_target FOREIGN KEY (tenant_id, target_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_external_review_event', 'fk_g2_external_supersedes',
  'ALTER TABLE t_compliance_external_review_event ADD CONSTRAINT fk_g2_external_supersedes FOREIGN KEY (tenant_id, supersedes_event_id) REFERENCES t_compliance_external_review_event(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_external_review_event', 'fk_g2_external_evidence_document',
  'ALTER TABLE t_compliance_external_review_event ADD CONSTRAINT fk_g2_external_evidence_document FOREIGN KEY (tenant_id, evidence_document_id) REFERENCES t_document(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_external_review_event', 'fk_g2_external_evidence_version',
  'ALTER TABLE t_compliance_external_review_event ADD CONSTRAINT fk_g2_external_evidence_version FOREIGN KEY (tenant_id, evidence_document_version_id) REFERENCES t_document_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
CALL __ses_g2_repair_fk('t_compliance_mapping_status_event', 'fk_g2_status_mapping',
  'ALTER TABLE t_compliance_mapping_status_event ADD CONSTRAINT fk_g2_status_mapping FOREIGN KEY (tenant_id, mapping_id) REFERENCES m_compliance_mapping_version(tenant_id, id) ON UPDATE CASCADE ON DELETE RESTRICT');
DROP PROCEDURE IF EXISTS __ses_g2_repair_fk;

-- Existing delivery rows are legacy and remain NULL in new gate columns.
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'mapping_version_id') = 0,
  'ALTER TABLE t_document_delivery ADD COLUMN mapping_version_id BIGINT NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'mapping_version') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN mapping_version VARCHAR(50) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'mapping_hash') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN mapping_hash CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'review_policy_hash') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN review_policy_hash CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'gate_evaluated_at') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN gate_evaluated_at DATETIME(6) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'gate_snapshot_hash') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN gate_snapshot_hash CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'profile_snapshot_id') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN profile_snapshot_id BIGINT NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'profile_snapshot_hash') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN profile_snapshot_hash CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'worker_snapshot_id') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN worker_snapshot_id BIGINT NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'worker_snapshot_hash') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN worker_snapshot_hash CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'workplace_id') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN workplace_id BIGINT NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'render_input_hash') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN render_input_hash CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'recipient_display_snapshot_hash') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN recipient_display_snapshot_hash CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'company_config_snapshot_hash') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN company_config_snapshot_hash CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'field_mask_policy_hash') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN field_mask_policy_hash CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'render_engine_version') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN render_engine_version VARCHAR(100) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'rendition_group_id') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN rendition_group_id VARCHAR(36) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'full_document_version_id') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN full_document_version_id BIGINT NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'full_document_sha256') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN full_document_sha256 CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'mask_document_version_id') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN mask_document_version_id BIGINT NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'mask_document_sha256') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN mask_document_sha256 CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'limited_document_version_id') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN limited_document_version_id BIGINT NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'limited_document_sha256') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN limited_document_sha256 CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'delivery_business_key') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN delivery_business_key CHAR(64) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;
SET @g2_delivery_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND COLUMN_NAME = 'generation_state') = 0, 'ALTER TABLE t_document_delivery ADD COLUMN generation_state VARCHAR(20) NULL', 'SELECT 1');
PREPARE g2_delivery_stmt FROM @g2_delivery_sql; EXECUTE g2_delivery_stmt; DEALLOCATE PREPARE g2_delivery_stmt;

SET @g2_delivery_index_sql = IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND INDEX_NAME = 'uk_delivery_business_key') = 0,
  'ALTER TABLE t_document_delivery ADD UNIQUE KEY uk_delivery_business_key (tenant_id, delivery_business_key)', 'SELECT 1');
PREPARE g2_delivery_index_stmt FROM @g2_delivery_index_sql; EXECUTE g2_delivery_index_stmt; DEALLOCATE PREPARE g2_delivery_index_stmt;
SET @g2_delivery_index_sql = IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND INDEX_NAME = 'idx_delivery_mapping_version') = 0,
  'CREATE INDEX idx_delivery_mapping_version ON t_document_delivery (tenant_id, mapping_version_id)', 'SELECT 1');
PREPARE g2_delivery_index_stmt FROM @g2_delivery_index_sql; EXECUTE g2_delivery_index_stmt; DEALLOCATE PREPARE g2_delivery_index_stmt;
SET @g2_delivery_index_sql = IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND INDEX_NAME = 'idx_delivery_gate_evaluated') = 0,
  'CREATE INDEX idx_delivery_gate_evaluated ON t_document_delivery (tenant_id, gate_evaluated_at)', 'SELECT 1');
PREPARE g2_delivery_index_stmt FROM @g2_delivery_index_sql; EXECUTE g2_delivery_index_stmt; DEALLOCATE PREPARE g2_delivery_index_stmt;
SET @g2_delivery_index_sql = IF((SELECT COUNT(*) FROM information_schema.statistics
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND INDEX_NAME = 'idx_delivery_rendition_group') = 0,
  'CREATE INDEX idx_delivery_rendition_group ON t_document_delivery (tenant_id, rendition_group_id)', 'SELECT 1');
PREPARE g2_delivery_index_stmt FROM @g2_delivery_index_sql; EXECUTE g2_delivery_index_stmt; DEALLOCATE PREPARE g2_delivery_index_stmt;
CALL __ses_g2_assert_index('t_document_delivery', 'uk_delivery_business_key',
  'tenant_id,delivery_business_key', 0);
CALL __ses_g2_assert_index('t_document_delivery', 'idx_delivery_mapping_version',
  'tenant_id,mapping_version_id', 1);
CALL __ses_g2_assert_index('t_document_delivery', 'idx_delivery_gate_evaluated',
  'tenant_id,gate_evaluated_at', 1);
CALL __ses_g2_assert_index('t_document_delivery', 'idx_delivery_rendition_group',
  'tenant_id,rendition_group_id', 1);
DROP PROCEDURE IF EXISTS __ses_g2_assert_index;

-- 新規deliveryのIDは実在するimmutable source/versionへFKで解決する。
DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_g2_repair_delivery_fk$$
CREATE PROCEDURE __ses_g2_repair_delivery_fk(IN p_constraint VARCHAR(64), IN p_sql TEXT)
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.table_constraints
    WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery'
      AND CONSTRAINT_NAME = p_constraint AND CONSTRAINT_TYPE = 'FOREIGN KEY') THEN
    SET @g2_delivery_drop_fk_sql = CONCAT('ALTER TABLE t_document_delivery DROP FOREIGN KEY ', p_constraint);
    PREPARE g2_delivery_drop_fk_stmt FROM @g2_delivery_drop_fk_sql;
    EXECUTE g2_delivery_drop_fk_stmt;
    DEALLOCATE PREPARE g2_delivery_drop_fk_stmt;
  END IF;
  SET @g2_delivery_add_fk_sql = p_sql;
  PREPARE g2_delivery_add_fk_stmt FROM @g2_delivery_add_fk_sql;
  EXECUTE g2_delivery_add_fk_stmt;
  DEALLOCATE PREPARE g2_delivery_add_fk_stmt;
END$$
DELIMITER ;
CALL __ses_g2_repair_delivery_fk('fk_delivery_g2_mapping_version',
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_mapping_version FOREIGN KEY (tenant_id, mapping_version_id) REFERENCES m_compliance_mapping_version(tenant_id, id)');
CALL __ses_g2_repair_delivery_fk('fk_delivery_g2_profile_snapshot',
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_profile_snapshot FOREIGN KEY (tenant_id, profile_snapshot_id) REFERENCES t_contract_compliance_snapshot(tenant_id, id)');
CALL __ses_g2_repair_delivery_fk('fk_delivery_g2_worker_snapshot',
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_worker_snapshot FOREIGN KEY (tenant_id, worker_snapshot_id) REFERENCES t_contract_compliance_worker_snapshot(tenant_id, id)');
CALL __ses_g2_repair_delivery_fk('fk_delivery_g2_workplace',
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_workplace FOREIGN KEY (tenant_id, workplace_id) REFERENCES m_workplace(tenant_id, id)');
CALL __ses_g2_repair_delivery_fk('fk_delivery_g2_full_document_version',
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_full_document_version FOREIGN KEY (tenant_id, full_document_version_id) REFERENCES t_document_version(tenant_id, id)');
CALL __ses_g2_repair_delivery_fk('fk_delivery_g2_mask_document_version',
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_mask_document_version FOREIGN KEY (tenant_id, mask_document_version_id) REFERENCES t_document_version(tenant_id, id)');
CALL __ses_g2_repair_delivery_fk('fk_delivery_g2_limited_document_version',
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_limited_document_version FOREIGN KEY (tenant_id, limited_document_version_id) REFERENCES t_document_version(tenant_id, id)');
DROP PROCEDURE IF EXISTS __ses_g2_repair_delivery_fk;

SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_mapping_version') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_mapping_version FOREIGN KEY (tenant_id, mapping_version_id) REFERENCES m_compliance_mapping_version(tenant_id, id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_profile_snapshot') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_profile_snapshot FOREIGN KEY (tenant_id, profile_snapshot_id) REFERENCES t_contract_compliance_snapshot(tenant_id, id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_worker_snapshot') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_worker_snapshot FOREIGN KEY (tenant_id, worker_snapshot_id) REFERENCES t_contract_compliance_worker_snapshot(tenant_id, id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_workplace') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_workplace FOREIGN KEY (tenant_id, workplace_id) REFERENCES m_workplace(tenant_id, id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_full_document_version') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_full_document_version FOREIGN KEY (tenant_id, full_document_version_id) REFERENCES t_document_version(tenant_id, id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_mask_document_version') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_mask_document_version FOREIGN KEY (tenant_id, mask_document_version_id) REFERENCES t_document_version(tenant_id, id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_limited_document_version') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_limited_document_version FOREIGN KEY (tenant_id, limited_document_version_id) REFERENCES t_document_version(tenant_id, id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;

DELIMITER $$
DROP TRIGGER IF EXISTS trg_g2_mapping_slot_check$$
CREATE TRIGGER trg_g2_mapping_slot_check BEFORE INSERT ON m_compliance_mapping_version
FOR EACH ROW
BEGIN
  IF NEW.status = 'ACTIVE' AND (NEW.active_slot IS NULL OR NEW.active_slot <> 1 OR NEW.future_slot IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping ACTIVE slot is invalid';
  END IF;
  IF NEW.status <> 'ACTIVE' AND NEW.active_slot IS NOT NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping non-ACTIVE row cannot have active_slot';
  END IF;
  IF NEW.future_slot IS NOT NULL AND NEW.status NOT IN ('DRAFT', 'PROVISIONAL_REVIEWED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping future_slot requires a candidate status';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_mapping_slot_check_update$$
CREATE TRIGGER trg_g2_mapping_slot_check_update BEFORE UPDATE ON m_compliance_mapping_version
FOR EACH ROW
BEGIN
  IF OLD.status <> 'DRAFT' AND (NEW.mapping_code <> OLD.mapping_code OR NEW.mapping_version <> OLD.mapping_version
    OR NEW.mapping_hash <> OLD.mapping_hash OR NEW.review_policy_hash <> OLD.review_policy_hash
    OR NEW.effective_from <> OLD.effective_from OR NOT (NEW.effective_to <=> OLD.effective_to)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 frozen mapping payload is immutable';
  END IF;
  IF NEW.status = 'ACTIVE' AND (NEW.active_slot IS NULL OR NEW.active_slot <> 1 OR NEW.future_slot IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping ACTIVE slot is invalid';
  END IF;
  IF NEW.status <> 'ACTIVE' AND NEW.active_slot IS NOT NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping non-ACTIVE row cannot have active_slot';
  END IF;
  IF NEW.future_slot IS NOT NULL AND NEW.status NOT IN ('DRAFT', 'PROVISIONAL_REVIEWED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping future_slot requires a candidate status';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_assignment_slot_check$$
CREATE TRIGGER trg_g2_assignment_slot_check BEFORE INSERT ON t_compliance_responsible_assignment
FOR EACH ROW
BEGIN
  IF NEW.effective_to IS NULL AND (NEW.active_slot IS NULL OR NEW.active_slot <> 1 OR NEW.ended_by IS NOT NULL OR NEW.end_reason IS NOT NULL)
    OR (NEW.effective_to IS NOT NULL AND (NEW.active_slot IS NOT NULL OR NEW.ended_by IS NULL OR NEW.end_reason IS NULL)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 assignment slot/period is invalid';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_assignment_slot_check_update$$
CREATE TRIGGER trg_g2_assignment_slot_check_update BEFORE UPDATE ON t_compliance_responsible_assignment
FOR EACH ROW
BEGIN
  IF NEW.effective_to IS NULL AND (NEW.active_slot IS NULL OR NEW.active_slot <> 1 OR NEW.ended_by IS NOT NULL OR NEW.end_reason IS NOT NULL)
    OR (NEW.effective_to IS NOT NULL AND (NEW.active_slot IS NOT NULL OR NEW.ended_by IS NULL OR NEW.end_reason IS NULL)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 assignment slot/period is invalid';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_mapping_source_freeze_insert$$
CREATE TRIGGER trg_g2_mapping_source_freeze_insert BEFORE INSERT ON m_compliance_mapping_source
FOR EACH ROW
BEGIN
  IF NOT EXISTS (SELECT 1 FROM m_compliance_mapping_version WHERE id = NEW.mapping_id AND tenant_id = NEW.tenant_id AND status = 'DRAFT') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping source parent is frozen';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_mapping_source_freeze_update$$
CREATE TRIGGER trg_g2_mapping_source_freeze_update BEFORE UPDATE ON m_compliance_mapping_source
FOR EACH ROW
BEGIN
  IF OLD.mapping_id <> NEW.mapping_id OR NOT EXISTS (SELECT 1 FROM m_compliance_mapping_version WHERE id = OLD.mapping_id AND tenant_id = OLD.tenant_id AND status = 'DRAFT')
    OR NOT EXISTS (SELECT 1 FROM m_compliance_mapping_version WHERE id = NEW.mapping_id AND tenant_id = NEW.tenant_id AND status = 'DRAFT') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping source parent is frozen';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_mapping_source_freeze_delete$$
CREATE TRIGGER trg_g2_mapping_source_freeze_delete BEFORE DELETE ON m_compliance_mapping_source
FOR EACH ROW
BEGIN
  IF NOT EXISTS (SELECT 1 FROM m_compliance_mapping_version WHERE id = OLD.mapping_id AND tenant_id = OLD.tenant_id AND status = 'DRAFT') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping source parent is frozen';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_approval_no_update$$
CREATE TRIGGER trg_g2_approval_no_update BEFORE UPDATE ON t_compliance_mapping_approval_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 approval event is append-only'$$
DROP TRIGGER IF EXISTS trg_g2_approval_no_delete$$
CREATE TRIGGER trg_g2_approval_no_delete BEFORE DELETE ON t_compliance_mapping_approval_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 approval event is append-only'$$
DROP TRIGGER IF EXISTS trg_g2_external_review_no_update$$
CREATE TRIGGER trg_g2_external_review_no_update BEFORE UPDATE ON t_compliance_external_review_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 external review event is append-only'$$
DROP TRIGGER IF EXISTS trg_g2_external_review_no_delete$$
CREATE TRIGGER trg_g2_external_review_no_delete BEFORE DELETE ON t_compliance_external_review_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 external review event is append-only'$$
DROP TRIGGER IF EXISTS trg_g2_status_no_update$$
CREATE TRIGGER trg_g2_status_no_update BEFORE UPDATE ON t_compliance_mapping_status_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 status event is append-only'$$
DROP TRIGGER IF EXISTS trg_g2_status_no_delete$$
CREATE TRIGGER trg_g2_status_no_delete BEFORE DELETE ON t_compliance_mapping_status_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 status event is append-only'$$
DROP TRIGGER IF EXISTS trg_g2_operation_claim_insert$$
CREATE TRIGGER trg_g2_operation_claim_insert BEFORE INSERT ON t_compliance_operation_ledger
FOR EACH ROW
BEGIN
  IF NEW.state <> 'PROCESSING' OR NEW.retryable_flag <> 0 OR NEW.attempt_count <> 1 OR NEW.version <> 0
     OR NEW.finished_at IS NOT NULL OR NEW.failure_code IS NOT NULL
     OR NEW.result_reference_type IS NOT NULL OR NEW.result_reference_id IS NOT NULL
     OR NEW.result_reference_version IS NOT NULL OR NEW.result_summary_canonical IS NOT NULL
     OR NEW.result_http_status IS NOT NULL OR NEW.result_hash IS NOT NULL OR NEW.deleted_flag <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 operation claim insert is invalid';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_operation_no_update$$
CREATE TRIGGER trg_g2_operation_no_update BEFORE UPDATE ON t_compliance_operation_ledger
FOR EACH ROW
BEGIN
  IF NOT (NEW.tenant_id <=> OLD.tenant_id)
    OR NOT (NEW.operation_id <=> OLD.operation_id)
     OR NOT (NEW.operation_type <=> OLD.operation_type)
     OR NOT (NEW.idempotency_key <=> OLD.idempotency_key)
     OR NOT (NEW.request_hash <=> OLD.request_hash)
     OR NOT (NEW.correlation_id <=> OLD.correlation_id)
     OR NEW.deleted_flag <> 0
     OR NEW.version <> OLD.version + 1
     OR NEW.state NOT IN ('PROCESSING', 'SUCCEEDED', 'FAILED')
     OR NOT (NEW.started_at <=> OLD.started_at)
     OR NOT (NEW.expires_at <=> OLD.expires_at)
     OR NOT (NEW.created_at <=> OLD.created_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 operation ledger transition is invalid';
  END IF;
  IF OLD.state = 'PROCESSING' AND NEW.state = 'PROCESSING' THEN
    IF NEW.attempt_count <> OLD.attempt_count + 1
       OR NOT (NEW.retryable_flag <=> OLD.retryable_flag)
       OR NOT (NEW.finished_at <=> OLD.finished_at)
       OR NOT (NEW.result_reference_type <=> OLD.result_reference_type)
       OR NOT (NEW.result_reference_id <=> OLD.result_reference_id)
       OR NOT (NEW.result_reference_version <=> OLD.result_reference_version)
       OR NOT (NEW.result_summary_canonical <=> OLD.result_summary_canonical)
       OR NOT (NEW.result_http_status <=> OLD.result_http_status)
       OR NOT (NEW.result_hash <=> OLD.result_hash)
       OR NOT (NEW.failure_code <=> OLD.failure_code)
       OR NEW.result_reference_type IS NOT NULL OR NEW.result_reference_id IS NOT NULL
       OR NEW.result_reference_version IS NOT NULL OR NEW.result_summary_canonical IS NOT NULL
       OR NEW.result_http_status IS NOT NULL OR NEW.result_hash IS NOT NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 operation lease transition is invalid';
    END IF;
  ELSEIF OLD.state = 'PROCESSING' AND NEW.state = 'SUCCEEDED' THEN
    IF NEW.retryable_flag <> 0 OR NEW.finished_at IS NULL
       OR NEW.result_summary_canonical IS NULL OR NEW.result_http_status IS NULL OR NEW.result_hash IS NULL
       OR NEW.failure_code IS NOT NULL OR NEW.attempt_count <> OLD.attempt_count THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 operation success transition is invalid';
    END IF;
  ELSEIF OLD.state = 'PROCESSING' AND NEW.state = 'FAILED' THEN
    IF NEW.finished_at IS NULL OR NEW.failure_code IS NULL
       OR NEW.attempt_count <> OLD.attempt_count
       OR NEW.result_reference_type IS NOT NULL OR NEW.result_reference_id IS NOT NULL
       OR NEW.result_reference_version IS NOT NULL OR NEW.result_summary_canonical IS NOT NULL
       OR NEW.result_http_status IS NOT NULL OR NEW.result_hash IS NOT NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 operation failure transition is invalid';
    END IF;
  ELSEIF OLD.state = 'FAILED' AND NEW.state = 'PROCESSING' THEN
    IF OLD.retryable_flag <> 1 OR NEW.retryable_flag <> 1
       OR NEW.attempt_count <> OLD.attempt_count + 1 OR NEW.lease_until IS NULL
       OR NEW.finished_at IS NOT NULL OR NEW.failure_code IS NOT NULL
       OR NEW.result_reference_type IS NOT NULL OR NEW.result_reference_id IS NOT NULL
       OR NEW.result_reference_version IS NOT NULL OR NEW.result_summary_canonical IS NOT NULL
       OR NEW.result_http_status IS NOT NULL OR NEW.result_hash IS NOT NULL THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 operation retry transition is invalid';
    END IF;
  ELSE
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 operation state transition is invalid';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_g2_operation_no_delete$$
CREATE TRIGGER trg_g2_operation_no_delete BEFORE DELETE ON t_compliance_operation_ledger
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 operation ledger is permanent'$$
DELIMITER ;
