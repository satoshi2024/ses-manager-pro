-- ライフサイクル案件番号をtenant・月単位のDB行ロックで採番する。
CREATE TABLE t_lifecycle_case_sequence (
    tenant_id VARCHAR(100) NOT NULL,
    case_month CHAR(6) NOT NULL,
    last_number BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, case_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='ライフサイクル案件番号のtenant・月次採番';
