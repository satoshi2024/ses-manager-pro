-- ses-backup:quiesce-db:full-new-instance
-- ONLINE の full の最中の新しい instance（U-13。design.md §7.6 の 4。D3-P2-2）。要求の後に登録した（started_at >= requested_at）
-- STOPPED でない instance があれば、mode = 'ONLINE' かつ job_kind = 'FULL' の要求だけを ABORTED（NEW_INSTANCE）にする
-- （APP_STOPPED の full と checkpoint は中止しない。保持 process もこの file を ONLINE の full の時だけ実行する）。
-- 数えるのは一貫読取りで、UPDATE は要求の行だけを lock する（instance の行に locking read をしない）。
-- 引数（検査済みの整数）: @p_request_id
-- 結果: ABORT <変わった行の数> <要求の後に登録した instance の数>
SET @v_new = (SELECT COUNT(*)
                FROM sys_backup_quiesce_instance i
                JOIN sys_backup_quiesce_request q ON q.id = @p_request_id
               WHERE i.started_at >= q.requested_at AND i.status <> 'STOPPED');
UPDATE sys_backup_quiesce_request
   SET status = 'ABORTED', released_at = UTC_TIMESTAMP(6), release_reason = 'NEW_INSTANCE'
 WHERE id = @p_request_id AND status = 'REQUESTED' AND mode = 'ONLINE' AND job_kind = 'FULL' AND @v_new > 0;
SELECT 'ABORT', ROW_COUNT(), @v_new;
