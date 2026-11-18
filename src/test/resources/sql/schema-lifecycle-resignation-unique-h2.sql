-- V187相当のH2 schema（同一要員の未終結退社案件をtenant内で1件に限定する生成列と一意index）。
-- H2は生成列のSTORED指定を解釈しないため、db/migration/V187 と同じ式をSTORED無しで定義する。
-- 共有H2はcontextごとにschema-locationsを再実行するため、列・indexとも IF NOT EXISTS で冪等にする。
-- t_lifecycle_case.tenant_id は schema-service-desk-h2.sql が付与するため、それより後に置くこと。
ALTER TABLE t_lifecycle_case ADD COLUMN IF NOT EXISTS active_resignation_engineer_id BIGINT
    GENERATED ALWAYS AS (
        CASE
            WHEN lifecycle_type = 'RESIGNATION'
             AND status NOT IN ('COMPLETED', 'CANCELLED')
             AND deleted_flag = 0
            THEN engineer_id
            ELSE NULL
        END
    );
CREATE UNIQUE INDEX IF NOT EXISTS uk_lifecycle_case_active_resignation
    ON t_lifecycle_case (tenant_id, active_resignation_engineer_id);
