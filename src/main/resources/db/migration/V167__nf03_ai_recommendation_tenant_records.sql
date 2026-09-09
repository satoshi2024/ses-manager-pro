-- V167: NF03のAI recommendation/item/feedback/outcomeをtenant単位の正本に固定する。
-- 既存V108/V157の履歴は変更しない。過去行は親run/itemからのみ機械的に復元し、
-- それでもtenantを特定できない場合はdefaultへ補完せず移行を停止する。

ALTER TABLE t_ai_recommendation_run
    ADD UNIQUE KEY uk_ai_run_tenant_id (tenant_id, id);

ALTER TABLE t_ai_recommendation_item
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;
ALTER TABLE t_ai_feedback
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;
ALTER TABLE t_ai_outcome
    ADD COLUMN tenant_id VARCHAR(100) NULL AFTER id;

UPDATE t_ai_recommendation_item i
JOIN t_ai_recommendation_run r ON r.id = i.run_id
SET i.tenant_id = r.tenant_id
WHERE i.tenant_id IS NULL AND r.tenant_id IS NOT NULL AND r.tenant_id <> '';
UPDATE t_ai_feedback f
JOIN t_ai_recommendation_item i ON i.id = f.item_id
SET f.tenant_id = i.tenant_id
WHERE f.tenant_id IS NULL AND i.tenant_id IS NOT NULL AND i.tenant_id <> '';
UPDATE t_ai_outcome o
JOIN t_ai_recommendation_item i ON i.id = o.item_id
SET o.tenant_id = i.tenant_id
WHERE o.tenant_id IS NULL AND i.tenant_id IS NOT NULL AND i.tenant_id <> '';

DELIMITER $$
DROP PROCEDURE IF EXISTS __ses_check_v167_ai_tenant_records $$
CREATE PROCEDURE __ses_check_v167_ai_tenant_records()
BEGIN
    DECLARE missing_count BIGINT DEFAULT 0;
    SELECT COUNT(*) INTO missing_count
      FROM (
          SELECT id FROM t_ai_recommendation_item WHERE tenant_id IS NULL OR tenant_id = ''
          UNION ALL
          SELECT id FROM t_ai_feedback WHERE tenant_id IS NULL OR tenant_id = ''
          UNION ALL
          SELECT id FROM t_ai_outcome WHERE tenant_id IS NULL OR tenant_id = ''
      ) unresolved;
    IF missing_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'V167: AI recommendation関連行のtenantを特定できないため移行を停止しました';
    END IF;
END $$
DELIMITER ;
CALL __ses_check_v167_ai_tenant_records();
DROP PROCEDURE IF EXISTS __ses_check_v167_ai_tenant_records;

ALTER TABLE t_ai_recommendation_item
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    DROP FOREIGN KEY fk_ai_item_run,
    DROP INDEX uk_ai_item_run_rank,
    ADD UNIQUE KEY uk_ai_item_tenant_run_rank (tenant_id, run_id, rank_no),
    ADD UNIQUE KEY uk_ai_item_tenant_id (tenant_id, id),
    ADD INDEX idx_ai_item_tenant_target (tenant_id, target_type, target_id),
    ADD CONSTRAINT fk_ai_item_run_tenant FOREIGN KEY (tenant_id, run_id)
        REFERENCES t_ai_recommendation_run (tenant_id, id);

ALTER TABLE t_ai_feedback
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    DROP FOREIGN KEY fk_ai_feedback_item,
    ADD INDEX idx_ai_feedback_tenant_item (tenant_id, item_id),
    ADD CONSTRAINT fk_ai_feedback_item_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES t_ai_recommendation_item (tenant_id, id);

ALTER TABLE t_ai_outcome
    MODIFY COLUMN tenant_id VARCHAR(100) NOT NULL,
    DROP FOREIGN KEY fk_ai_outcome_item,
    ADD INDEX idx_ai_outcome_tenant_item (tenant_id, item_id),
    ADD CONSTRAINT fk_ai_outcome_item_tenant FOREIGN KEY (tenant_id, item_id)
        REFERENCES t_ai_recommendation_item (tenant_id, id);
