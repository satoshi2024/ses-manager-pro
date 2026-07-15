-- 既存のUNIQUE制約は論理削除（deleted_flag=1）されたレコードと
-- 新規追加される同一番号の階層との間で重複エラーを引き起こすため削除し、
-- 代わりに外部キー制約用・検索用の通常のインデックスを付与する。

CREATE INDEX idx_bp_payment_work_record ON t_bp_payment (work_record_id);
ALTER TABLE t_bp_payment DROP INDEX uk_work_record_layer;
