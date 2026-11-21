-- ses-backup:quiesce-db:preflight
-- preflight の checks.quiesce（design.md §12.4。読むだけ）。信号表の 3 表があること、backup account の権限（provider が
-- 要求の表へ表単位の INSERT・UPDATE を持つことは preflight.sh が SHOW GRANTS の行で確かめる）、停止口の行（STOPPED でない
-- DISABLED。heartbeat の古さによらない）が無いこと、新しい instance の protocol の版と閾値の式が provider の設定と合うこと。
-- 表が無い時は最後の 2 つの文が失敗する（TABLES の件数で判定する）。
-- 引数（検査済みの整数）: @p_stale_seconds・@p_dead_seconds・@p_protocol_version・@p_lease_seconds・@p_renew_seconds・@p_full
-- 結果: TABLES <件数>、GRANTS_BEGIN、SHOW GRANTS の行、GRANTS_END、VERDICT <OK | FAIL_理由> …、INSTANCE の行
SELECT 'TABLES', COUNT(*)
  FROM information_schema.tables
 WHERE table_schema = DATABASE()
   AND table_name IN ('sys_backup_quiesce_request', 'sys_backup_quiesce_instance', 'sys_backup_quiesce_ack');
SELECT 'GRANTS_BEGIN';
SHOW GRANTS FOR CURRENT_USER();
SELECT 'GRANTS_END';
WITH c AS (
  SELECT i.instance_id, i.protocol_version,
         CASE WHEN TIMESTAMPDIFF(MICROSECOND, i.last_beat_at, UTC_TIMESTAMP(6)) <= @p_stale_seconds * 1000000
              THEN 1 ELSE 0 END AS fresh,
         CASE WHEN @p_stale_seconds * 1000 >= i.fence_after_ms + 2 * i.heartbeat_interval_ms
                   AND i.fence_after_ms + i.poll_interval_ms < (@p_lease_seconds - @p_renew_seconds) * 1000
              THEN 1 ELSE 0 END AS thresholds_ok,
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
         WHEN k.n_disabled > 0 THEN 'FAIL_DISABLED'
         WHEN k.n_protocol > 0 THEN 'FAIL_PROTOCOL'
         WHEN k.n_threshold > 0 THEN 'FAIL_THRESHOLD'
         ELSE 'OK'
       END,
       DATE_FORMAT(UTC_TIMESTAMP(6), '%Y-%m-%dT%H:%i:%sZ'),
       TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', UTC_TIMESTAMP(6)),
       k.n_fresh,
       CONCAT('disabled=', k.n_disabled, ',fresh=', k.n_fresh, ',protocol=', k.n_protocol,
              ',threshold=', k.n_threshold)
  FROM (
    SELECT COALESCE(SUM(c.cls = 'DISABLED'), 0) AS n_disabled,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.fresh = 1), 0) AS n_fresh,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.fresh = 1 AND c.protocol_version <> @p_protocol_version), 0)
             AS n_protocol,
           COALESCE(SUM(c.cls = 'REQUIRED' AND c.fresh = 1 AND c.thresholds_ok = 0), 0) AS n_threshold
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
