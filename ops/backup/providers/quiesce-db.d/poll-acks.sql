-- ses-backup:quiesce-db:poll-acks
-- ONLINE の acquire の待ち（design.md §7.1 の 5・6）。毎回新しい transaction で instance を分類し直し（§5.3）、必要な全
-- instance（1 つ以上）の ack を読む。必要: STOPPED でなく、heartbeat が停止済みの閾値の内か要求の後に heartbeat を書いたもの。
-- 停止口の行（STOPPED でない DISABLED）は heartbeat の古さによらず失敗にする（D3-P2-3）。
-- 引数（検査済みの整数）: @p_request_id・@p_stale_seconds・@p_dead_seconds・@p_protocol_version・@p_lease_seconds・
--   @p_renew_seconds・@p_margin_seconds・@p_full（full なら 1）
-- 結果: VERDICT <OK | WAIT | FAIL_理由> <UTC の今> <今の epoch の μ秒> <必要な instance の数> <内訳>、
--   INSTANCE <instance_id> <分類> <新しい 1/0> <要求の前から 1/0> <ack の状態> <拒否の理由> <host 名> <接続元>
--   <started_at> <acked_at> <hold_until> <観測した接続元> <verify の後の観測 1/0> <最大保持時間 ms> <heartbeat の古さ ms>
--   <instance の状態> <gate の状態>
-- OK のときの「今」を整合時刻（quiesce.json の started_at_utc）にする。時刻は UTC_TIMESTAMP(6) だけで書き・比べる（§3.10）。
START TRANSACTION READ ONLY;
WITH r AS (
  SELECT q.id, q.status, q.release_reason, q.requested_at, q.verify_requested_at,
         CASE WHEN q.lease_expires_at > UTC_TIMESTAMP(6) THEN 1 ELSE 0 END AS lease_valid
    FROM sys_backup_quiesce_request q
   WHERE q.id = @p_request_id
),
c AS (
  SELECT i.instance_id, i.protocol_version,
         CASE WHEN TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_stale_seconds * 1000000
              THEN 1 ELSE 0 END AS fresh,
         CASE WHEN @p_stale_seconds * 1000 >= i.fence_after_ms + 2 * i.heartbeat_interval_ms
                   AND i.fence_after_ms + i.poll_interval_ms < (@p_lease_seconds - @p_renew_seconds) * 1000
              THEN 1 ELSE 0 END AS thresholds_ok,
         CASE
           WHEN i.status <> 'STOPPED' AND i.gate_state = 'DISABLED' THEN 'DISABLED'
           WHEN i.status <> 'STOPPED'
                AND (TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_dead_seconds * 1000000
                     OR i.last_beat_at >= r.requested_at)
             THEN 'REQUIRED'
           ELSE 'IGNORED'
         END AS cls,
         a.state AS ack_state, a.scheduler_state, a.hold_until
    FROM sys_backup_quiesce_instance i
   CROSS JOIN r
    LEFT JOIN sys_backup_quiesce_ack a ON a.request_id = r.id AND a.instance_id = i.instance_id
)
SELECT 'VERDICT',
       CASE
         WHEN k.n_request = 0 OR k.r_status <> 'REQUESTED' THEN 'FAIL_REQUEST_GONE'
         WHEN k.r_lease_valid = 0 THEN 'FAIL_LEASE_EXPIRED'
         WHEN k.n_disabled > 0 THEN 'FAIL_DISABLED'
         WHEN k.n_refused > 0 THEN 'FAIL_REFUSED'
         WHEN k.n_resumed > 0 THEN 'FAIL_RESUMED'
         WHEN k.n_stale > 0 THEN 'FAIL_STALE'
         WHEN k.n_required = 0 THEN 'FAIL_NO_INSTANCE'
         WHEN k.n_protocol > 0 THEN 'FAIL_PROTOCOL'
         WHEN k.n_threshold > 0 THEN 'FAIL_THRESHOLD'
         WHEN k.n_scheduler > 0 THEN 'FAIL_SCHEDULER'
         WHEN k.n_pending > 0 THEN 'WAIT'
         WHEN k.n_hold > 0 THEN 'FAIL_HOLD_UNTIL'
         ELSE 'OK'
       END,
       DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%sZ'),
       TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(6)),
       k.n_required,
       CONCAT('status=', COALESCE(k.r_status, '-'), ',reason=', COALESCE(k.r_reason, '-'),
              ',required=', k.n_required, ',pending=', k.n_pending, ',disabled=', k.n_disabled,
              ',refused=', k.n_refused, ',resumed=', k.n_resumed, ',stale=', k.n_stale, ',protocol=', k.n_protocol,
              ',threshold=', k.n_threshold, ',scheduler=', k.n_scheduler, ',hold=', k.n_hold)
  FROM (
    SELECT (SELECT COUNT(*) FROM r) AS n_request,
           (SELECT MAX(r.status) FROM r) AS r_status,
           (SELECT MAX(r.release_reason) FROM r) AS r_reason,
           (SELECT MAX(r.lease_valid) FROM r) AS r_lease_valid,
           COALESCE(SUM(c.cls = 'DISABLED'), 0) AS n_disabled,
           COALESCE(SUM(c.cls = 'REQUIRED'), 0) AS n_required,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.ack_state = 'REFUSED'), 0) AS n_refused,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.ack_state = 'RESUMED'), 0) AS n_resumed,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.ack_state IS NULL AND c.fresh = 0), 0) AS n_stale,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.ack_state IS NULL), 0) AS n_pending,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.protocol_version <> @p_protocol_version), 0) AS n_protocol,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.thresholds_ok = 0), 0) AS n_threshold,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.ack_state = 'QUIESCED'
                        AND c.scheduler_state NOT IN ('PAUSED', 'DISABLED')), 0) AS n_scheduler,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.ack_state = 'QUIESCED'
                        AND (c.hold_until IS NULL
                             OR TIMESTAMPDIFF(MICROSECOND, UTC_TIMESTAMP(6), c.hold_until)
                                < @p_margin_seconds * 1000000)), 0) AS n_hold
      FROM c
  ) k;
