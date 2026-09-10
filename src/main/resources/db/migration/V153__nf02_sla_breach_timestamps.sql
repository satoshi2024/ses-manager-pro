-- NF-02: SLA breach時刻はround作成時刻と別に追記する。
DROP PROCEDURE IF EXISTS __ses_nf02_add_sla_breach_time;
DELIMITER $$
CREATE PROCEDURE __ses_nf02_add_sla_breach_time(IN p_column VARCHAR(64), IN p_comment VARCHAR(255))
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 't_service_sla_clock'
       AND column_name = p_column
  ) THEN
    SET @nf02_sql = CONCAT('ALTER TABLE t_service_sla_clock ADD COLUMN ', p_column,
                           ' DATETIME NULL COMMENT ''', p_comment, '''');
    PREPARE nf02_stmt FROM @nf02_sql;
    EXECUTE nf02_stmt;
    DEALLOCATE PREPARE nf02_stmt;
  END IF;
END$$
DELIMITER ;

CALL __ses_nf02_add_sla_breach_time('response_breached_at', '初回応答超過を最初に検知した日時');
CALL __ses_nf02_add_sla_breach_time('resolve_breached_at', '解決目標超過を最初に検知した日時');
DROP PROCEDURE IF EXISTS __ses_nf02_add_sla_breach_time;

INSERT INTO m_document_type (code, name, direction, retention_years, retention_start_rule, legal_hold_supported)
SELECT 'SERVICE_REQUEST_ATTACHMENT', 'サービスリクエスト添付', 'INCOMING', 7, 'TRANSACTION_DATE', 1
WHERE NOT EXISTS (SELECT 1 FROM m_document_type WHERE code = 'SERVICE_REQUEST_ATTACHMENT');

SELECT 1;
