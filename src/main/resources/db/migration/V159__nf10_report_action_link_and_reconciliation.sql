-- NF10: report通知はraw tokenを保存せず、配布状態の再照合を永続化する。

SET @nf10_column_sql = (SELECT IF(COUNT(*) = 0,
    'ALTER TABLE t_notification_outbox ADD COLUMN reconciliation_required TINYINT NOT NULL DEFAULT 0 COMMENT ''delivery再照合要否''',
    'SELECT 1')
    FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 't_notification_outbox'
      AND column_name = 'reconciliation_required');
PREPARE nf10_column_stmt FROM @nf10_column_sql;
EXECUTE nf10_column_stmt;
DEALLOCATE PREPARE nf10_column_stmt;

-- 旧版で通知欄へ書かれたreport tokenは、hashを残したままaction URLへ縮退させる。
UPDATE t_notification
SET link_url = SUBSTRING_INDEX(link_url, '?', 1)
WHERE type = 'MANAGEMENT_REPORT'
  AND link_url LIKE '%?token=%';

UPDATE t_notification_outbox
SET link_url = SUBSTRING_INDEX(link_url, '?', 1)
WHERE type = 'MANAGEMENT_REPORT'
  AND link_url LIKE '%?token=%';

SELECT 1;
