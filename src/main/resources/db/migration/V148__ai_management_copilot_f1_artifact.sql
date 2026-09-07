-- ===================================================================
-- V148: NF-08 F1 management copilot artifact seed
-- ===================================================================

INSERT INTO m_ai_artifact_version (
    use_case, provider, model_name, prompt_version, rule_version, config_hash,
    status, status_version, activated_at
) VALUES (
    'MANAGEMENT_COPILOT', 'mock', 'mock-management-copilot', 'nf08-f1', 'catalog-v1',
    '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef',
    'ACTIVE', 0, CURRENT_TIMESTAMP
);
