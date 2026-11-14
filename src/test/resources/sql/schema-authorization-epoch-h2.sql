-- V189相当のH2 schema（NF01 細粒度認可epoch / epoch A spine）。
-- db/migration/V189 はMySQLの複数句ALTER（ADD COLUMN と ADD CONSTRAINT を1文に並べる形）・ENGINE・COMMENTを含むため、
-- H2のreplayには同じ表・列・CHECK・一意keyを1文ずつ置く。
-- 共有H2はcontextごとにschema-locationsを再実行するため、表・列・制約・indexとも IF NOT EXISTS で冪等にする。
-- t_lifecycle_case.tenant_id は schema-service-desk-h2.sql が付与し、t_document / t_document_version は
-- schema-document-archive-h2.sql が作り直すため、本fileはそれらより後に置くこと。
-- 認可epochの読取（FOR SHARE）はH2で実行しない（計画 §2.5。fast testはreaderをmockする）。
CREATE TABLE IF NOT EXISTS t_authorization_global_epoch (
    singleton_id TINYINT      NOT NULL PRIMARY KEY,
    epoch        BIGINT       NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_authz_global_singleton CHECK (singleton_id = 1),
    CONSTRAINT ck_authz_global_epoch_nonneg CHECK (epoch >= 0)
);

INSERT INTO t_authorization_global_epoch (singleton_id, epoch)
SELECT 1, 0
WHERE NOT EXISTS (SELECT 1 FROM t_authorization_global_epoch WHERE singleton_id = 1);

ALTER TABLE sys_user ADD COLUMN IF NOT EXISTS actor_authz_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE sys_user ADD CONSTRAINT IF NOT EXISTS ck_sys_user_actor_authz_epoch
    CHECK (actor_authz_epoch >= 0);

ALTER TABLE t_engineer ADD COLUMN IF NOT EXISTS engineer_scope_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE t_engineer ADD CONSTRAINT IF NOT EXISTS ck_engineer_scope_epoch
    CHECK (engineer_scope_epoch >= 0);

ALTER TABLE m_organization_unit ADD COLUMN IF NOT EXISTS org_scope_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE m_organization_unit ADD CONSTRAINT IF NOT EXISTS ck_org_scope_epoch
    CHECK (org_scope_epoch >= 0);

ALTER TABLE t_document ADD COLUMN IF NOT EXISTS evidence_eligibility_epoch BIGINT NOT NULL DEFAULT 0;
ALTER TABLE t_document ADD CONSTRAINT IF NOT EXISTS ck_document_evidence_eligibility_epoch
    CHECK (evidence_eligibility_epoch >= 0);

ALTER TABLE t_lifecycle_task ADD COLUMN IF NOT EXISTS task_generation BIGINT NOT NULL DEFAULT 0;
ALTER TABLE t_lifecycle_task ADD CONSTRAINT IF NOT EXISTS ck_lifecycle_task_generation
    CHECK (task_generation >= 0);

-- B0の複合FKが参照する一意keyの前提（sys_user (tenant_id, id) はV165の既存keyのため置かない）。
CREATE UNIQUE INDEX IF NOT EXISTS uk_lifecycle_case_tenant_id ON t_lifecycle_case (tenant_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_lifecycle_task_case_id ON t_lifecycle_task (case_id, id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_document_version_tenant_doc_id
    ON t_document_version (tenant_id, document_id, id);
