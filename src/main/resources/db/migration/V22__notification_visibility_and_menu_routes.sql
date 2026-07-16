ALTER TABLE t_notification ADD COLUMN menu_key VARCHAR(50) NULL COMMENT '閲覧に必要なメニューキー';
CREATE INDEX idx_notification_menu_key ON t_notification(menu_key);
UPDATE m_menu SET path_prefix = '/contract-document' WHERE menu_key = 'contract-document';
