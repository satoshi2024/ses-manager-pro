-- ses-backup:quiesce-db:stopped-check
-- app の停止を確かめる backup（APP_STOPPED）の保持の登録の後の分類し直し（design.md §9.2 の 4）と verify。新しい transaction で、
-- STOPPED でなく（heartbeat が停止済みの閾値の内、または保持の要求の後に heartbeat を書いた）instance（ALIVE）か、STOPPED でない
-- 停止口の行（DISABLED。heartbeat の古さによらない。D3-P2-3）が 1 つでもあれば失敗にする。保持の間に起動しようとした instance は、
-- 登録して保持を読み、起動を中止して STOPPED を書くため数えない（D3-P2-2 の負例。STOPPED は業務の pool を閉じた後にだけ書かれる）。
-- 引数（検査済みの整数）: @p_request_id・@p_stale_seconds・@p_dead_seconds・@p_full（full なら 1）
-- 結果: VERDICT <OK | FAIL_理由> <UTC の今> <今の epoch の μ秒> <停止していない instance の数> <内訳>、INSTANCE の行
START TRANSACTION READ ONLY;
WITH r AS (
  SELECT q.id, q.status, q.release_reason, q.mode, q.requested_at,
         CASE WHEN q.lease_expires_at > UTC_TIMESTAMP(6) THEN 1 ELSE 0 END AS lease_valid
    FROM sys_backup_quiesce_request q
   WHERE q.id = @p_request_id
),
c AS (
  SELECT i.instance_id,
         CASE
           WHEN i.status <> 'STOPPED' AND i.gate_state = 'DISABLED' THEN 'DISABLED'
           WHEN i.status <> 'STOPPED'
                AND (TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_dead_seconds * 1000000
                     OR i.last_beat_at >= r.requested_at)
             THEN 'ALIVE'
           ELSE 'IGNORED'
         END AS cls
    FROM sys_backup_quiesce_instance i
   CROSS JOIN r
)
SELECT 'VERDICT',
       CASE
         WHEN k.n_request = 0 OR k.r_status <> 'REQUESTED' THEN 'FAIL_REQUEST_GONE'
         WHEN k.r_mode <> 'APP_STOPPED' THEN 'FAIL_MODE'
         WHEN k.r_lease_valid = 0 THEN 'FAIL_LEASE_EXPIRED'
         WHEN k.n_disabled > 0 THEN 'FAIL_DISABLED'
         WHEN k.n_alive > 0 THEN 'FAIL_ALIVE'
         ELSE 'OK'
       END,
       DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%sZ'),
       TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(6)),
       k.n_alive + k.n_disabled,
       CONCAT('status=', COALESCE(k.r_status, '-'), ',reason=', COALESCE(k.r_reason, '-'), ',disabled=', k.n_disabled,
              ',alive=', k.n_alive)
  FROM (
    SELECT (SELECT COUNT(*) FROM r) AS n_request,
           (SELECT MAX(r.status) FROM r) AS r_status,
           (SELECT MAX(r.release_reason) FROM r) AS r_reason,
           (SELECT MAX(r.mode) FROM r) AS r_mode,
           (SELECT MAX(r.lease_valid) FROM r) AS r_lease_valid,
           COALESCE(SUM(c.cls = 'DISABLED'), 0) AS n_disabled,
           COALESCE(SUM(c.cls = 'ALIVE'), 0) AS n_alive
      FROM c
  ) k;
WITH r AS (
  SELECT q.id, q.requested_at
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
             THEN 'ALIVE'
           ELSE 'IGNORED'
         END AS cls
    FROM sys_backup_quiesce_instance i
   CROSS JOIN r
)
SELECT 'INSTANCE', c.instance_id, c.cls, c.fresh, c.before_request, '-', '-',
       COALESCE(NULLIF(c.host_name, ''), '-'), COALESCE(NULLIF(c.db_client_host, ''), '-'),
       DATE_FORMAT(c.started_at, '%Y-%m-%dT%H:%i:%s.%fZ'), '-', '-', '-', 0, c.max_hold_ms, c.age_us DIV 1000,
       c.status, c.gate_state
  FROM c
 ORDER BY c.started_at, c.instance_id;
COMMIT;
