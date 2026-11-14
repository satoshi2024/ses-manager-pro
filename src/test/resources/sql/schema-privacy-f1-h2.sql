-- V190相当のH2 schema（NF07 F1: catalog / policy / hold projection / DSAR request）。
-- 共有H2（DB_CLOSE_DELAY=-1）をcontextごとに再実行するため、CREATE TABLE IF NOT EXISTS と
-- ADD CONSTRAINT IF NOT EXISTS で冪等にする。engineer-schema-h2.sql にも同じ定義を置く（両方の仕組みで同期）。
-- MySQLとの差: 生成列は STORED を書かない（H2は常に計算列）、COMMENT・ENGINE・collationを書かない、
-- LONGBLOB は BLOB。生成列UNIQUE・SoD CHECK・承認束縛CHECK・複合FKの実MySQLでの挙動は
-- FlywayNf07PrivacyF1MigrationTest が確認する。

CREATE TABLE IF NOT EXISTS m_pii_data_element (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id           VARCHAR(100) NOT NULL DEFAULT 'default',
  data_element_id     VARCHAR(128) NOT NULL,
  storage_kind        VARCHAR(20)  NOT NULL,
  storage_ref         VARCHAR(255) NOT NULL,
  data_class          VARCHAR(50)  NOT NULL DEFAULT 'UNKNOWN',
  owner_state         VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
  owner_ref           VARCHAR(100),
  provider_id         VARCHAR(100),
  subject_attribution VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
  third_party_flag    TINYINT      NOT NULL DEFAULT 1,
  inventory_status    VARCHAR(30)  NOT NULL DEFAULT 'UNKNOWN',
  inventory_ref       VARCHAR(255),
  created_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at          DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  deleted_flag        TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_pii_data_element UNIQUE (tenant_id, data_element_id),
  CONSTRAINT chk_pii_data_element_attribution
    CHECK (subject_attribution IN ('ATTRIBUTABLE', 'NOT_ATTRIBUTABLE', 'NO_PERSONAL_DATA', 'UNKNOWN')),
  CONSTRAINT chk_pii_data_element_storage_kind
    CHECK (storage_kind IN ('DB_COLUMN', 'FILE_OBJECT', 'AI_PAYLOAD', 'LOG_OR_BACKUP')),
  CONSTRAINT chk_pii_data_element_inventory
    CHECK (inventory_status IN ('CONFIRMED_TECHNICAL', 'PROVISIONAL', 'UNKNOWN'))
);

CREATE TABLE IF NOT EXISTS m_retention_policy (
  id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id               VARCHAR(100) NOT NULL,
  policy_id               VARCHAR(128) NOT NULL,
  policy_revision         VARCHAR(64)  NOT NULL,
  module_code             VARCHAR(50)  NOT NULL,
  legal_entity_id         BIGINT,
  data_element_id         VARCHAR(128) NOT NULL,
  target_action           VARCHAR(50)  NOT NULL,
  target_provider         VARCHAR(100) NOT NULL,
  status                  VARCHAR(20)  NOT NULL DEFAULT 'CANDIDATE',
  current_key             VARCHAR(512) GENERATED ALWAYS AS (
                            CASE WHEN status = 'CURRENT'
                                 THEN CONCAT_WS('|', tenant_id, module_code,
                                                CAST(COALESCE(legal_entity_id, 0) AS VARCHAR(20)),
                                                data_element_id, target_action, target_provider)
                                 ELSE NULL END),
  envelope_schema_version INT          NOT NULL,
  envelope_canonical      BLOB         NOT NULL,
  envelope_signature      VARBINARY(1024) NOT NULL,
  envelope_digest         CHAR(64)     NOT NULL,
  authority_id            VARCHAR(100) NOT NULL,
  key_id                  VARCHAR(100) NOT NULL,
  registered_by           BIGINT       NOT NULL,
  registered_at           DATETIME(6)  NOT NULL,
  activated_by            BIGINT,
  activated_at            DATETIME(6),
  row_version             BIGINT       NOT NULL DEFAULT 0,
  CONSTRAINT uk_retention_policy_revision UNIQUE (tenant_id, policy_id, policy_revision),
  CONSTRAINT uk_retention_policy_current UNIQUE (current_key),
  CONSTRAINT chk_retention_policy_status CHECK (status IN ('CANDIDATE', 'CURRENT', 'SUPERSEDED', 'REVOKED')),
  CONSTRAINT chk_retention_policy_activation
    CHECK (status <> 'CURRENT' OR (activated_by IS NOT NULL AND activated_at IS NOT NULL AND activated_by <> registered_by)),
  CONSTRAINT chk_retention_policy_digest CHECK (CHAR_LENGTH(envelope_digest) = 64)
);

CREATE INDEX IF NOT EXISTS idx_retention_policy_lookup
  ON m_retention_policy (tenant_id, module_code, data_element_id, target_action, target_provider, status);

