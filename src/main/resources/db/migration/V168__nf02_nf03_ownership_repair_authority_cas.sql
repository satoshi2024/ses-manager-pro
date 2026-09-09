-- V168: ownership repair queueの中心修復権限・監査・CAS境界。
-- 既存queue行を削除・推測補完せず、未解決行は従来どおりfail-closedで扱う。
ALTER TABLE nf02_nf03_ownership_repair_queue
    ADD COLUMN version INT NOT NULL DEFAULT 0,
    ADD COLUMN claim_token VARCHAR(100) NULL,
    ADD COLUMN claimed_by BIGINT NULL,
    ADD COLUMN claimed_at DATETIME NULL,
    ADD COLUMN incident_id BIGINT NULL,
    ADD COLUMN actor_tenant_id VARCHAR(100) NULL,
    ADD COLUMN evidence_hash VARCHAR(64) NULL,
    ADD COLUMN approver_id BIGINT NULL;

CREATE INDEX idx_nf02_nf03_repair_claim
    ON nf02_nf03_ownership_repair_queue (status, version, claim_token, id);
