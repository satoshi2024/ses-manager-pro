-- ============================================================
-- SES Manager Pro - AI推薦フィードバック・評価ループ (T110 / S17)
-- ファイル: V108__ai_feedback_learning.sql
-- 説明: artifact version / run / item / feedback / outcome / evaluation
--
-- G0 独立DBのため tenant_id は作らない。design §5.3 の
-- UNIQUE(use_case, tenant) WHERE status=ACTIVE は、生成列
-- active_use_case（ACTIVE かつ未削除のとき use_case）の UNIQUE で実現する。
-- tenant 境界はプロセス/DB分離。raw prompt 列は作らない（R1.2）。
-- ============================================================

-- ============================================================
-- m_ai_artifact_version
-- ============================================================
CREATE TABLE m_ai_artifact_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    use_case VARCHAR(32) NOT NULL COMMENT 'MATCHING/PROPOSAL_DRAFT/CHAT',
    provider VARCHAR(32) NOT NULL COMMENT 'mock/rule/gemini',
    model_name VARCHAR(128) NULL,
    prompt_version VARCHAR(64) NOT NULL COMMENT 'prompt識別子。本文は保存しない',
    rule_version VARCHAR(64) NULL,
    config_hash CHAR(64) NOT NULL COMMENT '再現用ハッシュ',
    status VARCHAR(16) NOT NULL COMMENT 'DRAFT/SHADOW/ACTIVE/RETIRED',
    status_version INT NOT NULL DEFAULT 0 COMMENT '状態CAS',
    activated_at DATETIME NULL,
    retired_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    active_use_case VARCHAR(32)
        GENERATED ALWAYS AS (
            CASE WHEN status = 'ACTIVE' AND deleted_flag = 0 THEN use_case ELSE NULL END
        ) STORED COMMENT 'ACTIVE一意用。NULLは複数可',
    UNIQUE KEY uk_ai_artifact_active_use_case (active_use_case),
    KEY idx_ai_artifact_use_case_status (use_case, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI model/prompt/rule version';

-- ============================================================
-- t_ai_recommendation_run
-- raw prompt / request_params は持たない。redacted_summary_json と input_hash のみ。
-- ============================================================
CREATE TABLE t_ai_recommendation_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id CHAR(36) NOT NULL,
    use_case VARCHAR(32) NOT NULL,
    artifact_version_id BIGINT NOT NULL,
    actor_user_id BIGINT NULL,
    input_hash CHAR(64) NOT NULL,
    redacted_summary_json JSON NULL COMMENT 'mask済みsummary。raw promptではない',
    latency_ms INT NULL,
    token_input INT NULL,
    token_output INT NULL,
    cost_jpy INT NULL COMMENT '円。端数切捨て',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/SUCCEEDED/FAILED',
    status_version INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_run_trace (trace_id),
    KEY idx_ai_run_version (artifact_version_id),
    KEY idx_ai_run_created (created_at),
    CONSTRAINT fk_ai_run_version
        FOREIGN KEY (artifact_version_id) REFERENCES m_ai_artifact_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI推薦実行';

-- ============================================================
-- t_ai_recommendation_item
-- ============================================================
CREATE TABLE t_ai_recommendation_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    rank_no INT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    score DECIMAL(10,4) NULL,
    explanation_json JSON NULL COMMENT 'JSON schema検証対象。HTML禁止',
    selected_flag TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_item_run_rank (run_id, rank_no),
    KEY idx_ai_item_target (target_type, target_id),
    CONSTRAINT fk_ai_item_run
        FOREIGN KEY (run_id) REFERENCES t_ai_recommendation_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI推薦候補';

-- ============================================================
-- t_ai_feedback
-- decision NULL は未判断（却下ではない）。item単位でappend。
-- ============================================================
CREATE TABLE t_ai_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    decision VARCHAR(16) NULL COMMENT 'ACCEPT/REJECT/HOLD。NULL=未判断',
    reason_code VARCHAR(32) NULL,
    comment_redacted VARCHAR(500) NULL,
    decided_by BIGINT NULL,
    decided_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    KEY idx_ai_feedback_item (item_id),
    CONSTRAINT fk_ai_feedback_item
        FOREIGN KEY (item_id) REFERENCES t_ai_recommendation_item (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI推薦フィードバック';

-- ============================================================
-- t_ai_outcome
-- EARLY_EXIT 用に original_end_date を snapshot する（R1-P2-02）。
-- ============================================================
CREATE TABLE t_ai_outcome (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    outcome_type VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    occurred_at DATETIME NOT NULL,
    original_end_date DATE NULL COMMENT '解約CAS前の当初end_date。EARLY_EXIT判定用',
    value_json JSON NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    UNIQUE KEY uk_ai_outcome_idempotent (item_id, outcome_type, source_type, source_id),
    KEY idx_ai_outcome_item (item_id),
    CONSTRAINT fk_ai_outcome_item
        FOREIGN KEY (item_id) REFERENCES t_ai_recommendation_item (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI推薦成果event';

-- ============================================================
-- t_ai_evaluation
-- ============================================================
CREATE TABLE t_ai_evaluation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_version_id BIGINT NOT NULL,
    baseline_version_id BIGINT NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    metrics_json JSON NULL,
    status VARCHAR(16) NOT NULL COMMENT 'RUNNING/PASSED/FAILED/APPROVED/REJECTED',
    status_version INT NOT NULL DEFAULT 0,
    approved_by BIGINT NULL,
    approved_at DATETIME NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT(1) NOT NULL DEFAULT 0,
    KEY idx_ai_eval_candidate (candidate_version_id),
    KEY idx_ai_eval_baseline (baseline_version_id),
    CONSTRAINT fk_ai_eval_candidate
        FOREIGN KEY (candidate_version_id) REFERENCES m_ai_artifact_version (id),
    CONSTRAINT fk_ai_eval_baseline
        FOREIGN KEY (baseline_version_id) REFERENCES m_ai_artifact_version (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI version評価';

-- 開発baseline: use caseあたり mock ACTIVE を1つ。T111 が参照する。
INSERT INTO m_ai_artifact_version (
    use_case, provider, model_name, prompt_version, rule_version, config_hash,
    status, status_version, activated_at
) VALUES
('MATCHING', 'mock', 'mock-matching', 'g10-t109', 'mock',
 '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
 'ACTIVE', 0, CURRENT_TIMESTAMP),
('PROPOSAL_DRAFT', 'mock', 'mock-proposal', 'g10-t109', 'mock',
 '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
 'ACTIVE', 0, CURRENT_TIMESTAMP),
('CHAT', 'mock', 'mock-chat', 'g10-t109', 'mock',
 '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
 'ACTIVE', 0, CURRENT_TIMESTAMP);