CREATE TABLE IF NOT EXISTS t_legal_hold (
  id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id           VARCHAR(100) NOT NULL,
  hold_id             VARCHAR(64)  NOT NULL,
  target_type         VARCHAR(20)  NOT NULL,
  subject_ref         VARCHAR(128),
  data_element_id     VARCHAR(128),
  hold_kind           VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
  status              VARCHAR(20)  NOT NULL,
  reason_code         VARCHAR(50)  NOT NULL,
  reason_hash         CHAR(64)     NOT NULL,
  ticket_ref          VARCHAR(100) NOT NULL,
  requested_by        BIGINT       NOT NULL,
  requested_at        DATETIME(6)  NOT NULL,
  journal_revision    BIGINT       NOT NULL,
  journal_event_hash  CHAR(64)     NOT NULL,
  released_at         DATETIME(6),
  CONSTRAINT uk_legal_hold UNIQUE (tenant_id, hold_id),
  CONSTRAINT chk_legal_hold_target
    CHECK ((target_type = 'SUBJECT' AND subject_ref IS NOT NULL AND data_element_id IS NULL)
        OR (target_type = 'DATA_ELEMENT' AND data_element_id IS NOT NULL AND subject_ref IS NULL)),
  CONSTRAINT chk_legal_hold_status CHECK (status IN ('ACTIVE', 'RELEASED')),
  CONSTRAINT chk_legal_hold_release CHECK ((status = 'RELEASED') = (released_at IS NOT NULL))
);

CREATE INDEX IF NOT EXISTS idx_legal_hold_subject ON t_legal_hold (tenant_id, subject_ref, status);
CREATE INDEX IF NOT EXISTS idx_legal_hold_element ON t_legal_hold (tenant_id, data_element_id, status);

CREATE TABLE IF NOT EXISTS t_privacy_projection_state (
  tenant_id            VARCHAR(100) NOT NULL,
  journal_id           VARCHAR(64)  NOT NULL,
  engaged_at           DATETIME(6)  NOT NULL,
  last_applied_seq     BIGINT       NOT NULL DEFAULT 0,
  last_applied_hash    CHAR(64),
  pending_command_id   VARCHAR(64),
  pending_since        DATETIME(6),
  verified_at          DATETIME(6),
  row_version          BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (tenant_id, journal_id),
  CONSTRAINT chk_projection_pending CHECK ((pending_command_id IS NULL) = (pending_since IS NULL)),
  CONSTRAINT chk_projection_seq CHECK (last_applied_seq >= 0)
);

CREATE TABLE IF NOT EXISTS t_privacy_request (
  id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id                VARCHAR(100) NOT NULL,
  request_no               VARCHAR(40)  NOT NULL,
  request_type             VARCHAR(20)  NOT NULL,
  status                   VARCHAR(30)  NOT NULL,
  channel                  VARCHAR(20)  NOT NULL,
  received_at              DATETIME(6)  NOT NULL,
  due_date                 DATE,
  intake_ticket_ref        VARCHAR(128) NOT NULL,
  identity_state           VARCHAR(20)  NOT NULL DEFAULT 'UNVERIFIED',
  identity_evidence_ref    VARCHAR(128),
  identity_rule_ref        VARCHAR(128),
  identity_verified_by     BIGINT,
  identity_verified_at     DATETIME(6),
  case_owner_id            BIGINT,
  scope_snapshot_hash      CHAR(64),
  prepared_by              BIGINT,
  approved_by              BIGINT,
  approved_at              DATETIME(6),
  approved_package_id      BIGINT,
  approved_content_sha256  CHAR(64),
  delivered_by             BIGINT,
  delivered_at             DATETIME(6),
  delivery_method          VARCHAR(20),
  delivery_evidence_ref    VARCHAR(128),
  reopen_of_request_id     BIGINT,
  row_version              BIGINT       NOT NULL DEFAULT 0,
  created_by               BIGINT       NOT NULL,
  created_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at               DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  deleted_flag             TINYINT      NOT NULL DEFAULT 0,
  CONSTRAINT uk_privacy_request_no UNIQUE (tenant_id, request_no),
  CONSTRAINT uk_privacy_request_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT chk_privacy_request_sod
    CHECK (approved_by IS NULL OR (prepared_by IS NOT NULL AND approved_by <> prepared_by)),
  CONSTRAINT chk_privacy_request_approval_binding
    CHECK ((approved_by IS NULL AND approved_at IS NULL AND approved_package_id IS NULL AND approved_content_sha256 IS NULL)
        OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND approved_package_id IS NOT NULL
            AND approved_content_sha256 IS NOT NULL)),
  CONSTRAINT chk_privacy_request_type
    CHECK (request_type IN ('ACCESS', 'CORRECTION', 'DELETION', 'RESTRICTION', 'OBJECTION')),
  CONSTRAINT chk_privacy_request_identity CHECK (identity_state IN ('UNVERIFIED', 'AMBIGUOUS', 'VERIFIED'))
);

