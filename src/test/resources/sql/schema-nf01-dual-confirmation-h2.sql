-- V195相当のH2 schema（NF01 B0 二者確認coordinatorの7表 / W3-18）。
-- db/migration/V195 は ENGINE・COMMENT・列単位の文字集合（idempotency_key の ascii_bin）とtriggerを含むため、
-- H2のreplayには同じ表・列・主キー・一意key・CHECK・FK・indexを置く。
-- triggerは置かない（計画 §5.12）。不変6表の更新・削除は mapper層（LifecycleDual*Mapper）で拒否する。
-- 共有H2はcontextごとにschema-locationsを再実行するため、表・制約・indexとも IF NOT EXISTS で冪等にする。
-- FKは表の作成と分けて ALTER TABLE ... ADD CONSTRAINT IF NOT EXISTS で置く。V1のreplayが sys_user・m_organization_unit・
-- t_document_version を作り直す前に test-schema-import-precleanup-h2.sql がそれらへのFKを外し、本fileが付け直す
-- （schema-document-archive-h2.sql の DROP ... CASCADE で外れたFKも同じく付け直す）。
-- t_lifecycle_case.tenant_id（schema-service-desk-h2.sql）と t_lifecycle_task の世代列・V189相当の一意key
-- （schema-authorization-epoch-h2.sql）より後に置くこと。engineer-schema-h2.sql も同じ定義を持つ。
CREATE TABLE IF NOT EXISTS t_lifecycle_dual_submission (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id               VARCHAR(100)  NOT NULL,
    case_id                 BIGINT        NOT NULL,
    task_id                 BIGINT        NOT NULL,
    engineer_id             BIGINT        NOT NULL,
    task_generation         BIGINT        NOT NULL,
    revision                INT           NOT NULL,
    submitter_user_id       BIGINT        NOT NULL,
    submitter_role_snapshot VARCHAR(30)   NOT NULL,
    global_epoch            BIGINT        NOT NULL,
    submitter_actor_epoch   BIGINT        NOT NULL,
    engineer_scope_epoch    BIGINT        NOT NULL,
    task_version_snapshot   INT           NOT NULL,
    evidence_canonical_json LONGTEXT      NOT NULL,
    evidence_sha256         CHAR(64)      NOT NULL,
    request_sha256          CHAR(64)      NOT NULL,
    business_comment        VARCHAR(1000),
    created_at              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_dual_submission_revision UNIQUE (tenant_id, task_id, task_generation, revision),
    CONSTRAINT uk_dual_submission_binding UNIQUE (tenant_id, task_id, task_generation, revision, id, evidence_sha256),
    CONSTRAINT uk_dual_submission_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT ck_dual_submission_nonneg CHECK (task_generation >= 0 AND revision > 0
        AND global_epoch >= 0 AND submitter_actor_epoch >= 0 AND engineer_scope_epoch >= 0)
);

CREATE TABLE IF NOT EXISTS t_lifecycle_dual_submission_document (
    tenant_id                  VARCHAR(100) NOT NULL,
    submission_id              BIGINT       NOT NULL,
    document_id                BIGINT       NOT NULL,
    document_version_id        BIGINT       NOT NULL,
    version_no                 INT          NOT NULL,
    sha256                     CHAR(64)     NOT NULL,
    evidence_eligibility_epoch BIGINT       NOT NULL,
    PRIMARY KEY (submission_id, document_id)
);

CREATE TABLE IF NOT EXISTS t_lifecycle_dual_submission_org_fence (
    tenant_id       VARCHAR(100) NOT NULL,
    submission_id   BIGINT       NOT NULL,
    organization_id BIGINT       NOT NULL,
    org_scope_epoch BIGINT       NOT NULL,
    PRIMARY KEY (submission_id, organization_id)
);

