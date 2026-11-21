-- ses-backup:quiesce-db:verify-request
-- verify の前に app へ接続元を観測し直させる（design.md §5.4・§7.2 の 1）。要求が既に解かれていれば 0 行（full の
-- NEW_INSTANCE の中止を含む）で、provider は verify を失敗にする。
-- 引数（検査済みの整数）: @p_request_id
-- 結果: VERIFY_REQUESTED <変わった行の数>
UPDATE sys_backup_quiesce_request
   SET verify_requested_at = UTC_TIMESTAMP(6)
 WHERE id = @p_request_id AND status = 'REQUESTED';
SELECT 'VERIFY_REQUESTED', ROW_COUNT();
