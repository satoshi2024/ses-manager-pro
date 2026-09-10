-- NF08: legacy matching runにもquery時点のscopeと再現用metadataを保存する。
-- 既存runは推測でbackfillせずNULLのまま保持する。新規recorder経由runだけ全項目を束縛する。
ALTER TABLE t_ai_recommendation_run
    ADD COLUMN scope_hash CHAR(64) NULL COMMENT 'query時点のEffectiveScopeSnapshot hash',
    ADD COLUMN parameter_hash CHAR(64) NULL COMMENT '正規化済みmatching parameter hash',
    ADD COLUMN tenant_id VARCHAR(64) NULL COMMENT 'security-bound tenant',
    ADD COLUMN legal_entity_id BIGINT NULL COMMENT 'security-bound legal entity',
    ADD COLUMN as_of_at DATETIME NULL COMMENT 'query時点のasOf（UTC）',
    ADD COLUMN timezone_id VARCHAR(64) NULL COMMENT 'query時点のtenant timezone',
    ADD COLUMN catalog_version VARCHAR(64) NULL COMMENT 'legacy catalog contract version',
    ADD COLUMN data_version VARCHAR(64) NULL COMMENT 'resource/scope data contract version';

CREATE INDEX idx_ai_run_scope_tenant_legal
    ON t_ai_recommendation_run (tenant_id, legal_entity_id, scope_hash, id);
