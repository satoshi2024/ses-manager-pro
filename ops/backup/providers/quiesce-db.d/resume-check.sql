-- ses-backup:quiesce-db:resume-check
-- 解除の後の再開の確認（design.md §7.3。D-P3-2・D3-P3-4）。この要求に QUIESCED の ack を持ち、今 heartbeat が新しい
-- （STOPPED でなく QUIESCE_STALE_SECONDS の内）instance の ack が RESUMED になるのを待つ。区間の中で heartbeat が止まった
-- instance（落ちた等）は待たない（provider が WARN を出す）。ack の行の無い instance（ack の前に要求が解かれた。U-13 の中止の
-- 時の新しい instance 等）はこの表に現れないため待たない。
-- 引数（検査済みの整数）: @p_request_id・@p_stale_seconds
-- 結果: VERDICT <OK | WAIT> <UTC の今> <今の epoch の μ秒> <待つ instance の数> <内訳>、
--   ACK <instance_id> <ack の状態> <heartbeat が新しい 1/0> <host 名>
START TRANSACTION READ ONLY;
WITH w AS (
  SELECT a.instance_id, a.state,
         CASE WHEN i.instance_id IS NOT NULL AND i.status <> 'STOPPED'
                   AND TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_stale_seconds * 1000000
              THEN 1 ELSE 0 END AS fresh
    FROM sys_backup_quiesce_ack a
    LEFT JOIN sys_backup_quiesce_instance i ON i.instance_id = a.instance_id
   WHERE a.request_id = @p_request_id
)
SELECT 'VERDICT',
       CASE WHEN k.n_waiting > 0 THEN 'WAIT' ELSE 'OK' END,
       DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%sZ'),
       TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(6)),
       k.n_waiting,
       CONCAT('waiting=', k.n_waiting, ',down=', k.n_down)
  FROM (
    SELECT COALESCE(SUM(w.state = 'QUIESCED' AND w.fresh = 1), 0) AS n_waiting,
           COALESCE(SUM(w.state = 'QUIESCED' AND w.fresh = 0), 0) AS n_down
      FROM w
  ) k;
WITH w AS (
  SELECT a.instance_id, a.state, COALESCE(NULLIF(i.host_name, ''), '-') AS host_name,
         CASE WHEN i.instance_id IS NOT NULL AND i.status <> 'STOPPED'
                   AND TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_stale_seconds * 1000000
              THEN 1 ELSE 0 END AS fresh
    FROM sys_backup_quiesce_ack a
    LEFT JOIN sys_backup_quiesce_instance i ON i.instance_id = a.instance_id
   WHERE a.request_id = @p_request_id
)
SELECT 'ACK', w.instance_id, w.state, w.fresh, w.host_name
  FROM w
 ORDER BY w.instance_id;
COMMIT;
