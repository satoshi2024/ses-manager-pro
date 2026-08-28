-- schedule claim後のcrashで当月実行が欠落しないよう、processing leaseを追加する。
ALTER TABLE m_report_schedule
    ADD COLUMN processing_logical_run_at DATETIME NULL COMMENT 'claim中の論理実行時刻' AFTER retry_scheduled_at,
    ADD COLUMN processing_claimed_at DATETIME NULL COMMENT 'claim取得日時（lease）' AFTER processing_logical_run_at;

CREATE INDEX idx_report_schedule_processing ON m_report_schedule (enabled, processing_claimed_at, deleted_flag);
