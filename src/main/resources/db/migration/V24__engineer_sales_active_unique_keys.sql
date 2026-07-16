-- 要員担当営業の現任レコード重複を DB 側でも防止する。
ALTER TABLE t_engineer_sales
  ADD COLUMN active_assignment_key VARCHAR(80)
    GENERATED ALWAYS AS (
      IF(deleted_flag = 0 AND released_at IS NULL,
         CONCAT(engineer_id, ':', sales_user_id), NULL)
    ) VIRTUAL,
  ADD COLUMN active_primary_key BIGINT
    GENERATED ALWAYS AS (
      IF(deleted_flag = 0 AND released_at IS NULL AND primary_flag = 1,
         engineer_id, NULL)
    ) VIRTUAL;

ALTER TABLE t_engineer_sales
  ADD UNIQUE KEY uk_engsales_active_assignment (active_assignment_key),
  ADD UNIQUE KEY uk_engsales_active_primary (active_primary_key);

-- 終端状態（成約・見送り）以外の提案は要員×案件で一意にする。
ALTER TABLE t_proposal
  ADD COLUMN active_proposal_key VARCHAR(80)
    GENERATED ALWAYS AS (
      IF(status IN ('書類選考中','一次面接','二次面接','結果待ち'),
         CONCAT(engineer_id, ':', project_id), NULL)
    ) VIRTUAL,
  ADD UNIQUE KEY uk_proposal_active_engineer_project (active_proposal_key);
