-- ses-backup:quiesce-db:renew
-- 保持 process の lease の延長（design.md §3.3・§7.6 の 2・3）。期限を過ぎた lease は延ばさない。
-- 延長の上限は requested_at から min(@p_lease_max_seconds, 必要な instance の最大保持時間(job_kind)) 秒で、上限を超えて
-- 延ばさない（D-P3-6）。必要な instance の分類は §5.3 と同じ（STOPPED でなく、heartbeat が停止済みの閾値の内か要求の後に
-- heartbeat を書いたもの）。instance の読取りは locking read にしない（SET の中の副問合せは一貫読取り）。
-- 引数（検査済みの整数）: @p_request_id・@p_lease_max_seconds・@p_dead_seconds
-- 結果: RENEW <変わった行の数>、LEASE <要求の状態> <lease が有効 1/0> <上限に達した 1/0> <上限の秒数> <lease_expires_at（UTC）>
SET @v_cap = (SELECT LEAST(@p_lease_max_seconds,
                           COALESCE(MIN(CASE WHEN q.job_kind = 'FULL' THEN i.max_hold_full_ms
                                             ELSE i.max_hold_checkpoint_ms END DIV 1000), @p_lease_max_seconds))
                FROM sys_backup_quiesce_request q
                LEFT JOIN sys_backup_quiesce_instance i
                       ON i.status <> 'STOPPED'
                      AND (TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_dead_seconds * 1000000
                           OR i.last_beat_at >= q.requested_at)
               WHERE q.id = @p_request_id);
UPDATE sys_backup_quiesce_request
   SET lease_expires_at = LEAST(TIMESTAMPADD(SECOND, lease_seconds, UTC_TIMESTAMP(6)),
                                TIMESTAMPADD(SECOND, @v_cap, requested_at))
 WHERE id = @p_request_id AND status = 'REQUESTED' AND lease_expires_at > UTC_TIMESTAMP(6)
   AND lease_expires_at < TIMESTAMPADD(SECOND, @v_cap, requested_at);
SELECT 'RENEW', ROW_COUNT();
SELECT 'LEASE', q.status,
       CASE WHEN q.lease_expires_at > UTC_TIMESTAMP(6) THEN 1 ELSE 0 END,
       CASE WHEN q.lease_expires_at >= TIMESTAMPADD(SECOND, @v_cap, q.requested_at) THEN 1 ELSE 0 END,
       @v_cap,
       DATE_FORMAT(q.lease_expires_at, '%Y-%m-%dT%H:%i:%s.%fZ')
  FROM sys_backup_quiesce_request q
 WHERE q.id = @p_request_id;
