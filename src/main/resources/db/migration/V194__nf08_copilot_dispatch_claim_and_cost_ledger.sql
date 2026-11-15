-- V194: NF08 B2c-1。summary dispatchのdurable claim・発行時binding・card/policy snapshot・dispatch_started_atと、
-- 外部送信費用の永続ledger（JST日次・月次のbucketと、送信1回ごとの予約entry）を追加する。
-- 計画: .kiro/roadmap/2026-10-18-snf-nf08-c2-b2-technical-plan.md 「W3-03 計画更新」§1.2（D-2・D-3を反映）。
-- transport observation・response列はB2b/B2eの設計PASS後に別のmigrationで追加する（本scriptには含めない）。
-- 既存のquery/legacy行は dispatch_state='NOT_APPLICABLE'・他の列NULLのままとし、推測によるbackfillはしない。
-- claim ID（dispatch_claim_id / reservation_id）は小文字36文字のUUID表記を UUID_TO_BIN(?, 0) で変換して保存し、
-- BIN_TO_UUID(?, 0) で同じ表記へ戻す。t_audit_log.idempotency_key には同じ36文字表記を保存する。
-- 全てのhashはcanonical encodingのSHA-256（32 byte）。nonce・idempotency keyの生値は保存しない。

-- (a) summary dispatchのdurable claimと発行時binding
ALTER TABLE t_ai_recommendation_run
    ADD COLUMN dispatch_state VARCHAR(16) NOT NULL DEFAULT 'NOT_APPLICABLE'
        COMMENT 'NOT_APPLICABLE / READY / CLAIMED / UNKNOWN',
    ADD COLUMN dispatch_claim_id BINARY(16) NULL COMMENT 'claim ID（UUID_TO_BIN(?, 0)）。全tenantで一意',
    ADD COLUMN dispatch_nonce_hash BINARY(32) NULL COMMENT 'nonceのSHA-256（生値は保存しない）',
    ADD COLUMN dispatch_idempotency_hash BINARY(32) NULL COMMENT 'idempotency keyのSHA-256',
    ADD COLUMN dispatch_binding_hash BINARY(32) NULL COMMENT '発行時bindingのcanonical SHA-256',
    ADD COLUMN preflight_snapshot_hash BINARY(32) NULL COMMENT 'preflight snapshotのSHA-256',
    ADD COLUMN dispatch_request_sha256 BINARY(32) NULL COMMENT '送信request bytesのSHA-256',
    ADD COLUMN dispatch_issued_at DATETIME(6) NULL COMMENT 'READY発行時刻（UTC、アプリClock）',
    ADD COLUMN dispatch_expires_at DATETIME(6) NULL COMMENT 'attempt期限（UTC）',
    ADD COLUMN dispatch_claimed_at DATETIME(6) NULL COMMENT 'claim commit時刻（UTC、アプリClock）',
    ADD COLUMN dispatch_started_at DATETIME(6) NULL COMMENT 'socket直前の確認後に記録する時刻（UTC。B2c-2）',
    ADD COLUMN dispatch_provider VARCHAR(32) NULL,
    ADD COLUMN dispatch_model_revision VARCHAR(64) NULL,
    ADD COLUMN dispatch_region VARCHAR(32) NULL,
    ADD COLUMN dispatch_tier VARCHAR(32) NULL,
    ADD COLUMN dispatch_config_hash BINARY(32) NULL,
    ADD COLUMN dispatch_artifact_version_id BIGINT NULL,
    ADD COLUMN dispatch_scope_hash BINARY(32) NULL,
    ADD COLUMN dispatch_input_hash BINARY(32) NULL,
    ADD COLUMN dispatch_approval_digest BINARY(32) NULL COMMENT 'W3-03の承認内容digest',
    ADD COLUMN dispatch_card_id VARCHAR(64) NULL,
    ADD COLUMN dispatch_card_version VARCHAR(32) NULL,
    ADD COLUMN dispatch_card_digest BINARY(32) NULL,
    ADD COLUMN dispatch_policy_id VARCHAR(64) NULL,
    ADD COLUMN dispatch_policy_version VARCHAR(32) NULL,
    ADD COLUMN dispatch_policy_digest BINARY(32) NULL,
    ADD CONSTRAINT ck_ai_run_dispatch_state
        CHECK (dispatch_state IN ('NOT_APPLICABLE', 'READY', 'CLAIMED', 'UNKNOWN')),
    ADD UNIQUE KEY uk_ai_run_dispatch_claim (dispatch_claim_id),
    ADD UNIQUE KEY uk_ai_run_dispatch_nonce (tenant_id, dispatch_nonce_hash),
    ADD UNIQUE KEY uk_ai_run_dispatch_idem (tenant_id, dispatch_idempotency_hash),
    ADD KEY idx_ai_run_dispatch_reconcile (tenant_id, dispatch_state, status, dispatch_expires_at, id);

