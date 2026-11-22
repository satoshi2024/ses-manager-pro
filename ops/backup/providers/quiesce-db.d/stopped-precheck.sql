-- ses-backup:quiesce-db:stopped-precheck
-- app の停止を確かめる backup（quiesce-app-stopped。APP_STOPPED）の事前の確認（助言。design.md §9.2 の 2）。
-- 期限切れの有効な要求を EXPIRED にしてから、有効な要求が残っていれば失敗、STOPPED でなく heartbeat が停止済みの閾値の内の
-- instance（ALIVE）と、STOPPED でない停止口の行（DISABLED。heartbeat の古さによらない。D3-P2-3）が 1 つでもあれば失敗する。
-- 表が空なら通る。
-- 引数（検査済みの整数）: @p_stale_seconds・@p_dead_seconds・@p_full（full なら 1）
-- 結果: EXPIRED <件数>、VERDICT <OK | FAIL_理由> <UTC の今> <今の epoch の μ秒> <停止していない instance の数> <内訳>、
--   INSTANCE の行（欄は poll-acks.sql と同じ。要求に依る欄は -）
UPDATE sys_backup_quiesce_request
   SET status = 'EXPIRED', released_at = UTC_TIMESTAMP(6), release_reason = 'EXPIRED'
 WHERE status = 'REQUESTED' AND lease_expires_at <= UTC_TIMESTAMP(6);
SELECT 'EXPIRED', ROW_COUNT();
START TRANSACTION READ ONLY;
WITH c AS (
  SELECT i.instance_id,
         CASE
           WHEN i.status <> 'STOPPED' AND i.gate_state = 'DISABLED' THEN 'DISABLED'
           WHEN i.status <> 'STOPPED'
                AND TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_dead_seconds * 1000000
             THEN 'ALIVE'
           ELSE 'IGNORED'
         END AS cls
    FROM sys_backup_quiesce_instance i
)
SELECT 'VERDICT',
       CASE
         WHEN k.n_active > 0 THEN 'FAIL_ACTIVE_REQUEST'
         WHEN k.n_disabled > 0 THEN 'FAIL_DISABLED'
         WHEN k.n_alive > 0 THEN 'FAIL_ALIVE'
         ELSE 'OK'
       END,
       DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%sZ'),
       TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(6)),
       k.n_alive + k.n_disabled,
       CONCAT('active=', k.n_active, ',disabled=', k.n_disabled, ',alive=', k.n_alive)
  FROM (
    SELECT (SELECT COUNT(*) FROM sys_backup_quiesce_request q WHERE q.status = 'REQUESTED') AS n_active,
           COALESCE(SUM(c.cls = 'DISABLED'), 0) AS n_disabled,
           COALESCE(SUM(c.cls = 'ALIVE'), 0) AS n_alive
      FROM c
  ) k;
WITH c AS (
  SELECT i.instance_id, i.host_name, i.db_client_host, i.status, i.gate_state, i.started_at,
         TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) AS age_us,
         CASE WHEN TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_stale_seconds * 1000000
              THEN 1 ELSE 0 END AS fresh,
         CASE WHEN @p_full = 1 THEN i.max_hold_full_ms ELSE i.max_hold_checkpoint_ms END AS max_hold_ms,
         CASE
           WHEN i.status <> 'STOPPED' AND i.gate_state = 'DISABLED' THEN 'DISABLED'
           WHEN i.status <> 'STOPPED'
                AND TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_dead_seconds * 1000000
             THEN 'ALIVE'
           ELSE 'IGNORED'
         END AS cls
    FROM sys_backup_quiesce_instance i
)
SELECT 'INSTANCE', c.instance_id, c.cls, c.fresh, '-', '-', '-',
       COALESCE(NULLIF(c.host_name, ''), '-'), COALESCE(NULLIF(c.db_client_host, ''), '-'),
       DATE_FORMAT(c.started_at, '%Y-%m-%dT%H:%i:%s.%fZ'), '-', '-', '-', 0, c.max_hold_ms, c.age_us DIV 1000,
       c.status, c.gate_state
  FROM c
 ORDER BY c.started_at, c.instance_id;
COMMIT;
