-- V35: C60 Notifications Recipient
ALTER TABLE t_notification ADD COLUMN recipient_user_id BIGINT COMMENT '宛先ユーザーID（NULLは該当ロール全体）' AFTER menu_key;
CREATE INDEX idx_notification_recipient ON t_notification(recipient_user_id);
