-- ============================================================
-- SES Manager Pro - 作業報告書と要員セルフサービス勤怠（engineer-self-service-timesheet / P1）
-- ファイル: V32__engineer_self_service.sql
-- 説明: 要員ロール追加・勤怠状態拡張・要員アカウント紐付け・日次勤怠テーブル・マイ勤怠メニュー。
-- ============================================================

-- ロールに「要員」を追加（既存値の並びは変えない）。
ALTER TABLE sys_user MODIFY role ENUM('管理者','営業','HR','マネージャー','要員') NOT NULL COMMENT 'ロール';

-- 勤怠状態に「提出済」「差戻し」を追加（値追加のみ・既存値の並びは不変）。
ALTER TABLE t_work_record MODIFY status ENUM('入力中','提出済','差戻し','確定') DEFAULT '入力中';

CREATE TABLE t_engineer_account_link (
  id           BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id  BIGINT NOT NULL UNIQUE,
  sys_user_id  BIGINT NOT NULL UNIQUE,
  linked_by    BIGINT,
  linked_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  FOREIGN KEY (engineer_id) REFERENCES t_engineer(id),
  FOREIGN KEY (sys_user_id) REFERENCES sys_user(id)
) COMMENT='要員アカウント紐付け';

CREATE TABLE t_work_record_daily (
  id             BIGINT AUTO_INCREMENT PRIMARY KEY,
  work_record_id BIGINT NOT NULL,
  work_date      DATE NOT NULL,
  start_time     TIME NULL,
  end_time       TIME NULL,
  break_minutes  INT NOT NULL DEFAULT 0,
  worked_hours   DECIMAL(4,2) NOT NULL COMMENT '稼働時間(自動計算値を保存)',
  remarks        VARCHAR(200),
  created_at     DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at     DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uq_wr_daily (work_record_id, work_date),
  FOREIGN KEY (work_record_id) REFERENCES t_work_record(id) ON DELETE CASCADE
) COMMENT='日次勤怠';

INSERT IGNORE INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('my-timesheet', 'マイ勤怠', '/my', '/api/my', 92);

INSERT IGNORE INTO t_role_menu (role, menu_id)
SELECT '要員', m.id FROM m_menu m WHERE m.menu_key = 'my-timesheet';
