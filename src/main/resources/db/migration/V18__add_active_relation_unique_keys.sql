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

-- t_bp_payment: add generated column for work_record_id
ALTER TABLE t_bp_payment
  ADD COLUMN active_work_record_id BIGINT
    GENERATED ALWAYS AS (IF(deleted_flag = 0, work_record_id, CAST(NULL AS BIGINT))) STORED;

-- t_bp_payment: add generated column for layer_order
ALTER TABLE t_bp_payment
  ADD COLUMN active_layer_order INT
    GENERATED ALWAYS AS (IF(deleted_flag = 0, layer_order, CAST(NULL AS INT))) STORED;

-- t_bp_payment: add unique key on generated columns
ALTER TABLE t_bp_payment
  ADD UNIQUE KEY uk_bp_payment_active_layer (active_work_record_id, active_layer_order);

-- t_contract: add generated column for proposal_id
ALTER TABLE t_contract
  ADD COLUMN active_proposal_id BIGINT
    GENERATED ALWAYS AS (IF(deleted_flag = 0, proposal_id, CAST(NULL AS BIGINT))) STORED;

-- t_contract: add generated column for renewed_from_contract_id
ALTER TABLE t_contract
  ADD COLUMN active_renewed_from_contract_id BIGINT
    GENERATED ALWAYS AS (IF(deleted_flag = 0, renewed_from_contract_id, CAST(NULL AS BIGINT))) STORED;

-- t_contract: add unique key for active_proposal_id
ALTER TABLE t_contract
  ADD UNIQUE KEY uk_contract_active_proposal (active_proposal_id);

-- t_contract: add unique key for active_renewed_from_contract_id
ALTER TABLE t_contract
  ADD UNIQUE KEY uk_contract_active_renewal_source (active_renewed_from_contract_id);
