CREATE TABLE t_notification (
  id          BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'ID',
  type        VARCHAR(30)  NOT NULL COMMENT '種別(CONTRACT_END/PROPOSAL_STALE/BENCH_LONG/PROJECT_URGENT/AI_MATCHING)',
  title       VARCHAR(200) NOT NULL COMMENT 'タイトル',
  message     VARCHAR(500)          COMMENT '本文',
  link_url    VARCHAR(300)          COMMENT '関連画面URL',
  dedupe_key  VARCHAR(200) NOT NULL UNIQUE COMMENT '重複防止キー(例 CONTRACT_END:12:2026-08-01)',
  created_at  DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '作成日時',
  INDEX idx_notification_type (type),
  INDEX idx_notification_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知';

CREATE TABLE t_notification_read (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY,
  notification_id BIGINT NOT NULL,
  user_id         BIGINT NOT NULL,
  read_at         DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_notification_read (notification_id, user_id),
  FOREIGN KEY (notification_id) REFERENCES t_notification(id) ON DELETE CASCADE,
  FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知既読';

-- ToDo画面のメニュー登録(sort_order は既存メニューの並びに合わせて調整)
INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
VALUES ('todo', 'ToDo・通知', '/todo', '/api/notifications', 75);
INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id FROM (SELECT '管理者' role UNION SELECT '営業' UNION SELECT 'HR' UNION SELECT 'マネージャー') r,
     m_menu m WHERE m.menu_key = 'todo';
