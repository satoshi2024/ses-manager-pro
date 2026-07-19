
ALTER TABLE t_notification ADD COLUMN recipient_user_id BIGINT;
CREATE INDEX idx_notification_recipient ON t_notification(recipient_user_id);

ALTER TABLE t_work_record ADD COLUMN reject_comment VARCHAR(500);
