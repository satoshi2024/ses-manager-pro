-- ===================================================================
-- V132: 退社例外免除の案件・タスクscopeと資産event不変性保護
-- V131の既存台帳行はlegacyとしてNULLを許容し、新規適用はserviceで両scopeを必須化する。
-- ===================================================================
ALTER TABLE t_asset_offboarding_waiver
    ADD COLUMN lifecycle_case_id BIGINT NULL COMMENT '対象退社案件ID' AFTER engineer_id,
    ADD COLUMN lifecycle_task_id BIGINT NULL COMMENT 'RESIGN_ASSET_RETURNタスクID' AFTER lifecycle_case_id,
    ADD INDEX idx_asset_offboarding_waiver_case_task (lifecycle_case_id, lifecycle_task_id),
    ADD CONSTRAINT fk_asset_offboarding_waiver_case
        FOREIGN KEY (lifecycle_case_id) REFERENCES t_lifecycle_case(id)
        ON UPDATE CASCADE ON DELETE RESTRICT,
    ADD CONSTRAINT fk_asset_offboarding_waiver_task
        FOREIGN KEY (lifecycle_task_id) REFERENCES t_lifecycle_task(id)
        ON UPDATE CASCADE ON DELETE RESTRICT;

-- eventはINSERT-only。資産状態の変更は別の正本テーブルで行い、event行は上書き・削除しない。
DROP TRIGGER IF EXISTS trg_asset_event_no_update;
CREATE TRIGGER trg_asset_event_no_update BEFORE UPDATE ON t_asset_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_asset_event is append-only';

DROP TRIGGER IF EXISTS trg_asset_event_no_delete;
CREATE TRIGGER trg_asset_event_no_delete BEFORE DELETE ON t_asset_event
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 't_asset_event is append-only';
