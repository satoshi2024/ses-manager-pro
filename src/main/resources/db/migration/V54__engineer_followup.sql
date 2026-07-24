-- 要員フォロー・定着リスク管理（FR-11）
-- フォロー記録テーブル。next_date < 今日 の未実施フォローは NotificationGenerateService.followupOverdue()
-- が担当営業へ通知する（dedupe_key = FOLLOWUP_OVERDUE:{engineerId}:{nextDate}）。
CREATE TABLE t_engineer_followup (
  id            BIGINT AUTO_INCREMENT PRIMARY KEY,
  engineer_id   BIGINT NOT NULL,
  followup_type VARCHAR(20) NOT NULL COMMENT '1on1/面談/連絡',
  followup_date DATE NOT NULL,
  satisfaction  TINYINT NULL COMMENT '満足度 1-5',
  topic         VARCHAR(200) NULL,
  content       TEXT NULL,
  next_date     DATE NULL COMMENT '次回フォロー予定',
  created_by    BIGINT NULL,
  created_at    DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at    DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag  TINYINT NOT NULL DEFAULT 0,
  INDEX idx_ef_engineer (engineer_id),
  INDEX idx_ef_next (next_date),
  CONSTRAINT fk_ef_engineer FOREIGN KEY (engineer_id) REFERENCES t_engineer(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='要員フォロー記録';
-- メニュー不要（要員詳細内。api_prefix は engineer 配下 /api/engineers/{id}/followups を流用）

-- 定着リスクスコアの閾値設定（RetentionRiskService、/system-config 画面で編集可）
INSERT IGNORE INTO m_system_config (config_key, config_value, description) VALUES
  ('retention.risk.bench-warn-days', '30', '定着リスク: 長期Bench加点の基準日数'),
  ('retention.risk.followup-interval-days', '30', '定着リスク: フォロー間隔超過とみなす基準日数'),
  ('retention.risk.threshold', '60', '定着リスク: 高リスクと判定するスコア閾値(0-100)');
