-- ============================================================
-- V91: 休憩区間テーブル（S11方式A追補 / R2-P1-02 / 発注者V91割当）
-- V83は凍結のため編集しない。S10=V84・S12〜S17=V92〜V97へ繰り上げ後の
-- 未使用version V91として適用する。前の欠番は埋めない。
-- ============================================================
-- 方式A（設計決定表 §5.1.1）: 休憩は1勤務日に複数の開始・終了区間として保存し、
-- break_minutesは区間合計から導出する。区間は勤務開始を0とする整数分offsetで
-- 保存し、跨夜でも日付を曖昧にしない。重複・勤務区間外・開始≧終了・勤務時間
-- 全体超過はアプリケーション層（AttendanceCalculator）でfail-closedに拒否する。
-- DB制約はoffsetの基本契約（負でない、開始<終了）と親行との整合だけを持つ。
CREATE TABLE IF NOT EXISTS t_employee_attendance_break (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  attendance_id BIGINT NOT NULL COMMENT '雇用勤怠日次行ID',
  sequence_no INT NOT NULL COMMENT '開始offset昇順の区間番号（1始まり）',
  start_offset_minutes INT NOT NULL COMMENT '勤務開始を0とする休憩開始offset（分）',
  end_offset_minutes INT NOT NULL COMMENT '勤務開始を0とする休憩終了offset（分）',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新日時',
  UNIQUE KEY uk_employee_attendance_break (attendance_id, sequence_no),
  INDEX idx_employee_attendance_break_attendance (attendance_id),
  CONSTRAINT chk_employee_attendance_break_offset CHECK (
    start_offset_minutes >= 0 AND end_offset_minutes > start_offset_minutes
  ),
  CONSTRAINT fk_employee_attendance_break_attendance FOREIGN KEY (attendance_id)
    REFERENCES t_employee_attendance(id)
    ON UPDATE CASCADE ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='雇用勤怠日次の休憩区間（方式A）';
