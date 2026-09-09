-- V169: 履歴取込原本・候補者・案件skill replacementのforward-only境界。
-- 既存行のtenantをdefaultへ寄せず、証拠がないNULL行は各resolverから不可視とする。
ALTER TABLE t_resume_ingestion
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id,
    ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER candidate_id;

-- 保持期限到達後に原本を消去できるよう、監査用ジョブ行だけを残して原本列をNULL許可へ変更する。
-- tenant不明のlegacy行はNULLのまま保持し、認可・清理対象から推測で復帰させない。
ALTER TABLE t_resume_ingestion
    MODIFY COLUMN original_file_name VARCHAR(255) NULL,
    MODIFY COLUMN stored_file_name VARCHAR(120) NULL,
    MODIFY COLUMN file_ext VARCHAR(10) NULL;

UPDATE t_resume_ingestion r
JOIN sys_user u ON u.id = r.created_by AND u.deleted_flag = 0
SET r.tenant_id = u.tenant_id
WHERE r.tenant_id IS NULL AND u.tenant_id IS NOT NULL AND u.tenant_id <> '';

UPDATE t_resume_ingestion r
JOIN t_engineer e ON e.id = r.converted_engineer_id AND e.deleted_flag = 0
SET r.tenant_id = e.tenant_id
WHERE r.tenant_id IS NULL AND e.tenant_id IS NOT NULL AND e.tenant_id <> '';

CREATE INDEX idx_resume_ingestion_tenant_status_file
    ON t_resume_ingestion (tenant_id, status, stored_file_name, deleted_flag);

ALTER TABLE t_candidate
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id,
    ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER converted_engineer_id;

UPDATE t_candidate c
JOIN sys_user u ON u.id = c.created_by AND u.deleted_flag = 0
SET c.tenant_id = u.tenant_id
WHERE c.tenant_id IS NULL AND u.tenant_id IS NOT NULL AND u.tenant_id <> '';

UPDATE t_candidate c
JOIN t_engineer e ON e.id = c.converted_engineer_id AND e.deleted_flag = 0
SET c.tenant_id = e.tenant_id
WHERE c.tenant_id IS NULL AND e.tenant_id IS NOT NULL AND e.tenant_id <> '';

CREATE INDEX idx_candidate_tenant_stage
    ON t_candidate (tenant_id, current_stage, deleted_flag, id);

ALTER TABLE t_project
    ADD COLUMN version INT NOT NULL DEFAULT 0 AFTER source_opportunity_id;

CREATE INDEX idx_project_customer_version
    ON t_project (customer_id, version, deleted_flag, id);
