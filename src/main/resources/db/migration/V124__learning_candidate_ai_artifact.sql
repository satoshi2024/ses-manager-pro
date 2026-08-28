-- ===================================================================
-- V124: NF-03 F2-5 学習候補用AI artifactの正本seed
-- ===================================================================

INSERT INTO m_ai_artifact_version (
    use_case, provider, model_name, prompt_version, rule_version, config_hash,
    status, status_version, activated_at
) VALUES (
    'LEARNING_CANDIDATE', 'mock', 'mock-learning-candidate', 'nf03-f2-5', 'rule-gap-v1',
    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
    'ACTIVE', 0, CURRENT_TIMESTAMP
);
