-- ============================================================
-- V113: 定期管理レポートのsection attempt監査とoutbox状態連携
--
-- sectionの現在値は読み取り用の最新状態として残し、各generation attemptは追記型で保存する。
-- notification outboxの実配送結果はdeliveryへ戻し、enqueue直後をSENTと扱わない。
-- ============================================================

CREATE TABLE IF NOT EXISTS t_report_section_attempt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'section attempt ID',
    tenant_id VARCHAR(64) NOT NULL DEFAULT 'default' COMMENT 'テナントID',
    run_id BIGINT NOT NULL COMMENT 't_report_run.id',
    section_key VARCHAR(100) NOT NULL COMMENT 'section key',
    attempt_no INT NOT NULL COMMENT 'run内section試行番号',
    section_status VARCHAR(30) NOT NULL COMMENT 'SUCCEEDED/FAILED',
    fact_type VARCHAR(20) NULL COMMENT '実績/予測',
    confirmation VARCHAR(20) NULL COMMENT '確定/速報',
    period_from DATE NULL COMMENT '対象期間開始',
    period_to DATE NULL COMMENT '対象期間終了',
    cutoff_kind VARCHAR(30) NULL COMMENT 'cutoff種別',
    started_at DATETIME NOT NULL COMMENT 'attempt開始日時',
    finished_at DATETIME NOT NULL COMMENT 'attempt終了日時',
    data_as_of_at DATETIME NULL COMMENT 'データ基準時刻',
    freshness_status VARCHAR(20) NULL COMMENT 'FRESH/STALE/UNKNOWN',
    canonical_service VARCHAR(200) NULL COMMENT '正本service',
    canonical_dto VARCHAR(200) NULL COMMENT '正本DTO',
    source_row_count BIGINT NULL COMMENT '参照行数',
    source_hash VARCHAR(128) NULL COMMENT '正本入力hash',
    value_json LONGTEXT NULL COMMENT 'attempt value JSON',
    error_code VARCHAR(100) NULL COMMENT '安全化済み失敗分類',
    error_message VARCHAR(500) NULL COMMENT '安全化済み失敗message',
    snapshot_hash VARCHAR(128) NULL COMMENT 'attempt snapshot hash',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
    deleted_flag TINYINT NOT NULL DEFAULT 0 COMMENT '論理削除',
    version INT NOT NULL DEFAULT 0 COMMENT '楽観ロック',
    INDEX idx_report_section_attempt_run (run_id, section_key, attempt_no),
    INDEX idx_report_section_attempt_status (tenant_id, section_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='管理レポートsection試行監査';

ALTER TABLE t_report_delivery
    ADD COLUMN notification_outbox_id BIGINT NULL COMMENT '通知outbox ID' AFTER document_version_no,
    ADD INDEX idx_report_delivery_outbox (notification_outbox_id);