WITH r AS (
  SELECT q.id, q.requested_at, q.verify_requested_at
    FROM sys_backup_quiesce_request q
   WHERE q.id = @p_request_id
),
c AS (
  SELECT i.instance_id, i.host_name, i.db_client_host, i.status, i.gate_state, i.started_at,
         TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) AS age_us,
         CASE WHEN TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_stale_seconds * 1000000
              THEN 1 ELSE 0 END AS fresh,
         CASE WHEN i.started_at < r.requested_at THEN 1 ELSE 0 END AS before_request,
         CASE WHEN @p_full = 1 THEN i.max_hold_full_ms ELSE i.max_hold_checkpoint_ms END AS max_hold_ms,
         CASE
           WHEN i.status <> 'STOPPED' AND i.gate_state = 'DISABLED' THEN 'DISABLED'
           WHEN i.status <> 'STOPPED'
                AND (TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_dead_seconds * 1000000
                     OR i.last_beat_at >= r.requested_at)
             THEN 'REQUIRED'
           ELSE 'IGNORED'
         END AS cls,
         a.state AS ack_state, a.refuse_reason, a.acked_at, a.hold_until, a.observed_app_hosts,
         CASE WHEN r.verify_requested_at IS NOT NULL AND a.observed_at > r.verify_requested_at
              THEN 1 ELSE 0 END AS observed_after_verify
    FROM sys_backup_quiesce_instance i
   CROSS JOIN r
    LEFT JOIN sys_backup_quiesce_ack a ON a.request_id = r.id AND a.instance_id = i.instance_id
)
SELECT 'INSTANCE', c.instance_id, c.cls, c.fresh, c.before_request,
       COALESCE(c.ack_state, '-'), COALESCE(c.refuse_reason, '-'),
       COALESCE(NULLIF(c.host_name, ''), '-'), COALESCE(NULLIF(c.db_client_host, ''), '-'),
       DATE_FORMAT(c.started_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
       COALESCE(DATE_FORMAT(c.acked_at, '%Y-%m-%dT%H:%i:%s.%fZ'), '-'),
       COALESCE(DATE_FORMAT(c.hold_until, '%Y-%m-%dT%H:%i:%s.%fZ'), '-'),
       COALESCE(NULLIF(c.observed_app_hosts, ''), '-'),
       c.observed_after_verify, c.max_hold_ms, c.age_us DIV 1000, c.status, c.gate_state
  FROM c
 ORDER BY c.started_at, c.instance_id;
COMMIT;
