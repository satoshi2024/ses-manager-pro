-- ses-backup:quiesce-db:insert-request
-- 静止要求の登録（design.md §7.1 の 4・§9.2 の 3）。autocommit の INSERT で commit してから番号を返す。
-- 有効な要求は 1 件だけ（生成列 active_slot の一意索引 uk_bq_request_active）。他の要求が有効なら 1062 で失敗する。
-- 引数: @p_token（provider が作る 16 byte の乱数の hex 32 桁）・@p_mode（ONLINE / APP_STOPPED）・
--   @p_job_kind（FULL / CHECKPOINT）・@p_lease_seconds（整数）・@p_requested_by_hex（requested_by の UTF-8 の hex）・
--   @p_protocol_version（整数）
-- 結果: REQUEST <番号> <requested_at（UTC）> <lease_expires_at（UTC）> <requested_at の epoch の μ秒>
INSERT INTO sys_backup_quiesce_request
       (request_token, mode, job_kind, status, lease_seconds, requested_by, protocol_version, requested_at,
        lease_expires_at)
VALUES (@p_token, @p_mode, @p_job_kind, 'REQUESTED', @p_lease_seconds,
        CONVERT(UNHEX(@p_requested_by_hex) USING utf8mb4), @p_protocol_version, UTC_TIMESTAMP(6),
        TIMESTAMPADD(SECOND, @p_lease_seconds, UTC_TIMESTAMP(6)));
SELECT 'REQUEST', q.id, DATE_FORMAT(q.requested_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
       DATE_FORMAT(q.lease_expires_at, '%Y-%m-%dT%H:%i:%s.%fZ'),
       TIMESTAMPDIFF(MICROSECOND, '1970-01-01 00:00:00', q.requested_at)
  FROM sys_backup_quiesce_request q
 WHERE q.id = LAST_INSERT_ID();
