-- ===================================================================
-- V125: NF-03 F2 training relation tablesのMyBatis監査timestamp補正
-- 適用済みV118は編集せず、entity/BaseMapperが要求する列を追加する。
-- ===================================================================

ALTER TABLE t_training_course_skill
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

ALTER TABLE t_learning_plan_skill
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;

ALTER TABLE t_training_enrollment_expense
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
