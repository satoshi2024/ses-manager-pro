-- MySQLの旧V4/V5を変更せず、テスト用H2へV60の追加列だけを反映する。
ALTER TABLE t_notification ADD COLUMN IF NOT EXISTS organization_id BIGINT;
ALTER TABLE t_invoice ADD COLUMN IF NOT EXISTS cost_center_id BIGINT;
ALTER TABLE t_bp_payment ADD COLUMN IF NOT EXISTS cost_center_id BIGINT;