-- (b) 永続費用ledger（Q-11）。上限の適用範囲（cost_scope）ごとのJST日次・月次の合計。
CREATE TABLE t_ai_external_send_cost_bucket (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    cost_scope VARCHAR(64) NOT NULL COMMENT '費用上限の適用範囲（provider単位。例: gemini）',
    bucket_type VARCHAR(8) NOT NULL COMMENT 'DAY / MONTH',
    bucket_key VARCHAR(10) NOT NULL COMMENT 'JST業務日 yyyy-MM-dd / 月 yyyy-MM',
    committed_yen BIGINT NOT NULL DEFAULT 0 COMMENT '予約中と精算済みの合計（円）',
    last_limit_yen BIGINT NULL COMMENT '最後の予約判定に使った上限（監査用）',
    last_approval_digest BINARY(32) NULL COMMENT '最後の予約時の承認内容digest',
    clock_high_water_at DATETIME(6) NULL COMMENT 'アプリClockで観測した時刻の高水位（UTC）',
    version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CONSTRAINT uk_ai_cost_bucket UNIQUE (cost_scope, bucket_type, bucket_key),
    CONSTRAINT ck_ai_cost_bucket_type CHECK (bucket_type IN ('DAY', 'MONTH')),
    CONSTRAINT ck_ai_cost_bucket_nonneg CHECK (committed_yen >= 0),
    KEY idx_ai_cost_clock (cost_scope, clock_high_water_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF08 外部送信費用の日次・月次合計';

-- (c) 1回の外部送信の予約と精算（attemptと1対1。reservation_id = dispatch_claim_id）。
-- tenant_idは t_ai_recommendation_run.tenant_id（V162でVARCHAR(100)）と同じ幅にする。
CREATE TABLE t_ai_external_send_cost_entry (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_id BINARY(16) NOT NULL COMMENT 'claim ID（UUID_TO_BIN(?, 0)）',
    tenant_id VARCHAR(100) NOT NULL,
    run_id BIGINT NULL COMMENT 't_ai_recommendation_run.id（summary dispatch attempt）',
    trace_id CHAR(36) NOT NULL,
    cost_scope VARCHAR(64) NOT NULL,
    day_key VARCHAR(10) NOT NULL COMMENT '予約時のJST業務日。精算もこの日のbucketへ反映する',
    month_key VARCHAR(7) NOT NULL,
    decision_id VARCHAR(64) NOT NULL,
    approval_digest BINARY(32) NOT NULL,
    reserved_yen INT NOT NULL,
    settled_yen INT NULL,
    entry_state VARCHAR(16) NOT NULL COMMENT 'RESERVED / SETTLED / KEPT_UPPER_BOUND',
    status_version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    closed_at DATETIME(6) NULL,
    CONSTRAINT uk_ai_cost_entry_reservation UNIQUE (reservation_id),
    CONSTRAINT ck_ai_cost_entry_state CHECK (entry_state IN ('RESERVED', 'SETTLED', 'KEPT_UPPER_BOUND')),
    CONSTRAINT ck_ai_cost_entry_amount CHECK (reserved_yen >= 1 AND (settled_yen IS NULL OR settled_yen >= 0)),
    KEY idx_ai_cost_entry_state (entry_state, created_at),
    KEY idx_ai_cost_entry_tenant (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='NF08 外部送信費用の予約・精算';

-- (d) C-1: ATTEMPT / RESULT 監査の存在照会（idempotency_key = claim ID の完全一致。application_codeごと）。
ALTER TABLE t_audit_log
    ADD INDEX idx_audit_dispatch_key (idempotency_key, application_code, id);