ALTER TABLE t_privacy_request ADD CONSTRAINT IF NOT EXISTS fk_privacy_request_reopen
  FOREIGN KEY (tenant_id, reopen_of_request_id) REFERENCES t_privacy_request (tenant_id, id);

CREATE INDEX IF NOT EXISTS idx_privacy_request_status ON t_privacy_request (tenant_id, status, due_date);

CREATE TABLE IF NOT EXISTS t_privacy_request_subject_link (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         VARCHAR(100) NOT NULL,
  request_id        BIGINT       NOT NULL,
  subject_ref       VARCHAR(128) NOT NULL,
  link_state        VARCHAR(20)  NOT NULL,
  resolved_by       BIGINT,
  resolved_at       DATETIME(6),
  resolution_code   VARCHAR(50),
  CONSTRAINT uk_privacy_subject_link UNIQUE (tenant_id, request_id, subject_ref),
  CONSTRAINT chk_privacy_subject_link_state CHECK (link_state IN ('CANDIDATE', 'SELECTED', 'REJECTED')),
  CONSTRAINT fk_privacy_subject_link_request FOREIGN KEY (tenant_id, request_id)
    REFERENCES t_privacy_request (tenant_id, id)
);

CREATE TABLE IF NOT EXISTS t_privacy_action (
  id                BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id         VARCHAR(100) NOT NULL,
  request_id        BIGINT       NOT NULL,
  action_type       VARCHAR(20)  NOT NULL,
  provider_id       VARCHAR(100),
  data_element_id   VARCHAR(128),
  status            VARCHAR(20)  NOT NULL,
  idempotency_key   VARCHAR(128) NOT NULL,
  claim_token       VARCHAR(64),
  lease_until       DATETIME(6),
  result_count      INT,
  result_hash       CHAR(64),
  blocked_reason    VARCHAR(50),
  evidence_ref      VARCHAR(128),
  row_version       BIGINT       NOT NULL DEFAULT 0,
  created_at        DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT uk_privacy_action_idem UNIQUE (tenant_id, idempotency_key),
  CONSTRAINT chk_privacy_action_type CHECK (action_type IN ('SEARCH', 'EXPORT_DRAFT', 'MANUAL_ACTION')),
  CONSTRAINT chk_privacy_action_status
    CHECK (status IN ('PENDING', 'CLAIMED', 'SUCCEEDED', 'BLOCKED', 'UNKNOWN', 'FAILED')),
  CONSTRAINT fk_privacy_action_request FOREIGN KEY (tenant_id, request_id)
    REFERENCES t_privacy_request (tenant_id, id)
);

CREATE INDEX IF NOT EXISTS idx_privacy_action_request ON t_privacy_action (tenant_id, request_id, status);

CREATE TABLE IF NOT EXISTS t_privacy_export_package (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  tenant_id       VARCHAR(100) NOT NULL,
  request_id      BIGINT       NOT NULL,
  stored_name     VARCHAR(255) NOT NULL,
  content_sha256  CHAR(64)     NOT NULL,
  size_bytes      BIGINT       NOT NULL,
  status          VARCHAR(20)  NOT NULL,
  purge_after     DATETIME(6)  NOT NULL,
  created_by      BIGINT       NOT NULL,
  created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  approved_by     BIGINT,
  approved_at     DATETIME(6),
  purged_at       DATETIME(6),
  CONSTRAINT uk_privacy_export_package UNIQUE (tenant_id, stored_name),
  CONSTRAINT uk_privacy_export_package_tenant_id UNIQUE (tenant_id, id),
  CONSTRAINT uk_privacy_export_package_request UNIQUE (tenant_id, id, request_id),
  CONSTRAINT chk_privacy_export_package_sod CHECK (approved_by IS NULL OR approved_by <> created_by),
  CONSTRAINT chk_privacy_export_package_approval CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
  CONSTRAINT chk_privacy_export_package_status CHECK (status IN ('DRAFT', 'APPROVED', 'DELIVERED', 'PURGED')),
  CONSTRAINT chk_privacy_export_package_size CHECK (size_bytes >= 0),
  CONSTRAINT fk_privacy_export_request FOREIGN KEY (tenant_id, request_id)
    REFERENCES t_privacy_request (tenant_id, id)
);

CREATE INDEX IF NOT EXISTS idx_privacy_export_package_stored_name ON t_privacy_export_package (stored_name);

ALTER TABLE t_privacy_request ADD CONSTRAINT IF NOT EXISTS fk_privacy_request_approved_package
  FOREIGN KEY (tenant_id, approved_package_id, id) REFERENCES t_privacy_export_package (tenant_id, id, request_id);
