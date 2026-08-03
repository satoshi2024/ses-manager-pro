-- B1: step SLA判定の基準時刻を申請行へ保存する。
-- 既存申請は既存requested_atを初回step開始時刻として扱い、進行中申請の期限判定を継続可能にする。
SET @sql = (SELECT IF(COUNT(*) = 0,
  'ALTER TABLE t_approval_request ADD COLUMN current_step_started_at DATETIME NULL AFTER current_step',
  'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE()
    AND table_name = 't_approval_request'
    AND column_name = 'current_step_started_at');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE t_approval_request
SET current_step_started_at = requested_at
WHERE current_step_started_at IS NULL
  AND status = 'in_review'
  AND requested_at IS NOT NULL;
