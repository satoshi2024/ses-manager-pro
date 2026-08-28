-- ===================================================================
-- V121: NF-03 F2-1 資格record lifecycle、event冪等key、PII action seed
-- ===================================================================

ALTER TABLE t_certification_event
    ADD COLUMN idempotency_key VARCHAR(255) NULL COMMENT '同一業務eventの再実行を収束させるkey';

ALTER TABLE t_certification_event
    ADD UNIQUE KEY uk_cert_event_idempotency (tenant_id, idempotency_key);

-- 管理者はAuthorizationServiceのsuperuser境界を持つが、HRの明示権限と
-- 未seed時fail-closedの判定根拠をproductionにも残す。
INSERT IGNORE INTO t_permission_group_action (tenant_id, group_id, action_key, deny_flag)
SELECT 'default', g.id, 'certification.pii.view', 0
FROM m_permission_group g
WHERE g.tenant_id = 'default'
  AND g.group_key IN ('role-admin', 'role-hr')
  AND g.enabled = 1;
