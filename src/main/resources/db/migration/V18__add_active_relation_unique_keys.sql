-- 有効レコードだけに適用する最終一意性制約。
--
-- MySQL の UNIQUE は複数の NULL を許容するため、論理削除済みレコードの
-- 生成列は NULL にする。削除履歴の共存と同一関係の再作成を両立できる。
-- 既存の有効レコードに重複がある場合、ADD UNIQUE KEY は明示的に失敗する。
-- この migration は残すレコードを推測せず、業務データを自動削除しない。
--
-- 本番適用前の重複確認 SQL:
-- SELECT work_record_id, layer_order, COUNT(*) FROM t_bp_payment
--  WHERE deleted_flag = 0 GROUP BY work_record_id, layer_order HAVING COUNT(*) > 1;
-- SELECT proposal_id, COUNT(*) FROM t_contract
--  WHERE deleted_flag = 0 AND proposal_id IS NOT NULL GROUP BY proposal_id HAVING COUNT(*) > 1;
-- SELECT renewed_from_contract_id, COUNT(*) FROM t_contract
--  WHERE deleted_flag = 0 AND renewed_from_contract_id IS NOT NULL
--  GROUP BY renewed_from_contract_id HAVING COUNT(*) > 1;

ALTER TABLE t_bp_payment
  ADD COLUMN active_work_record_id BIGINT
    GENERATED ALWAYS AS (CASE WHEN deleted_flag = 0 THEN work_record_id ELSE NULL END) STORED,
  ADD COLUMN active_layer_order INT
    GENERATED ALWAYS AS (CASE WHEN deleted_flag = 0 THEN layer_order ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_bp_payment_active_layer (active_work_record_id, active_layer_order);

ALTER TABLE t_contract
  ADD COLUMN active_proposal_id BIGINT
    GENERATED ALWAYS AS (CASE WHEN deleted_flag = 0 THEN proposal_id ELSE NULL END) STORED,
  ADD COLUMN active_renewed_from_contract_id BIGINT
    GENERATED ALWAYS AS (CASE WHEN deleted_flag = 0 THEN renewed_from_contract_id ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_contract_active_proposal (active_proposal_id),
  ADD UNIQUE KEY uk_contract_active_renewal_source (active_renewed_from_contract_id);
