CREATE TABLE t_sales_activity (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  customer_id      BIGINT NOT NULL COMMENT '顧客ID',
  activity_type    ENUM('商談','訪問','電話','メール','その他') NOT NULL COMMENT '活動種別',
  activity_date    DATE NOT NULL COMMENT '活動日',
  title            VARCHAR(200) NOT NULL COMMENT 'タイトル',
  content          TEXT COMMENT '内容',
  next_action_date DATE COMMENT '次回アクション予定日',
  completed_flag   TINYINT DEFAULT 0 COMMENT '完了フラグ',
  created_by       BIGINT COMMENT '登録者ID',
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted_flag     TINYINT DEFAULT 0 COMMENT '論理削除フラグ',
  INDEX idx_activity_customer (customer_id),
  INDEX idx_activity_next_action (next_action_date),
  CONSTRAINT fk_activity_customer FOREIGN KEY (customer_id) REFERENCES m_customer(id) ON DELETE RESTRICT,
  CONSTRAINT fk_activity_created_by FOREIGN KEY (created_by) REFERENCES sys_user(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='営業活動';
