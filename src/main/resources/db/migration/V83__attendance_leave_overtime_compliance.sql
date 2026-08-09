-- ============================================================
-- V83: 雇用勤怠・休暇・時間外コンプライアンス (S11 / T068)
-- V82 dispatchの後に適用する。V59は永久欠番であり、前の欠番は埋めない。
-- V1統合baselineと同じ最終shapeを、V81適用済みlegacy DBへも追加する。
-- ============================================================

-- V1へ統合済みの列を、V81適用済みlegacy DBへも順方向に追加する。
SET @attendance_exempt_sql = IF(
    (SELECT COUNT(*) FROM information_schema.columns
      WHERE table_schema = DATABASE() AND table_name = 't_engineer'
        AND column_name = 'overtime_exempt_flag') = 0,
    'ALTER TABLE t_engineer ADD COLUMN overtime_exempt_flag TINYINT NULL COMMENT ''時間外上限の適用除外フラグ（NULL=HR未確認、確定値のみ設定）'' AFTER organization_id',
    'SELECT 1'
);
PREPARE attendance_exempt_stmt FROM @attendance_exempt_sql;
EXECUTE attendance_exempt_stmt;
DEALLOCATE PREPARE attendance_exempt_stmt;

SET @attendance_exempt_index_sql = IF(
    (SELECT COUNT(*) FROM information_schema.statistics
      WHERE table_schema = DATABASE() AND table_name = 't_engineer'
        AND index_name = 'idx_engineer_overtime_exempt') = 0,
    'ALTER TABLE t_engineer ADD INDEX idx_engineer_overtime_exempt (overtime_exempt_flag)',
    'SELECT 1'
);
PREPARE attendance_exempt_index_stmt FROM @attendance_exempt_index_sql;
EXECUTE attendance_exempt_index_stmt;
DEALLOCATE PREPARE attendance_exempt_index_stmt;

