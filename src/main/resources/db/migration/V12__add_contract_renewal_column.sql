-- ============================================================
-- SES Manager Pro - 契約自動更新ドラフト追跡列 (P8フォローアップ・提案14)
-- ファイル: V12__add_contract_renewal_column.sql
-- 説明: auto_renew=1の契約について自動生成する更新ドラフトが、
--       元契約1件につき重複生成されないよう追跡する列を追加する。
-- ============================================================

ALTER TABLE t_contract
  ADD COLUMN renewed_from_contract_id BIGINT NULL COMMENT '自動更新ドラフトの生成元契約ID';
