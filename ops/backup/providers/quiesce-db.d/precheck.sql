-- ses-backup:quiesce-db:precheck
-- ONLINE（quiesce-db）の acquire の事前の確認（助言。design.md §7.1 の 3）。
-- 期限切れの有効な要求を EXPIRED にしてから、要求の無い状態で instance を分類する（§5.3。要求が無いので「必要」は
-- heartbeat が停止済みの閾値の内のもの）。停止口の行（STOPPED でない DISABLED）は heartbeat の古さによらず数える（D3-P2-3）。
-- 引数（検査済みの整数）: @p_stale_seconds・@p_dead_seconds・@p_protocol_version・@p_lease_seconds・@p_renew_seconds・
--   @p_full（full なら 1）
-- 結果: EXPIRED <件数>、VERDICT <OK | FAIL_理由> <UTC の今> <今の epoch の μ秒> <必要な instance の数> <内訳>、
--   instance ごとの INSTANCE の行（欄は poll-acks.sql と同じ。要求に依る欄は -）
-- 時刻は UTC_TIMESTAMP(6) だけで書き・比べる（§3.10）。空の欄を作らない（NULL・空は -）。
UPDATE sys_backup_quiesce_request
   SET status = 'EXPIRED', released_at = UTC_TIMESTAMP(6), release_reason = 'EXPIRED'
 WHERE status = 'REQUESTED' AND lease_expires_at <= UTC_TIMESTAMP(6);
SELECT 'EXPIRED', ROW_COUNT();
START TRANSACTION READ ONLY;
WITH c AS (
  SELECT i.instance_id, i.host_name, i.db_client_host, i.status, i.gate_state, i.protocol_version, i.started_at,
         TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) AS age_us,
         CASE WHEN TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_stale_seconds * 1000000
              THEN 1 ELSE 0 END AS fresh,
         CASE WHEN @p_stale_seconds * 1000 >= i.fence_after_ms + 2 * i.heartbeat_interval_ms
                   AND i.fence_after_ms + i.poll_interval_ms < (@p_lease_seconds - @p_renew_seconds) * 1000
              THEN 1 ELSE 0 END AS thresholds_ok,
         CASE WHEN @p_full = 1 THEN i.max_hold_full_ms ELSE i.max_hold_checkpoint_ms END AS max_hold_ms,
         CASE
           WHEN i.status <> 'STOPPED' AND i.gate_state = 'DISABLED' THEN 'DISABLED'
           WHEN i.status <> 'STOPPED'
                AND TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_dead_seconds * 1000000
             THEN 'REQUIRED'
           ELSE 'IGNORED'
         END AS cls
    FROM sys_backup_quiesce_instance i
)
SELECT 'VERDICT',
       CASE
         WHEN k.n_active > 0 THEN 'FAIL_ACTIVE_REQUEST'
         WHEN k.n_disabled > 0 THEN 'FAIL_DISABLED'
         WHEN k.n_stale > 0 THEN 'FAIL_STALE'
         WHEN k.n_required = 0 THEN 'FAIL_NO_INSTANCE'
         WHEN k.n_protocol > 0 THEN 'FAIL_PROTOCOL'
         WHEN k.n_threshold > 0 THEN 'FAIL_THRESHOLD'
         ELSE 'OK'
       END,
       DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%sZ'),
       TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(6)),
       k.n_required,
       CONCAT('active=', k.n_active, ',disabled=', k.n_disabled, ',stale=', k.n_stale, ',required=', k.n_required,
              ',protocol=', k.n_protocol, ',threshold=', k.n_threshold)
  FROM (
    SELECT (SELECT COUNT(*) FROM sys_backup_quiesce_request q WHERE q.status = 'REQUESTED') AS n_active,
           COALESCE(SUM(c.cls = 'DISABLED'), 0) AS n_disabled,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.fresh = 0), 0) AS n_stale,
           COALESCE(SUM(c.cls = 'REQUIRED'), 0) AS n_required,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.protocol_version <> @p_protocol_version), 0) AS n_protocol,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.thresholds_ok = 0), 0) AS n_threshold
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
             THEN 'REQUIRED'
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
