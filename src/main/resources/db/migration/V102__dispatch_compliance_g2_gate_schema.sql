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
  CONSTRAINT chk_g2_mapping_active_slot CHECK (active_slot IS NULL OR active_slot = 1),
  CONSTRAINT chk_g2_mapping_future_slot CHECK (future_slot IS NULL OR future_slot = 1),
  CONSTRAINT fk_g2_mapping_activated_by FOREIGN KEY (activated_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 compliance mapping version';
CREATE INDEX idx_g2_mapping_effective ON m_compliance_mapping_version
  (tenant_id, mapping_code, status, effective_from, effective_to);

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
  CONSTRAINT fk_g2_source_mapping FOREIGN KEY (mapping_id) REFERENCES m_compliance_mapping_version(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_source_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_g2_source_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 mapping source';
CREATE INDEX idx_g2_mapping_source_lookup ON m_compliance_mapping_source
  (tenant_id, source_code, confirmed_on);

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
CREATE INDEX idx_g2_reviewer_type_enabled ON m_compliance_external_reviewer_type
  (tenant_id, enabled, sort_order);

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
  CONSTRAINT fk_g2_review_group_mapping FOREIGN KEY (mapping_id) REFERENCES m_compliance_mapping_version(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_review_group_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_g2_review_group_updated_by FOREIGN KEY (updated_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 external review requirement group';
CREATE INDEX idx_g2_review_group_mapping ON m_compliance_mapping_review_requirement_group
  (tenant_id, mapping_id, sort_order);

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
  CONSTRAINT fk_g2_review_type_group FOREIGN KEY (requirement_group_id)
    REFERENCES m_compliance_mapping_review_requirement_group(id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_review_type_reviewer FOREIGN KEY (reviewer_type_id)
    REFERENCES m_compliance_external_reviewer_type(id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_review_type_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 frozen reviewer type requirement';
CREATE INDEX idx_g2_review_type_reviewer ON m_compliance_mapping_review_requirement_type
  (tenant_id, reviewer_type_id);

CREATE TABLE IF NOT EXISTS t_compliance_responsible_assignment (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id     VARCHAR(100) NOT NULL DEFAULT 'default',
  workplace_id  BIGINT NOT NULL,
  user_id       BIGINT NOT NULL,
  role_code     VARCHAR(40) NOT NULL DEFAULT 'COMPLIANCE_RESPONSIBLE',
  effective_from DATE NOT NULL,
  effective_to  DATE,
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
    (effective_to IS NULL AND active_slot IS NULL AND ended_by IS NULL AND end_reason IS NULL)
    OR (effective_to IS NOT NULL AND ended_by IS NOT NULL AND end_reason IS NOT NULL)
  ),
  CONSTRAINT fk_g2_assignment_workplace FOREIGN KEY (workplace_id) REFERENCES m_workplace(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_assignment_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_assignment_assigned_by FOREIGN KEY (assigned_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_assignment_ended_by FOREIGN KEY (ended_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 workplace compliance responsible assignment';
CREATE INDEX idx_g2_assignment_period ON t_compliance_responsible_assignment
  (tenant_id, workplace_id, effective_from, effective_to);
CREATE INDEX idx_g2_assignment_user_period ON t_compliance_responsible_assignment
  (tenant_id, user_id, effective_from, effective_to);

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
  CONSTRAINT fk_g2_approval_mapping FOREIGN KEY (mapping_id) REFERENCES m_compliance_mapping_version(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_assignment FOREIGN KEY (assignment_id) REFERENCES t_compliance_responsible_assignment(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_workplace FOREIGN KEY (workplace_id_snapshot) REFERENCES m_workplace(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_evidence_document FOREIGN KEY (evidence_document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_approval_evidence_version FOREIGN KEY (evidence_document_version_id) REFERENCES t_document_version(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 internal approval append-only event';
CREATE INDEX idx_g2_approval_scope ON t_compliance_mapping_approval_event
  (tenant_id, mapping_id, workplace_id_snapshot, assignment_id, occurred_at, id);
CREATE INDEX idx_g2_approval_chain ON t_compliance_mapping_approval_event
  (tenant_id, event_chain_id, occurred_at, id);

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
  CONSTRAINT fk_g2_external_mapping FOREIGN KEY (mapping_id) REFERENCES m_compliance_mapping_version(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_group FOREIGN KEY (requirement_group_id)
    REFERENCES m_compliance_mapping_review_requirement_group(id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_reviewer_type FOREIGN KEY (reviewer_type_id)
    REFERENCES m_compliance_external_reviewer_type(id) ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_evidence_document FOREIGN KEY (evidence_document_id) REFERENCES t_document(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_evidence_version FOREIGN KEY (evidence_document_version_id) REFERENCES t_document_version(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_external_recorded_by FOREIGN KEY (recorded_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 external review append-only event';
CREATE INDEX idx_g2_external_review_scope ON t_compliance_external_review_event
  (tenant_id, mapping_id, requirement_group_id, reviewer_identity_hash, recorded_at, id);
CREATE INDEX idx_g2_external_review_chain ON t_compliance_external_review_event
  (tenant_id, review_chain_id, recorded_at, id);
CREATE INDEX idx_g2_external_review_valid_until ON t_compliance_external_review_event
  (tenant_id, valid_until);

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
  CONSTRAINT fk_g2_status_mapping FOREIGN KEY (mapping_id) REFERENCES m_compliance_mapping_version(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_g2_status_actor FOREIGN KEY (actor_id) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 mapping status append-only event';
CREATE INDEX idx_g2_status_mapping ON t_compliance_mapping_status_event
  (tenant_id, mapping_id, occurred_at, id);
CREATE INDEX idx_g2_status_correlation ON t_compliance_mapping_status_event
  (tenant_id, correlation_id);

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
    (state = 'SUCCEEDED' AND result_summary_canonical IS NOT NULL AND result_http_status IS NOT NULL)
    OR (state <> 'SUCCEEDED')
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='G2 state-changing operation idempotency ledger';
CREATE INDEX idx_g2_operation_lease ON t_compliance_operation_ledger
  (tenant_id, state, lease_until);
CREATE INDEX idx_g2_operation_result ON t_compliance_operation_ledger
  (tenant_id, result_reference_type, result_reference_id);

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

-- 新規deliveryのIDは実在するimmutable source/versionへFKで解決する。
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_mapping_version') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_mapping_version FOREIGN KEY (mapping_version_id) REFERENCES m_compliance_mapping_version(id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_profile_snapshot') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_profile_snapshot FOREIGN KEY (profile_snapshot_id) REFERENCES t_contract_compliance_snapshot(id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_worker_snapshot') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_worker_snapshot FOREIGN KEY (worker_snapshot_id) REFERENCES t_contract_compliance_worker_snapshot(id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_workplace') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_workplace FOREIGN KEY (workplace_id) REFERENCES m_workplace(id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_full_document_version') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_full_document_version FOREIGN KEY (full_document_version_id) REFERENCES t_document_version(id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_mask_document_version') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_mask_document_version FOREIGN KEY (mask_document_version_id) REFERENCES t_document_version(id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;
SET @g2_delivery_fk_sql = IF((SELECT COUNT(*) FROM information_schema.table_constraints
  WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = 't_document_delivery' AND CONSTRAINT_NAME = 'fk_delivery_g2_limited_document_version') = 0,
  'ALTER TABLE t_document_delivery ADD CONSTRAINT fk_delivery_g2_limited_document_version FOREIGN KEY (limited_document_version_id) REFERENCES t_document_version(id)', 'SELECT 1');
PREPARE g2_delivery_fk_stmt FROM @g2_delivery_fk_sql; EXECUTE g2_delivery_fk_stmt; DEALLOCATE PREPARE g2_delivery_fk_stmt;

DELIMITER $$
DROP TRIGGER IF EXISTS trg_g2_mapping_slot_check$$
CREATE TRIGGER trg_g2_mapping_slot_check BEFORE INSERT ON m_compliance_mapping_version
FOR EACH ROW
BEGIN
  IF NEW.status = 'ACTIVE' AND (NEW.active_slot <> 1 OR NEW.future_slot IS NOT NULL) THEN
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
  IF NEW.status = 'ACTIVE' AND (NEW.active_slot <> 1 OR NEW.future_slot IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping ACTIVE slot is invalid';
  END IF;
  IF NEW.status <> 'ACTIVE' AND NEW.active_slot IS NOT NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping non-ACTIVE row cannot have active_slot';
  END IF;
  IF NEW.future_slot IS NOT NULL AND NEW.status NOT IN ('DRAFT', 'PROVISIONAL_REVIEWED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'G2 mapping future_slot requires a candidate status';
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
DELIMITER ;
