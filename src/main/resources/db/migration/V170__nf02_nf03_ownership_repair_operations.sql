-- NF02/NF03 tenant未解決行の運用修復記録。自動推測やdefault補完は行わない。
ALTER TABLE nf02_nf03_ownership_repair_queue
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN assignee_user_id BIGINT NULL,
    ADD COLUMN repair_tenant_id VARCHAR(100) NULL,
    ADD COLUMN resolution_reason VARCHAR(500) NULL,
    ADD COLUMN evidence TEXT NULL,
    ADD COLUMN resolved_at DATETIME NULL,
    ADD COLUMN resolved_by BIGINT NULL,
    ADD COLUMN last_checked_at DATETIME NULL;

CREATE INDEX idx_nf02_nf03_repair_status_age
    ON nf02_nf03_ownership_repair_queue (status, created_at, id);