CREATE TABLE IF NOT EXISTS t_lifecycle_dual_confirmation (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id               VARCHAR(100)  NOT NULL,
    case_id                 BIGINT        NOT NULL,
    task_id                 BIGINT        NOT NULL,
    task_generation         BIGINT        NOT NULL,
    submission_id           BIGINT        NOT NULL,
    submission_revision     INT           NOT NULL,
    evidence_sha256         CHAR(64)      NOT NULL,
    confirmer_user_id       BIGINT        NOT NULL,
    confirmer_role_snapshot VARCHAR(30)   NOT NULL,
    global_epoch            BIGINT        NOT NULL,
    confirmer_actor_epoch   BIGINT        NOT NULL,
    decision                VARCHAR(10)   NOT NULL,
    decision_comment        VARCHAR(1000),
    decided_at              DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_dual_confirmation_submission UNIQUE (tenant_id, submission_id),
    CONSTRAINT ck_dual_confirmation_decision CHECK (decision IN ('CONFIRMED', 'REJECTED'))
);

CREATE TABLE IF NOT EXISTS t_lifecycle_dual_projection (
    tenant_id          VARCHAR(100) NOT NULL,
    task_id            BIGINT       NOT NULL,
    case_id            BIGINT       NOT NULL,
    task_generation    BIGINT       NOT NULL,
    revision           INT          NOT NULL,
    submission_id      BIGINT       NOT NULL,
    evidence_sha256    CHAR(64)     NOT NULL,
    state              VARCHAR(12)  NOT NULL,
    projection_version BIGINT       NOT NULL DEFAULT 0,
    updated_at         DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (tenant_id, task_id),
    CONSTRAINT ck_dual_projection_state CHECK (state IN ('PENDING', 'CONFIRMED', 'REJECTED'))
);

CREATE TABLE IF NOT EXISTS t_lifecycle_dual_idempotency (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id       VARCHAR(100) NOT NULL,
    case_id         BIGINT       NOT NULL,
    task_id         BIGINT       NOT NULL,
    task_generation BIGINT       NOT NULL,
    operation       VARCHAR(10)  NOT NULL,
    actor_user_id   BIGINT       NOT NULL,
    idempotency_key VARCHAR(190) NOT NULL,
    request_sha256  CHAR(64)     NOT NULL,
    submission_id   BIGINT       NOT NULL,
    confirmation_id BIGINT,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_dual_idempotency UNIQUE (tenant_id, task_id, task_generation, operation, actor_user_id, idempotency_key),
    CONSTRAINT ck_dual_idem_operation CHECK (operation IN ('SUBMIT', 'CONFIRM')),
    CONSTRAINT ck_dual_idem_key CHECK (CHAR_LENGTH(idempotency_key) >= 1
        AND NOT REGEXP_LIKE(idempotency_key, '[^A-Za-z0-9._~-]', 'c'))
);

CREATE TABLE IF NOT EXISTS t_lifecycle_dual_event (
    id                      BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id               VARCHAR(100) NOT NULL,
    case_id                 BIGINT,
    task_id                 BIGINT,
    task_generation         BIGINT,
    submission_id           BIGINT,
    requested_task_id       BIGINT,
    requested_submission_id BIGINT,
    actor_user_id           BIGINT       NOT NULL,
    operation               VARCHAR(10)  NOT NULL,
    event_type              VARCHAR(20)  NOT NULL,
    reason_code             VARCHAR(60),
    http_status             SMALLINT,
    attempt_count           TINYINT,
    occurred_at             DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_dual_event_type CHECK (event_type IN ('SUBMITTED', 'SUPERSEDED', 'CONFIRMED', 'REJECTED', 'DENIED'))
);

CREATE INDEX IF NOT EXISTS idx_dual_event_task ON t_lifecycle_dual_event (tenant_id, task_id, occurred_at);

-- sys_user (tenant_id, id) の一意制約を明示する（MySQLのV165 uk_sys_user_tenant_id に対応。H2のMySQL modeが参照列へ
-- 暗黙に作る一意制約に頼らない）。sys_user はV1のreplayと engineer-schema-h2.sql が作り直すため、FKより先に毎回付ける。
ALTER TABLE sys_user ADD CONSTRAINT IF NOT EXISTS uk_sys_user_tenant_id UNIQUE (tenant_id, id);

