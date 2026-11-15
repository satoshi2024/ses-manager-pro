-- V194（NF08 B2c-1）のH2再生。dispatch claim列・費用ledger 2表・監査の存在照会索引。
-- 共有H2はcontextごとにschema-locationsを再実行するため、列・制約・索引・表・aliasとも存在確認付きで冪等にする。
-- MySQLの UUID_TO_BIN(?, 0) / BIN_TO_UUID(?, 0) はH2に無いため、同じ変換をするtest専用のJava関数を登録する。
-- 索引はMySQLと同じ列順で再現するが、H2の結果を照合・一意性・lockの正しさの根拠にはしない（MySQL gateで確認する）。

CREATE ALIAS IF NOT EXISTS UUID_TO_BIN FOR 'com.ses.test.H2MySqlUuidFunctions.uuidToBin';
CREATE ALIAS IF NOT EXISTS BIN_TO_UUID FOR 'com.ses.test.H2MySqlUuidFunctions.binToUuid';

ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_state VARCHAR(16) DEFAULT 'NOT_APPLICABLE' NOT NULL;
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_claim_id BINARY(16);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_nonce_hash BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_idempotency_hash BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_binding_hash BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS preflight_snapshot_hash BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_request_sha256 BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_issued_at TIMESTAMP(6);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_expires_at TIMESTAMP(6);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_claimed_at TIMESTAMP(6);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_started_at TIMESTAMP(6);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_provider VARCHAR(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_model_revision VARCHAR(64);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_region VARCHAR(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_tier VARCHAR(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_config_hash BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_artifact_version_id BIGINT;
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_scope_hash BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_input_hash BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_approval_digest BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_card_id VARCHAR(64);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_card_version VARCHAR(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_card_digest BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_policy_id VARCHAR(64);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_policy_version VARCHAR(32);
ALTER TABLE t_ai_recommendation_run ADD COLUMN IF NOT EXISTS dispatch_policy_digest BINARY(32);
ALTER TABLE t_ai_recommendation_run ADD CONSTRAINT IF NOT EXISTS ck_ai_run_dispatch_state
    CHECK (dispatch_state IN ('NOT_APPLICABLE', 'READY', 'CLAIMED', 'UNKNOWN'));
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_run_dispatch_claim ON t_ai_recommendation_run (dispatch_claim_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_run_dispatch_nonce ON t_ai_recommendation_run (tenant_id, dispatch_nonce_hash);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_run_dispatch_idem ON t_ai_recommendation_run (tenant_id, dispatch_idempotency_hash);
CREATE INDEX IF NOT EXISTS idx_ai_run_dispatch_reconcile
    ON t_ai_recommendation_run (tenant_id, dispatch_state, status, dispatch_expires_at, id);

CREATE TABLE IF NOT EXISTS t_ai_external_send_cost_bucket (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cost_scope VARCHAR(64) NOT NULL,
    bucket_type VARCHAR(8) NOT NULL,
    bucket_key VARCHAR(10) NOT NULL,
    committed_yen BIGINT DEFAULT 0 NOT NULL,
    last_limit_yen BIGINT,
    last_approval_digest BINARY(32),
    clock_high_water_at TIMESTAMP(6),
    version BIGINT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6) NOT NULL,
    CONSTRAINT uk_ai_cost_bucket UNIQUE (cost_scope, bucket_type, bucket_key),
    CONSTRAINT ck_ai_cost_bucket_type CHECK (bucket_type IN ('DAY', 'MONTH')),
    CONSTRAINT ck_ai_cost_bucket_nonneg CHECK (committed_yen >= 0)
);
CREATE INDEX IF NOT EXISTS idx_ai_cost_clock ON t_ai_external_send_cost_bucket (cost_scope, clock_high_water_at);

CREATE TABLE IF NOT EXISTS t_ai_external_send_cost_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id BINARY(16) NOT NULL,
    tenant_id VARCHAR(100) NOT NULL,
    run_id BIGINT,
    trace_id CHAR(36) NOT NULL,
    cost_scope VARCHAR(64) NOT NULL,
    day_key VARCHAR(10) NOT NULL,
    month_key VARCHAR(7) NOT NULL,
    decision_id VARCHAR(64) NOT NULL,
    approval_digest BINARY(32) NOT NULL,
    reserved_yen INT NOT NULL,
    settled_yen INT,
    entry_state VARCHAR(16) NOT NULL,
    status_version INT DEFAULT 0 NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    closed_at TIMESTAMP(6),
    CONSTRAINT uk_ai_cost_entry_reservation UNIQUE (reservation_id),
    CONSTRAINT ck_ai_cost_entry_state CHECK (entry_state IN ('RESERVED', 'SETTLED', 'KEPT_UPPER_BOUND')),
    CONSTRAINT ck_ai_cost_entry_amount CHECK (reserved_yen >= 1 AND (settled_yen IS NULL OR settled_yen >= 0))
);
CREATE INDEX IF NOT EXISTS idx_ai_cost_entry_state ON t_ai_external_send_cost_entry (entry_state, created_at);
CREATE INDEX IF NOT EXISTS idx_ai_cost_entry_tenant ON t_ai_external_send_cost_entry (tenant_id, created_at);

CREATE INDEX IF NOT EXISTS idx_audit_dispatch_key ON t_audit_log (idempotency_key, application_code, id);
