-- C15: 勤怠差戻しコメント列を追加（recipient_user_id はV36で追加済みのため再追加しない）
ALTER TABLE t_work_record ADD COLUMN reject_comment VARCHAR(500) COMMENT '差戻しコメント';