-- 提出
ALTER TABLE t_lifecycle_dual_submission ADD CONSTRAINT IF NOT EXISTS fk_dual_submission_case
    FOREIGN KEY (tenant_id, case_id) REFERENCES t_lifecycle_case (tenant_id, id);
ALTER TABLE t_lifecycle_dual_submission ADD CONSTRAINT IF NOT EXISTS fk_dual_submission_task
    FOREIGN KEY (case_id, task_id) REFERENCES t_lifecycle_task (case_id, id);
ALTER TABLE t_lifecycle_dual_submission ADD CONSTRAINT IF NOT EXISTS fk_dual_submission_submitter
    FOREIGN KEY (tenant_id, submitter_user_id) REFERENCES sys_user (tenant_id, id);
-- 提出文書
ALTER TABLE t_lifecycle_dual_submission_document ADD CONSTRAINT IF NOT EXISTS fk_dual_doc_submission
    FOREIGN KEY (tenant_id, submission_id) REFERENCES t_lifecycle_dual_submission (tenant_id, id);
ALTER TABLE t_lifecycle_dual_submission_document ADD CONSTRAINT IF NOT EXISTS fk_dual_doc_version
    FOREIGN KEY (tenant_id, document_id, document_version_id) REFERENCES t_document_version (tenant_id, document_id, id);
-- 提出時の組織集合
ALTER TABLE t_lifecycle_dual_submission_org_fence ADD CONSTRAINT IF NOT EXISTS fk_dual_org_submission
    FOREIGN KEY (tenant_id, submission_id) REFERENCES t_lifecycle_dual_submission (tenant_id, id);
ALTER TABLE t_lifecycle_dual_submission_org_fence ADD CONSTRAINT IF NOT EXISTS fk_dual_org_unit
    FOREIGN KEY (organization_id) REFERENCES m_organization_unit (id);
-- 確認・差戻し
ALTER TABLE t_lifecycle_dual_confirmation ADD CONSTRAINT IF NOT EXISTS fk_dual_confirmation_submission
    FOREIGN KEY (tenant_id, task_id, task_generation, submission_revision, submission_id, evidence_sha256)
    REFERENCES t_lifecycle_dual_submission (tenant_id, task_id, task_generation, revision, id, evidence_sha256);
ALTER TABLE t_lifecycle_dual_confirmation ADD CONSTRAINT IF NOT EXISTS fk_dual_confirmation_confirmer
    FOREIGN KEY (tenant_id, confirmer_user_id) REFERENCES sys_user (tenant_id, id);
-- 現在状態
ALTER TABLE t_lifecycle_dual_projection ADD CONSTRAINT IF NOT EXISTS fk_dual_projection_case
    FOREIGN KEY (tenant_id, case_id) REFERENCES t_lifecycle_case (tenant_id, id);
ALTER TABLE t_lifecycle_dual_projection ADD CONSTRAINT IF NOT EXISTS fk_dual_projection_task
    FOREIGN KEY (case_id, task_id) REFERENCES t_lifecycle_task (case_id, id);
ALTER TABLE t_lifecycle_dual_projection ADD CONSTRAINT IF NOT EXISTS fk_dual_projection_submission
    FOREIGN KEY (tenant_id, task_id, task_generation, revision, submission_id, evidence_sha256)
    REFERENCES t_lifecycle_dual_submission (tenant_id, task_id, task_generation, revision, id, evidence_sha256);
-- 冪等性
ALTER TABLE t_lifecycle_dual_idempotency ADD CONSTRAINT IF NOT EXISTS fk_dual_idem_case
    FOREIGN KEY (tenant_id, case_id) REFERENCES t_lifecycle_case (tenant_id, id);
ALTER TABLE t_lifecycle_dual_idempotency ADD CONSTRAINT IF NOT EXISTS fk_dual_idem_actor
    FOREIGN KEY (tenant_id, actor_user_id) REFERENCES sys_user (tenant_id, id);