CREATE TABLE IF NOT EXISTS m_work_calendar (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  legal_entity_id BIGINT,
  organization_id BIGINT,
  engineer_id BIGINT,
  name VARCHAR(200) NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE,
  status VARCHAR(20) NOT NULL DEFAULT '有効',
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  INDEX idx_work_calendar_scope (legal_entity_id, organization_id, engineer_id, valid_from, valid_to),
  INDEX idx_work_calendar_period (valid_from, valid_to),
  CONSTRAINT chk_work_calendar_period CHECK (valid_to IS NULL OR valid_from <= valid_to),
  CONSTRAINT fk_work_calendar_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_work_calendar_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS m_work_calendar_day (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  calendar_id BIGINT NOT NULL,
  calendar_date DATE NOT NULL,
  day_type VARCHAR(30) NOT NULL,
  scheduled_minutes INT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_work_calendar_day (calendar_id, calendar_date),
  INDEX idx_work_calendar_day_date (calendar_date),
  CONSTRAINT chk_work_calendar_day_minutes CHECK (scheduled_minutes IS NULL OR scheduled_minutes >= 0),
  CONSTRAINT fk_work_calendar_day_calendar FOREIGN KEY (calendar_id) REFERENCES m_work_calendar(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_employee_attendance (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  legal_entity_id BIGINT,
  organization_id BIGINT,
  work_calendar_id BIGINT,
  work_date DATE NOT NULL,
  clock_in TIME,
  clock_out TIME,
  break_minutes INT NOT NULL DEFAULT 0,
  regular_minutes INT NOT NULL DEFAULT 0,
  overtime_minutes INT NOT NULL DEFAULT 0,
  holiday_minutes INT NOT NULL DEFAULT 0,
  late_night_minutes INT NOT NULL DEFAULT 0,
  work_type VARCHAR(30) NOT NULL DEFAULT '通常',
  workplace_type VARCHAR(30),
  source VARCHAR(20) NOT NULL DEFAULT 'manual',
  source_external_id VARCHAR(200),
  status VARCHAR(20) NOT NULL DEFAULT '入力中',
  remarks VARCHAR(500),
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_employee_attendance_source (source, source_external_id),
  INDEX idx_employee_attendance_engineer_date (engineer_id, work_date),
  INDEX idx_employee_attendance_month (work_date, engineer_id),
  INDEX idx_employee_attendance_scope (legal_entity_id, organization_id, work_date),
  CONSTRAINT chk_employee_attendance_source CHECK (
    (source IN ('manual', 'system') AND source_external_id IS NULL)
    OR (source IN ('freee', 'import') AND source_external_id IS NOT NULL)
  ),
  CONSTRAINT chk_employee_attendance_minutes CHECK (
    break_minutes >= 0 AND regular_minutes >= 0 AND overtime_minutes >= 0
    AND holiday_minutes >= 0 AND late_night_minutes >= 0
  ),
  CONSTRAINT fk_employee_attendance_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_employee_attendance_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_employee_attendance_calendar FOREIGN KEY (work_calendar_id) REFERENCES m_work_calendar(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_attendance_month (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  legal_entity_id BIGINT,
  organization_id BIGINT,
  work_month DATE NOT NULL,
  scheduled_minutes INT NOT NULL DEFAULT 0,
  worked_minutes INT NOT NULL DEFAULT 0,
  regular_minutes INT NOT NULL DEFAULT 0,
  overtime_minutes INT NOT NULL DEFAULT 0,
  holiday_minutes INT NOT NULL DEFAULT 0,
  late_night_minutes INT NOT NULL DEFAULT 0,
  leave_minutes INT NOT NULL DEFAULT 0,
  status VARCHAR(20) NOT NULL DEFAULT '入力中',
  submitted_at DATETIME,
  submitted_by BIGINT,
  approved_at DATETIME,
  approved_by BIGINT,
  closed_at DATETIME,
  closed_by BIGINT,
  close_reason VARCHAR(500),
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_attendance_month_engineer (engineer_id, work_month),
  INDEX idx_attendance_month_scope (legal_entity_id, organization_id, work_month),
  CONSTRAINT chk_attendance_month_month_start CHECK (DAYOFMONTH(work_month) = 1),
  CONSTRAINT chk_attendance_month_minutes CHECK (
    scheduled_minutes >= 0 AND worked_minutes >= 0 AND regular_minutes >= 0
    AND overtime_minutes >= 0 AND holiday_minutes >= 0 AND late_night_minutes >= 0
    AND leave_minutes >= 0
  ),
  CONSTRAINT fk_attendance_month_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_attendance_month_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_leave_request (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  legal_entity_id BIGINT,
  organization_id BIGINT,
  leave_type VARCHAR(30) NOT NULL,
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  start_time TIME,
  end_time TIME,
  requested_minutes INT NOT NULL,
  reason VARCHAR(500),
  status VARCHAR(20) NOT NULL DEFAULT '申請中',
  approval_request_id BIGINT,
  version INT NOT NULL DEFAULT 0,
  created_by BIGINT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  INDEX idx_leave_request_engineer_period (engineer_id, start_date, end_date),
  INDEX idx_leave_request_status (status),
  INDEX idx_leave_request_approval (approval_request_id),
  CONSTRAINT chk_leave_request_period CHECK (start_date <= end_date),
  CONSTRAINT chk_leave_request_minutes CHECK (requested_minutes > 0),
  CONSTRAINT fk_leave_request_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT,
  CONSTRAINT fk_leave_request_organization FOREIGN KEY (organization_id) REFERENCES m_organization_unit(id)
    ON UPDATE CASCADE ON DELETE SET NULL,
  CONSTRAINT fk_leave_request_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id)
    ON UPDATE CASCADE ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS m_overtime_agreement (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  legal_entity_id BIGINT NOT NULL,
  valid_from DATE NOT NULL,
  valid_to DATE,
  special_clause TINYINT NOT NULL DEFAULT 0,
  normal_month_limit_minutes INT,
  normal_year_limit_minutes INT,
  special_year_limit_minutes INT,
  total_month_limit_minutes INT,
  multi_month_average_limit_minutes INT,
  exceed_month_count_limit INT,
  warning_threshold_percent INT,
  warning_recipients VARCHAR(100),
  config_json TEXT,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_overtime_agreement_period (legal_entity_id, valid_from),
  INDEX idx_overtime_agreement_lookup (legal_entity_id, valid_from, valid_to),
  CONSTRAINT chk_overtime_agreement_month_start CHECK (DAYOFMONTH(valid_from) = 1),
  CONSTRAINT chk_overtime_agreement_period CHECK (valid_to IS NULL OR valid_from <= valid_to),
  CONSTRAINT chk_overtime_agreement_limits CHECK (
    (normal_month_limit_minutes IS NULL OR normal_month_limit_minutes >= 0)
    AND (normal_year_limit_minutes IS NULL OR normal_year_limit_minutes >= 0)
    AND (special_year_limit_minutes IS NULL OR special_year_limit_minutes >= 0)
    AND (total_month_limit_minutes IS NULL OR total_month_limit_minutes >= 0)
    AND (multi_month_average_limit_minutes IS NULL OR multi_month_average_limit_minutes >= 0)
    AND (exceed_month_count_limit IS NULL OR exceed_month_count_limit >= 0)
    AND (warning_threshold_percent IS NULL OR warning_threshold_percent BETWEEN 0 AND 100)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_overtime_followup (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  warning_code VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT '未対応',
  notified_at DATETIME,
  health_action_status VARCHAR(30),
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_overtime_followup (engineer_id, period_month, warning_code),
  INDEX idx_overtime_followup_period (period_month, status),
  CONSTRAINT chk_overtime_followup_month_start CHECK (DAYOFMONTH(period_month) = 1),
  CONSTRAINT fk_overtime_followup_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
    ON UPDATE CASCADE ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 値は分単位の整数。法人別協定が無い場合はcalculatorがfinding/UNKNOWNとし、
-- configだけで適合を確定しない。INSERT IGNOREで既存の管理者変更を上書きしない。
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('overtime.limit.month-normal', '2700', '月時間外上限（分）'),
  ('overtime.limit.year-normal', '21600', '年時間外上限（分）'),
  ('overtime.limit.year-special', '43200', '特別条項年時間外上限（分）'),
  ('overtime.limit.month-total', '6000', '月合計上限（分、100時間未満のため境界はcalculatorで>=）'),
  ('overtime.limit.multi-month-average', '4800', '複数月平均上限（分）'),
  ('overtime.limit.exceed-month-count', '6', '45時間超過月数上限（回）'),
  ('overtime.prorate-partial-month', 'false', '月中入社・退職の按分有無'),
  ('overtime.warning.threshold-percent', '80', '予兆警告閾値（%）'),
  ('overtime.warning.recipients', 'self,manager,hr', '時間外警告の通知先');
