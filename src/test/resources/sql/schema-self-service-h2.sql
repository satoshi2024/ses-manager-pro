-- テスト用(冪等): V32__engineer_self_service.sql の2テーブル追加相当を共有インメモリH2へ適用する。
-- role/status は H2 では VARCHAR のため ENUM 拡張のスキーマ変更は不要。
CREATE TABLE IF NOT EXISTS t_engineer_account_link (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id BIGINT NOT NULL UNIQUE,
  sys_user_id BIGINT NOT NULL UNIQUE,
  linked_by   BIGINT,
  linked_at   DATETIME DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS t_work_record_daily (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_record_id BIGINT NOT NULL,
  work_date      DATE NOT NULL,
  start_time     TIME,
  end_time       TIME,
  break_minutes  INT NOT NULL DEFAULT 0,
  worked_hours   DECIMAL(6,2) NOT NULL,
  remarks        VARCHAR(200),
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_wr_daily (work_record_id, work_date)
);

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
  SELECT 'my-timesheet', 'マイ勤怠', '/my', '/api/my', 92
  WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'my-timesheet');

-- V36/V37 相当: 通知宛先とAND勤怠差戻しコメント（MySQL migrationと同一構造へ同期）
ALTER TABLE t_notification ADD COLUMN IF NOT EXISTS recipient_user_id BIGINT;
ALTER TABLE t_work_record ADD COLUMN IF NOT EXISTS reject_comment VARCHAR(500);
