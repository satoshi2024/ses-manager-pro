-- 雇用勤怠・休暇・時間外コンプライアンスのH2 replay shape（V83対応）。
-- H2ではMySQLのENGINE/FK方言を避け、制約と列契約を直接検証する。
SET REFERENTIAL_INTEGRITY FALSE;
DROP TABLE IF EXISTS t_overtime_followup CASCADE;
DROP TABLE IF EXISTS m_overtime_agreement CASCADE;
DROP TABLE IF EXISTS t_leave_request CASCADE;
DROP TABLE IF EXISTS t_attendance_month CASCADE;
DROP TABLE IF EXISTS t_employee_attendance CASCADE;
DROP TABLE IF EXISTS m_work_calendar_day CASCADE;
DROP TABLE IF EXISTS m_work_calendar CASCADE;

CREATE TABLE m_work_calendar (
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
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_work_calendar_period CHECK (valid_to IS NULL OR valid_from <= valid_to)
);

CREATE TABLE m_work_calendar_day (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  calendar_id BIGINT NOT NULL,
  calendar_date DATE NOT NULL,
  day_type VARCHAR(30) NOT NULL,
  scheduled_minutes INT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT uk_work_calendar_day UNIQUE (calendar_id, calendar_date),
  CONSTRAINT chk_work_calendar_day_minutes CHECK (scheduled_minutes IS NULL OR scheduled_minutes >= 0)
);

CREATE TABLE t_employee_attendance (
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
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_employee_attendance_source UNIQUE (source, source_external_id),
  CONSTRAINT chk_employee_attendance_source CHECK (
    (source IN ('manual', 'system') AND source_external_id IS NULL)
    OR (source IN ('freee', 'import') AND source_external_id IS NOT NULL)
  ),
  CONSTRAINT chk_employee_attendance_minutes CHECK (
    break_minutes >= 0 AND regular_minutes >= 0 AND overtime_minutes >= 0
    AND holiday_minutes >= 0 AND late_night_minutes >= 0
  )
);

CREATE TABLE t_attendance_month (
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
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_attendance_month_engineer UNIQUE (engineer_id, work_month),
  CONSTRAINT chk_attendance_month_month_start CHECK (DAYOFMONTH(work_month) = 1),
  CONSTRAINT chk_attendance_month_minutes CHECK (
    scheduled_minutes >= 0 AND worked_minutes >= 0 AND regular_minutes >= 0
    AND overtime_minutes >= 0 AND holiday_minutes >= 0 AND late_night_minutes >= 0
    AND leave_minutes >= 0
  )
);

CREATE TABLE t_leave_request (
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
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_leave_request_period CHECK (start_date <= end_date),
  CONSTRAINT chk_leave_request_minutes CHECK (requested_minutes > 0)
);

CREATE TABLE m_overtime_agreement (
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
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_overtime_agreement_period UNIQUE (legal_entity_id, valid_from),
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
);

CREATE TABLE t_overtime_followup (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL,
  period_month DATE NOT NULL,
  warning_code VARCHAR(50) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT '未対応',
  notified_at DATETIME,
  health_action_status VARCHAR(30),
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted_flag TINYINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_overtime_followup UNIQUE (engineer_id, period_month, warning_code),
  CONSTRAINT chk_overtime_followup_month_start CHECK (DAYOFMONTH(period_month) = 1)
);

INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('overtime.limit.month-normal', '2700', '月時間外上限（分）'),
  ('overtime.limit.year-normal', '21600', '年時間外上限（分）'),
  ('overtime.limit.year-special', '43200', '特別条項年時間外上限（分）'),
  ('overtime.limit.month-total', '6000', '月合計上限（分）'),
  ('overtime.limit.multi-month-average', '4800', '複数月平均上限（分）'),
  ('overtime.limit.exceed-month-count', '6', '45時間超過月数上限（回）'),
  ('overtime.prorate-partial-month', 'false', '月中入社・退職の按分有無'),
  ('overtime.warning.threshold-percent', '80', '予兆警告閾値（%）'),
  ('overtime.warning.recipients', 'self,manager,hr', '時間外警告の通知先');

SET REFERENTIAL_INTEGRITY TRUE;
