-- H2 Schema for AI feedback learning (T110)
-- MySQL V108 の方言差分: JSON→CLOB、生成列に STORED を付けない。

CREATE TABLE IF NOT EXISTS m_ai_artifact_version (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    use_case VARCHAR(32) NOT NULL,
    provider VARCHAR(32) NOT NULL,
    model_name VARCHAR(128) NULL,
    prompt_version VARCHAR(64) NOT NULL,
    rule_version VARCHAR(64) NULL,
    config_hash CHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    status_version INT NOT NULL DEFAULT 0,
    activated_at DATETIME NULL,
    retired_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT DEFAULT 0,
    active_use_case VARCHAR(32) GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' AND deleted_flag = 0 THEN use_case ELSE NULL END
    ),
    CONSTRAINT uk_ai_artifact_active_use_case UNIQUE (active_use_case)
);

CREATE TABLE IF NOT EXISTS t_ai_recommendation_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    trace_id CHAR(36) NOT NULL,
    use_case VARCHAR(32) NOT NULL,
    artifact_version_id BIGINT NOT NULL,
    actor_user_id BIGINT NULL,
    input_hash CHAR(64) NOT NULL,
    redacted_summary_json CLOB NULL,
    latency_ms INT NULL,
    token_input INT NULL,
    token_output INT NULL,
    cost_jpy INT NULL,
    status VARCHAR(16) NOT NULL,
    status_version INT NOT NULL DEFAULT 0,
    error_code VARCHAR(64) NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT DEFAULT 0,
    CONSTRAINT uk_ai_run_trace UNIQUE (trace_id),
    CONSTRAINT fk_ai_run_version FOREIGN KEY (artifact_version_id)
        REFERENCES m_ai_artifact_version (id)
);

CREATE TABLE IF NOT EXISTS t_ai_recommendation_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    rank_no INT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id BIGINT NOT NULL,
    score DECIMAL(10,4) NULL,
    explanation_json CLOB NULL,
    selected_flag TINYINT DEFAULT 0,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT DEFAULT 0,
    CONSTRAINT uk_ai_item_run_rank UNIQUE (run_id, rank_no),
    CONSTRAINT fk_ai_item_run FOREIGN KEY (run_id)
        REFERENCES t_ai_recommendation_run (id)
);

CREATE TABLE IF NOT EXISTS t_ai_feedback (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    decision VARCHAR(16) NULL,
    reason_code VARCHAR(32) NULL,
    comment_redacted VARCHAR(500) NULL,
    decided_by BIGINT NULL,
    decided_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT DEFAULT 0,
    CONSTRAINT fk_ai_feedback_item FOREIGN KEY (item_id)
        REFERENCES t_ai_recommendation_item (id)
);

CREATE TABLE IF NOT EXISTS t_ai_outcome (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    item_id BIGINT NOT NULL,
    outcome_type VARCHAR(32) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id BIGINT NOT NULL,
    occurred_at DATETIME NOT NULL,
    original_end_date DATE NULL,
    value_json CLOB NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT DEFAULT 0,
    CONSTRAINT uk_ai_outcome_idempotent UNIQUE (item_id, outcome_type, source_type, source_id),
    CONSTRAINT fk_ai_outcome_item FOREIGN KEY (item_id)
        REFERENCES t_ai_recommendation_item (id)
);

CREATE TABLE IF NOT EXISTS t_ai_evaluation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    candidate_version_id BIGINT NOT NULL,
    baseline_version_id BIGINT NOT NULL,
    dataset_version VARCHAR(64) NOT NULL,
    metrics_json CLOB NULL,
    status VARCHAR(16) NOT NULL,
    status_version INT NOT NULL DEFAULT 0,
    approved_by BIGINT NULL,
    approved_at DATETIME NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted_flag TINYINT DEFAULT 0,
    CONSTRAINT fk_ai_eval_candidate FOREIGN KEY (candidate_version_id)
        REFERENCES m_ai_artifact_version (id),
    CONSTRAINT fk_ai_eval_baseline FOREIGN KEY (baseline_version_id)
        REFERENCES m_ai_artifact_version (id)
);

INSERT INTO m_ai_artifact_version (
    use_case, provider, model_name, prompt_version, rule_version, config_hash,
    status, status_version, activated_at, deleted_flag
)
SELECT 'MATCHING', 'mock', 'mock-matching', 'g10-t109', 'mock',
       '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
       'ACTIVE', 0, CURRENT_TIMESTAMP, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM m_ai_artifact_version
    WHERE use_case = 'MATCHING' AND status = 'ACTIVE' AND deleted_flag = 0
);

INSERT INTO m_ai_artifact_version (
    use_case, provider, model_name, prompt_version, rule_version, config_hash,
    status, status_version, activated_at, deleted_flag
)
SELECT 'PROPOSAL_DRAFT', 'mock', 'mock-proposal', 'g10-t109', 'mock',
       '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
       'ACTIVE', 0, CURRENT_TIMESTAMP, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM m_ai_artifact_version
    WHERE use_case = 'PROPOSAL_DRAFT' AND status = 'ACTIVE' AND deleted_flag = 0
);

INSERT INTO m_ai_artifact_version (
    use_case, provider, model_name, prompt_version, rule_version, config_hash,
    status, status_version, activated_at, deleted_flag
)
SELECT 'CHAT', 'mock', 'mock-chat', 'g10-t109', 'mock',
       '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
       'ACTIVE', 0, CURRENT_TIMESTAMP, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM m_ai_artifact_version
    WHERE use_case = 'CHAT' AND status = 'ACTIVE' AND deleted_flag = 0
);

INSERT INTO m_ai_artifact_version (
    use_case, provider, model_name, prompt_version, rule_version, config_hash,
    status, status_version, activated_at, deleted_flag
)
SELECT 'MANAGEMENT_COPILOT', 'mock', 'mock-management-copilot', 'nf08-f1', 'catalog-v1',
       '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
       'ACTIVE', 0, CURRENT_TIMESTAMP, 0
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM m_ai_artifact_version
    WHERE use_case = 'MANAGEMENT_COPILOT' AND status = 'ACTIVE' AND deleted_flag = 0
);

-- T112: 提案へ AI trace を保存。H2 は schema-locations 再実行のため IF NOT EXISTS。
ALTER TABLE t_proposal ADD COLUMN IF NOT EXISTS ai_trace_id VARCHAR(36);
ALTER TABLE t_proposal ADD COLUMN IF NOT EXISTS ai_item_id BIGINT;

INSERT INTO m_menu (menu_key, menu_name, path_prefix, api_prefix, sort_order)
SELECT 'ai-evaluation', 'AI評価', '/ai/evaluation', '/api/ai/evaluations', 73
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM m_menu WHERE menu_key = 'ai-evaluation');

INSERT INTO t_role_menu (role, menu_id)
SELECT r.role, m.id
FROM (SELECT '管理者' AS role UNION ALL SELECT 'マネージャー' UNION ALL SELECT '営業') r
CROSS JOIN m_menu m
WHERE m.menu_key = 'ai-evaluation'
  AND NOT EXISTS (SELECT 1 FROM t_role_menu tr WHERE tr.role = r.role AND tr.menu_id = m.id);
