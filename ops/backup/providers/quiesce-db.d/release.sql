-- ses-backup:quiesce-db:release
-- 静止の解除（design.md §7.3）と、失敗の後の後始末（§7.1 の失敗の cleanup・§7.2 の VERIFY_FAILED）。有効な要求だけを変える
-- （既に解かれた要求。full の NEW_INSTANCE の中止・次の provider の EXPIRED 等は変えない）。
-- 引数: @p_request_id（整数）・@p_status（RELEASED / ABORTED）・@p_reason（COMPLETED / ABORTED / VERIFY_FAILED）
-- 結果: RELEASE <変わった行の数>、REQUEST_STATE <要求の状態> <理由> <released_at（UTC）>
UPDATE sys_backup_quiesce_request
   SET status = @p_status, released_at = UTC_TIMESTAMP(6), release_reason = @p_reason
 WHERE id = @p_request_id AND status = 'REQUESTED';
SELECT 'RELEASE', ROW_COUNT();
SELECT 'REQUEST_STATE', q.status, COALESCE(q.release_reason, '-'),
       COALESCE(DATE_FORMAT(q.released_at, '%Y-%m-%dT%H:%i:%sZ'), '-')
  FROM sys_backup_quiesce_request q
 WHERE q.id = @p_request_id;
